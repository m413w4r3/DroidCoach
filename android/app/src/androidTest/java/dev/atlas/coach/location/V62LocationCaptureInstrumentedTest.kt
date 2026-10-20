package dev.atlas.coach.location

import android.os.Build
import android.util.Log
import android.location.LocationManager
import android.view.ViewTreeObserver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.app.MainActivity
import dev.atlas.coach.test.BackendArgumentsAwareRunner
import dev.atlas.coach.test.RequiresBackendArguments
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Real Android 14 capture requires a visible MainActivity; fakes cover API fallback behavior. */
@RunWith(BackendArgumentsAwareRunner::class)
class V62LocationCaptureInstrumentedTest {
    @Test
    @RequiresBackendArguments(
        keys = ["location_fix_injection"],
        reason = "requires a synchronized host ADB geo fix while the visible production location manager request is active",
    )
    fun prepareSharingConsentBeforeHostInjectsFreshEmulatorFix() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val consent = LocationConsentStore.open(context)
        consent.clearLocationSummaries()
        consent.setLocationSummarySharingEnabled(true)
        assertTrue("The isolated AVD should record a current explicit sharing grant", consent.locationSummarySharingConsentAtUtcMillis() != null)
    }

    @Test
    @RequiresBackendArguments(
        keys = ["location_fix_injection"],
        reason = "requires a synchronized host ADB geo fix while the visible production location manager request is active",
    )
    fun injectedCoarseFixIsMinimizedAndStoredOnlyAsAZoneSummary(): Unit = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = LocationCaptureManager(context)
        val coarsePermission = context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        val finePermission = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
        assertEquals("The recipe must grant coarse access", android.content.pm.PackageManager.PERMISSION_GRANTED, coarsePermission)
        assertEquals("The recipe must keep fine access denied", android.content.pm.PackageManager.PERMISSION_DENIED, finePermission)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertEquals(
                "The recipe must not grant background location",
                android.content.pm.PackageManager.PERMISSION_DENIED,
                context.checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            )
        }
        assertTrue("The emulator must have location enabled", manager.permissionState().canCapture)
        val consent = LocationConsentStore.open(context)
        consent.clearLocationSummaries()
        consent.setLocationSummarySharingEnabled(true)
        assertTrue("The test must grant sharing before the fresh injected location", consent.isLocationSummarySharingEnabled())

        val capturedFix = AtomicReference<AtlasLocationFix?>()
        val requestStartedUtcMillis = AtomicLong(0L)
        val recordingProvider = object : AtlasLocationProvider {
            override fun permissionState() = manager.permissionState()

            override suspend fun capture(request: LocationCaptureRequest): AtlasLocationFix {
                assertEquals("The real request must stay coarse", LocationAccuracy.COARSE, request.desiredAccuracy)
                assertEquals(
                    "The acquisition must use the production 15 second cache age",
                    LocationCaptureRequest.DEFAULT_MAX_AGE_MS,
                    request.maxAgeMs,
                )
                requestStartedUtcMillis.set(System.currentTimeMillis())
                Log.i(TAG, "COARSE_CAPTURE_MANAGER_CALL max_age_ms=${request.maxAgeMs} started_utc_ms=${requestStartedUtcMillis.get()} device_uptime_ms=${android.os.SystemClock.elapsedRealtime()}")
                return manager.capture(request).also(capturedFix::set)
            }
        }
        val hostFixReceived = CountDownLatch(1)
        val hostInjectionStartedUtcMillis = AtomicLong(0L)
        val hostFixReceiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.action == HOST_FIX_INJECTED_ACTION) {
                    hostInjectionStartedUtcMillis.set(intent.getLongExtra(HOST_FIX_INJECTED_AT_EXTRA, 0L))
                    hostFixReceived.countDown()
                }
            }
        }
        context.registerReceiver(hostFixReceiver, IntentFilter(HOST_FIX_INJECTED_ACTION), Context.RECEIVER_EXPORTED)

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitMainActivityVisible(scenario)
                val summary = LocationRepository(recordingProvider, consent).captureManualSummaryForCoach()
                assertTrue("The host did not inject while the production manager request was active", hostFixReceived.await(5, TimeUnit.SECONDS))
                assertNotNull("A fresh, consented coarse fix should be summarized", summary)
                val fix = requireNotNull(capturedFix.get())
                val saved = requireNotNull(summary)
                val completedUtcMillis = System.currentTimeMillis()
                val fixAgeMs = completedUtcMillis - fix.capturedAtUtcMillis
                val requestStart = requestStartedUtcMillis.get()
                assertTrue("The operating system should return an approximate location", fix.accuracyMeters >= LocationCoarsener.COARSE_METERS)
                assertTrue("The exact Android fix timestamp must be no older than the production 15 second cache", fixAgeMs in 0L..LocationCaptureRequest.DEFAULT_MAX_AGE_MS)
                assertTrue("The exact fix timestamp must be within the request's freshness window", fix.capturedAtUtcMillis >= requestStart - LocationCaptureRequest.DEFAULT_MAX_AGE_MS)
                assertTrue("The exact fix timestamp cannot be in the future", fix.capturedAtUtcMillis <= completedUtcMillis)
                assertEquals("Coarsening must preserve the exact Android fix timestamp", fix.capturedAtUtcMillis, saved.measuredAtUtcMillis)
                assertNotEquals("The Coach summary must not contain the injected raw latitude", INJECTED_LATITUDE, saved.latitude, 0.000001)
                assertNotEquals("The Coach summary must not contain the injected raw longitude", INJECTED_LONGITUDE, saved.longitude, 0.000001)
                val injectedDistanceMeters = FloatArray(1)
                android.location.Location.distanceBetween(
                    INJECTED_LATITUDE,
                    INJECTED_LONGITUDE,
                    fix.latitude,
                    fix.longitude,
                    injectedDistanceMeters,
                )
                assertTrue(
                    "The fresh coarse fix must correspond to the injected emulator position within 25 km; distance_m=${injectedDistanceMeters.single()}",
                    injectedDistanceMeters.single() <= MAX_INJECTED_FIX_DISTANCE_METERS,
                )
                assertTrue("Coarsening must declare at least a 2 km uncertainty", saved.accuracyMeters >= LocationCoarsener.COARSE_METERS)
                assertEquals(saved, consent.recentLocationSummaries(System.currentTimeMillis()).singleOrNull())
                assertTrue("Stored locations must remain coarse", consent.recentLocationSummaries(System.currentTimeMillis()).all {
                    it.accuracyMeters >= LocationCoarsener.COARSE_METERS
                })
                Log.i(
                    TAG,
                    "COARSE_CAPTURED exact_fix_utc_ms=${fix.capturedAtUtcMillis} request_started_utc_ms=$requestStart completed_utc_ms=$completedUtcMillis fix_age_ms=$fixAgeMs accuracy_m=${fix.accuracyMeters} summary_timestamp_preserved=true",
                )
            }
        } finally {
            context.unregisterReceiver(hostFixReceiver)
            consent.setLocationSummarySharingEnabled(false)
            consent.clearLocationSummaries()
        }
    }

    @Test
    fun deniedPermissionStateReturnsFunctionalErrorWithoutCrashing() = runBlocking {
        val platform = FakeLocationCapturePlatform().apply { permissionGranted = false }
        val manager = LocationCaptureManager(platform, apiLevel = Build.VERSION.SDK_INT, nowUtcMillis = { NOW })
        assertFalse(manager.permissionState().canCapture)
        val failure = runCatching { manager.capture() }.exceptionOrNull() as? LocationCaptureException
        assertEquals(LocationErrorCode.PERMISSION_REQUIRED, failure?.code)
        assertEquals("A refused permission must not register an Android provider", 0, platform.locationUpdateCalls)
    }

    @Test
    fun api26Through29UseListenerAndReturnTimeoutOrUnavailableCodes() = runBlocking {
        val platform = FakeLocationCapturePlatform()
        val manager = LocationCaptureManager(platform, apiLevel = 26, nowUtcMillis = { NOW })
        val timeout = runCatching {
            manager.capture(LocationCaptureRequest(timeoutMs = 80, maxAgeMs = 0))
        }.exceptionOrNull() as? LocationCaptureException
        assertEquals(LocationErrorCode.TIMEOUT, timeout?.code)
        assertEquals("API 26 uses the listener fallback, not getCurrentLocation", 0, platform.currentLocationCalls)
        assertEquals(1, platform.locationUpdateCalls)
        assertEquals(1, platform.cancelCalls)

        val unavailable = FakeLocationCapturePlatform().apply { providersEnabled = false }
        val failure = runCatching {
            LocationCaptureManager(unavailable, apiLevel = 29, nowUtcMillis = { NOW }).capture()
        }.exceptionOrNull() as? LocationCaptureException
        assertEquals(LocationErrorCode.UNAVAILABLE, failure?.code)
        assertEquals("A provider outage must not be reported as a timeout", 0, unavailable.locationUpdateCalls)
    }

    @Test
    fun permissionRevocationDuringAcquisitionIsRecheckedBeforeReturningFix() = runBlocking {
        val platform = FakeLocationCapturePlatform()
        val manager = LocationCaptureManager(platform, apiLevel = 29, nowUtcMillis = { NOW })
        val capture = async {
            runCatching { manager.capture(LocationCaptureRequest(timeoutMs = 2_000, maxAgeMs = 60_000)) }
        }
        while (platform.locationUpdateCalls == 0) delay(5)
        platform.permissionGranted = false
        platform.emit(AtlasLocationFix(48.1234, 2.1234, 30.0, NOW))
        val failure = capture.await().exceptionOrNull() as? LocationCaptureException
        assertEquals(LocationErrorCode.PERMISSION_REQUIRED, failure?.code)
        assertEquals("Revoked capture listener must be unregistered", 1, platform.cancelCalls)
    }

    private class FakeLocationCapturePlatform : LocationCapturePlatform {
        @Volatile var permissionGranted = true
        @Volatile var providersEnabled = true
        @Volatile var locationUpdateCalls = 0
        @Volatile var currentLocationCalls = 0
        @Volatile var cancelCalls = 0
        @Volatile private var callback: ((AtlasLocationFix?) -> Unit)? = null

        override fun permissionState() = LocationPermissionState(permissionGranted, fineGranted = false)
        override fun isLocationEnabled() = true
        override fun isProviderEnabled(provider: String) = providersEnabled && provider == LocationManager.NETWORK_PROVIDER
        override fun lastKnownLocation(provider: String): AtlasLocationFix? = null
        override fun requestCurrentLocation(provider: String, callback: (AtlasLocationFix?) -> Unit): LocationCaptureHandle {
            currentLocationCalls++
            return LocationCaptureHandle { cancelCalls++ }
        }
        override fun requestLocationUpdates(provider: String, callback: (AtlasLocationFix?) -> Unit): LocationCaptureHandle {
            locationUpdateCalls++
            this.callback = callback
            return LocationCaptureHandle { cancelCalls++ }
        }

        fun emit(fix: AtlasLocationFix) { callback?.invoke(fix) }
    }

    companion object {
        private const val NOW = 1_800_000_000_000L
        private const val TAG = "ATLAS_V62_LOCATION"
        private const val HOST_FIX_INJECTED_ACTION = "dev.atlas.coach.location.V62_HOST_FIX_INJECTED"
        private const val HOST_FIX_INJECTED_AT_EXTRA = "injected_at_utc_ms"
        private const val INJECTED_LATITUDE = 48.123456
        private const val INJECTED_LONGITUDE = 2.123456
        private const val MAX_INJECTED_FIX_DISTANCE_METERS = 25_000f
    }

    private fun awaitMainActivityVisible(scenario: ActivityScenario<MainActivity>) {
        val focused = CountDownLatch(1)
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) focused.countDown()
        }
        scenario.onActivity { activity ->
            assertEquals("MainActivity must be RESUMED before location acquisition", Lifecycle.State.RESUMED, activity.lifecycle.currentState)
            if (activity.hasWindowFocus()) focused.countDown()
            else activity.window.decorView.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        }
        assertTrue("MainActivity did not receive window focus", focused.await(5, TimeUnit.SECONDS))
        scenario.onActivity { activity ->
            assertEquals("MainActivity left RESUMED before location acquisition", Lifecycle.State.RESUMED, activity.lifecycle.currentState)
            assertTrue("MainActivity lost window focus before location acquisition", activity.hasWindowFocus())
            val observer = activity.window.decorView.viewTreeObserver
            if (observer.isAlive) observer.removeOnWindowFocusChangeListener(listener)
        }
    }

}
