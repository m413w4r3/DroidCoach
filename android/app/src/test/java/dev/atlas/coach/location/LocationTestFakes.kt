package dev.atlas.coach.location

import java.util.concurrent.CopyOnWriteArrayList

internal class FakeAtlasLocationProvider(
    var permissions: LocationPermissionState = LocationPermissionState(coarseGranted = true, fineGranted = false),
    var fix: AtlasLocationFix = testFix(),
    var onCapture: suspend (LocationCaptureRequest) -> AtlasLocationFix = { fix },
) : AtlasLocationProvider {
    var captureCount = 0
    val requests = CopyOnWriteArrayList<LocationCaptureRequest>()

    override fun permissionState(): LocationPermissionState = permissions

    override suspend fun capture(request: LocationCaptureRequest): AtlasLocationFix {
        captureCount++
        requests += request
        return onCapture(request)
    }
}

internal class FakeLocationConsentStore(
    var sharingEnabled: Boolean = false,
) : LocationConsentStore {
    private val lock = Any()
    private val summaries = mutableListOf<LocationSummary>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var consentAtUtcMillis: Long? = if (sharingEnabled) 1L else null

    override fun isLocationSummarySharingEnabled(): Boolean = sharingEnabled

    override fun locationSummarySharingConsentAtUtcMillis(): Long? = consentAtUtcMillis

    override fun setLocationSummarySharingEnabled(enabled: Boolean) {
        val wasEnabled = sharingEnabled
        sharingEnabled = enabled
        if (enabled && !wasEnabled) consentAtUtcMillis = System.currentTimeMillis()
        if (!enabled) {
            consentAtUtcMillis = null
            synchronized(lock) { summaries.clear() }
            if (wasEnabled) listeners.forEach { it() }
        }
    }

    override fun recentLocationSummaries(nowUtcMillis: Long, maxAgeMs: Long): List<LocationSummary> {
        if (!sharingEnabled) return emptyList()
        val consentAt = consentAtUtcMillis ?: return emptyList()
        val cutoff = (nowUtcMillis - maxAgeMs).coerceAtLeast(0L)
        return synchronized(lock) {
            summaries.filter { it.measuredAtUtcMillis >= consentAt && it.measuredAtUtcMillis in cutoff..nowUtcMillis }
        }
    }

    override fun appendMobilitySummary(summary: LocationSummary): Boolean = synchronized(lock) {
        if (summaries.lastOrNull()?.sameApproximateZone(summary) == true) return false
        summaries += summary
        true
    }

    override fun appendManualSummary(summary: LocationSummary): Boolean = synchronized(lock) {
        if (!sharingEnabled) return false
        summaries += summary
        true
    }

    override fun clearLocationSummaries() = synchronized(lock) { summaries.clear() }

    override fun addSharingRevokedListener(listener: () -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    fun allSummaries(): List<LocationSummary> = synchronized(lock) { summaries.toList() }
}

internal class FakeLocationCapturePlatform(
    var permissions: LocationPermissionState = LocationPermissionState(coarseGranted = true, fineGranted = false),
) : LocationCapturePlatform {
    var enabled = true
    val enabledProviders = mutableSetOf(android.location.LocationManager.NETWORK_PROVIDER, android.location.LocationManager.GPS_PROVIDER)
    val cached = mutableMapOf<String, AtlasLocationFix>()
    val deniedProviders = mutableSetOf<String>()
    val updateProviders = CopyOnWriteArrayList<String>()
    val currentProviders = CopyOnWriteArrayList<String>()
    var onLastKnownLocation: ((String) -> Unit)? = null
    var currentCallback: ((AtlasLocationFix?) -> Unit)? = null
    var updatesCallback: ((AtlasLocationFix?) -> Unit)? = null
    val currentCallbacksByProvider = mutableMapOf<String, (AtlasLocationFix?) -> Unit>()
    val updatesCallbacksByProvider = mutableMapOf<String, (AtlasLocationFix?) -> Unit>()
    var currentCalls = 0
    var updateCalls = 0
    var currentHandleCancels = 0
    var updateHandleCancels = 0

    override fun permissionState() = permissions
    override fun isLocationEnabled() = enabled
    override fun isProviderEnabled(provider: String) = provider in enabledProviders
    override fun lastKnownLocation(provider: String): AtlasLocationFix? {
        onLastKnownLocation?.invoke(provider)
        return cached[provider]
    }

    override fun requestCurrentLocation(
        provider: String,
        callback: (AtlasLocationFix?) -> Unit,
    ): LocationCaptureHandle {
        currentCalls++
        currentProviders += provider
        if (provider in deniedProviders) throw SecurityException("provider denied")
        currentCallback = callback
        currentCallbacksByProvider[provider] = callback
        return LocationCaptureHandle { currentHandleCancels++ }
    }

    override fun requestLocationUpdates(
        provider: String,
        callback: (AtlasLocationFix?) -> Unit,
    ): LocationCaptureHandle {
        updateCalls++
        updateProviders += provider
        if (provider in deniedProviders) throw SecurityException("provider denied")
        updatesCallback = callback
        updatesCallbacksByProvider[provider] = callback
        return LocationCaptureHandle { updateHandleCancels++ }
    }
}

internal fun testFix(
    latitude: Double = 12.345678,
    longitude: Double = 45.678912,
    accuracyMeters: Double = 5.0,
    capturedAtUtcMillis: Long = System.currentTimeMillis(),
) = AtlasLocationFix(latitude, longitude, accuracyMeters, capturedAtUtcMillis)

internal fun testSummary(
    latitude: Double = 12.34,
    longitude: Double = 45.67,
    measuredAtUtcMillis: Long = System.currentTimeMillis(),
) = LocationSummary(latitude, longitude, LocationCoarsener.COARSE_METERS, measuredAtUtcMillis)
