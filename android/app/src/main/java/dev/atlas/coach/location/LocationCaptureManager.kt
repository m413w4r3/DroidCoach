package dev.atlas.coach.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Adapted from OpenClaw `apps/android/app/src/main/java/ai/openclaw/app/node/LocationCaptureManager.kt`.
 * Source: openclaw/openclaw @ e3fe108378f6eca2d4e2a4333fd08bf81bb87105, MIT (OpenClaw LICENSE).
 * ATLAS keeps only a minimal fix, defaults to coarse, and uses listener capture on API 26–29.
 */
class LocationCaptureManager internal constructor(
    private val platform: LocationCapturePlatform,
    private val apiLevel: Int,
    private val nowUtcMillis: () -> Long = System::currentTimeMillis,
) : AtlasLocationProvider {

    constructor(context: Context) : this(
        platform = AndroidLocationCapturePlatform(context.applicationContext),
        apiLevel = Build.VERSION.SDK_INT,
        nowUtcMillis = System::currentTimeMillis,
    )

    override fun permissionState(): LocationPermissionState = platform.permissionState()

    override suspend fun capture(request: LocationCaptureRequest): AtlasLocationFix {
        val permissions = permissionState()
        if (!permissions.canCapture) {
            throw LocationCaptureException(
                LocationErrorCode.PERMISSION_REQUIRED,
                "${LocationErrorCode.PERMISSION_REQUIRED}: grant foreground location access in Android settings",
            )
        }
        val locationEnabled = try {
            platform.isLocationEnabled()
        } catch (failure: SecurityException) {
            if (!permissionState().canCapture) throw permissionRevoked(failure)
            throw LocationCaptureException(
                LocationErrorCode.UNAVAILABLE,
                "${LocationErrorCode.UNAVAILABLE}: cannot query device location state",
                failure,
            )
        }
        if (!locationEnabled) {
            throw LocationCaptureException(
                LocationErrorCode.DISABLED,
                "${LocationErrorCode.DISABLED}: enable device location",
            )
        }

        val requireFine = request.desiredAccuracy == LocationAccuracy.PRECISE && permissions.fineGranted
        val candidates = when {
            requireFine ->
                listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            // Coarse access can use either provider. Network is usually lower power; GPS is
            // still useful on devices/emulators that have no network location fix.
            else -> listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        }
        val enabledProviders = candidates.filter { provider ->
            try {
                platform.isProviderEnabled(provider)
            } catch (failure: SecurityException) {
                if (!permissionState().canCapture) throw permissionRevoked(failure)
                false
            }
        }
        if (enabledProviders.isEmpty()) {
            if (!permissionState().canCapture) throw permissionRevoked()
            throw LocationCaptureException(
                LocationErrorCode.UNAVAILABLE,
                "${LocationErrorCode.UNAVAILABLE}: no provider supports the requested accuracy",
            )
        }

        try {
            val cached = enabledProviders.asSequence().mapNotNull { provider ->
                try {
                    platform.lastKnownLocation(provider)
                } catch (failure: SecurityException) {
                    if (!permissionState().canCapture) throw permissionRevoked(failure)
                    null
                }
            }.filter { it.isFresh(request.maxAgeMs, nowUtcMillis()) }
                .maxByOrNull(AtlasLocationFix::capturedAtUtcMillis)
            if (cached != null) {
                requirePermissionStillGranted(requireFine)
                return cached
            }

            val startedAt = System.nanoTime()
            var providerFailureCount = 0
            var sawSecurityDenial = false
            var fresh: AtlasLocationFix? = null
            enabledProviders.forEachIndexed { index, provider ->
                if (fresh != null) return@forEachIndexed
                requirePermissionStillGranted(requireFine)
                val remainingProviders = enabledProviders.size - index
                val elapsedMs = (System.nanoTime() - startedAt) / NANOS_PER_MILLI
                val remainingMs = request.timeoutMs - elapsedMs
                if (remainingMs <= 0L) return@forEachIndexed
                val providerBudgetMs = (remainingMs / remainingProviders).coerceAtLeast(1L)
                try {
                    fresh = withTimeoutOrNull(providerBudgetMs) {
                        requestFresh(provider, request.maxAgeMs)
                    }
                } catch (failure: SecurityException) {
                    requirePermissionStillGranted(requireFine, failure)
                    // Some Android/provider combinations reject GPS with coarse-only access.
                    // Keep trying another provider, and report unavailable if none can serve it.
                    providerFailureCount++
                    sawSecurityDenial = true
                } catch (failure: Exception) {
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    providerFailureCount++
                }
                requirePermissionStillGranted(requireFine)
            }
            if (fresh == null) {
                requirePermissionStillGranted(requireFine)
                if (sawSecurityDenial || providerFailureCount == enabledProviders.size) {
                    throw LocationCaptureException(
                        LocationErrorCode.UNAVAILABLE,
                        "${LocationErrorCode.UNAVAILABLE}: no enabled provider can serve the requested accuracy",
                    )
                }
                throw LocationCaptureException(
                    LocationErrorCode.TIMEOUT,
                    "${LocationErrorCode.TIMEOUT}: no fresh fix within ${request.timeoutMs} ms",
                )
            }
            requirePermissionStillGranted(requireFine)
            return checkNotNull(fresh)
        } catch (failure: LocationCaptureException) {
            throw failure
        } catch (failure: SecurityException) {
            throw permissionRevoked(failure)
        } catch (failure: kotlinx.coroutines.CancellationException) {
            throw failure
        } catch (failure: Exception) {
            throw LocationCaptureException(
                LocationErrorCode.UNAVAILABLE,
                "${LocationErrorCode.UNAVAILABLE}: ${failure.message ?: "location provider failed"}",
                failure,
            )
        }
    }

    private suspend fun requestFresh(provider: String, maxAgeMs: Long): AtlasLocationFix =
        suspendCancellableCoroutine { continuation ->
            val closed = AtomicBoolean(false)
            val completed = AtomicBoolean(false)
            val listenerStarted = AtomicBoolean(false)
            val handles = mutableListOf<LocationCaptureHandle>()
            val lock = Any()

            fun closeHandles() {
                if (!closed.compareAndSet(false, true)) return
                val pending = synchronized(lock) { handles.toList().also { handles.clear() } }
                pending.forEach { runCatching(it::cancel) }
            }

            fun addHandle(handle: LocationCaptureHandle) {
                val cancelImmediately = synchronized(lock) {
                    if (closed.get()) true else {
                        handles += handle
                        false
                    }
                }
                if (cancelImmediately) runCatching(handle::cancel)
            }

            fun deliver(fix: AtlasLocationFix?) {
                if (fix == null || !fix.isFresh(maxAgeMs, nowUtcMillis())) return
                if (!continuation.isActive || !completed.compareAndSet(false, true)) return
                closeHandles()
                continuation.resumeWith(Result.success(fix))
            }

            fun startListener() {
                if (!continuation.isActive || !listenerStarted.compareAndSet(false, true)) return
                try {
                    addHandle(platform.requestLocationUpdates(provider, ::deliver))
                } catch (failure: Exception) {
                    if (completed.compareAndSet(false, true) && continuation.isActive) {
                        closeHandles()
                        continuation.resumeWith(Result.failure(failure))
                    }
                }
            }

            continuation.invokeOnCancellation { closeHandles() }
            if (apiLevel >= API_CURRENT_LOCATION) {
                try {
                    val handle = platform.requestCurrentLocation(provider) { fix ->
                        if (fix != null && fix.isFresh(maxAgeMs, nowUtcMillis())) deliver(fix)
                        else startListener()
                    }
                    addHandle(handle)
                } catch (failure: Exception) {
                    startListener()
                }
            } else {
                // LocationManager.getCurrentLocation does not exist before API 30.
                startListener()
            }
        }

    private fun AtlasLocationFix.isFresh(maxAgeMs: Long, now: Long): Boolean {
        val ageMs = now - capturedAtUtcMillis
        return ageMs >= 0L && ageMs <= maxAgeMs
    }

    private fun permissionRevoked(cause: Throwable? = null) = LocationCaptureException(
        LocationErrorCode.PERMISSION_REQUIRED,
        "${LocationErrorCode.PERMISSION_REQUIRED}: location permission is no longer granted",
        cause,
    )

    private fun requirePermissionStillGranted(requireFine: Boolean, cause: Throwable? = null) {
        val current = permissionState()
        if (!current.canCapture || (requireFine && !current.fineGranted)) throw permissionRevoked(cause)
    }

    companion object {
        private const val API_CURRENT_LOCATION = 30
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}

internal fun interface LocationCaptureHandle {
    fun cancel()
}

/** Android-facing methods are hidden behind a fakeable seam for API 26 fallback tests. */
internal interface LocationCapturePlatform {
    fun permissionState(): LocationPermissionState
    fun isLocationEnabled(): Boolean
    fun isProviderEnabled(provider: String): Boolean
    fun lastKnownLocation(provider: String): AtlasLocationFix?
    fun requestCurrentLocation(provider: String, callback: (AtlasLocationFix?) -> Unit): LocationCaptureHandle
    fun requestLocationUpdates(provider: String, callback: (AtlasLocationFix?) -> Unit): LocationCaptureHandle
}

private class AndroidLocationCapturePlatform(context: Context) : LocationCapturePlatform {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    override fun permissionState(): LocationPermissionState = LocationPermissionState(
        coarseGranted = appContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED,
        fineGranted = appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
    )

    override fun isLocationEnabled(): Boolean = if (Build.VERSION.SDK_INT >= 28) {
        locationManager.isLocationEnabled
    } else {
        locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    override fun isProviderEnabled(provider: String): Boolean =
        runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)

    override fun lastKnownLocation(provider: String): AtlasLocationFix? {
        if (
            appContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("Location permission was revoked")
        }
        return locationManager.getLastKnownLocation(provider)?.toAtlasFix()
    }

    override fun requestCurrentLocation(
        provider: String,
        callback: (AtlasLocationFix?) -> Unit,
    ): LocationCaptureHandle {
        if (Build.VERSION.SDK_INT < 30) {
            callback(null)
            return LocationCaptureHandle { }
        }
        if (
            appContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("Location permission was revoked")
        }
        val cancellation = CancellationSignal()
        locationManager.getCurrentLocation(provider, cancellation, { it.run() }, { location ->
            callback(location?.toAtlasFix())
        })
        return LocationCaptureHandle(cancellation::cancel)
    }

    override fun requestLocationUpdates(
        provider: String,
        callback: (AtlasLocationFix?) -> Unit,
    ): LocationCaptureHandle {
        if (
            appContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("Location permission was revoked")
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) = callback(location.toAtlasFix())
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
            @Suppress("DEPRECATION")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        locationManager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
        return LocationCaptureHandle { locationManager.removeUpdates(listener) }
    }

    private fun Location.toAtlasFix(): AtlasLocationFix? {
        if (!hasAccuracy() || !accuracy.isFinite() || accuracy < 0f || time <= 0L) return null
        return AtlasLocationFix(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = accuracy.toDouble(),
            capturedAtUtcMillis = time,
        )
    }
}
