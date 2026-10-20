package dev.atlas.coach.location

import android.location.LocationManager
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Adapted from OpenClaw `LocationCaptureManagerTest.kt` @ e3fe108378f6eca2d4e2a4333fd08bf81bb87105 (MIT). */
class LocationCaptureManagerTest {
    @Test(timeout = 5_000)
    fun api26To29_usesListenerFallbackAndRejectsStaleUpdates() = runBlocking {
        val now = System.currentTimeMillis()
        val platform = FakeLocationCapturePlatform()
        val manager = LocationCaptureManager(platform, apiLevel = 26) { now }
        val capture = async {
            manager.capture(LocationCaptureRequest(maxAgeMs = 15_000L, timeoutMs = 1_000L))
        }

        while (platform.updatesCallback == null) delay(1)
        assertEquals(0, platform.currentCalls)
        assertEquals(1, platform.updateCalls)
        platform.updatesCallback?.invoke(testFix(capturedAtUtcMillis = now - 20_000L))
        assertFalse(capture.isCompleted)

        val fresh = testFix(latitude = 40.0, capturedAtUtcMillis = now)
        platform.updatesCallback?.invoke(fresh)
        assertEquals(fresh, capture.await())
        assertEquals(1, platform.updateHandleCancels)
    }

    @Test(timeout = 5_000)
    fun api30_usesCurrentLocationThenFallsBackToListenerForStaleFix() = runBlocking {
        val now = System.currentTimeMillis()
        val platform = FakeLocationCapturePlatform()
        val manager = LocationCaptureManager(platform, apiLevel = 30) { now }
        val capture = async {
            manager.capture(LocationCaptureRequest(maxAgeMs = 15_000L, timeoutMs = 1_000L))
        }

        while (platform.currentCallback == null) delay(1)
        platform.currentCallback?.invoke(testFix(capturedAtUtcMillis = now - 30_000L))
        while (platform.updatesCallback == null) delay(1)
        val fresh = testFix(latitude = 41.0, capturedAtUtcMillis = now)
        platform.updatesCallback?.invoke(fresh)

        assertEquals(fresh, capture.await())
        assertEquals(1, platform.currentCalls)
        assertEquals(1, platform.updateCalls)
        assertEquals(1, platform.currentHandleCancels)
        assertEquals(1, platform.updateHandleCancels)
    }

    @Test(timeout = 5_000)
    fun freshCachedFixHonorsMaximumAgeAndCoarseProviderChoice() = runBlocking {
        val now = System.currentTimeMillis()
        val platform = FakeLocationCapturePlatform()
        platform.cached[LocationManager.NETWORK_PROVIDER] = testFix(capturedAtUtcMillis = now - 10_000L)
        val manager = LocationCaptureManager(platform, apiLevel = 26) { now }

        val result = manager.capture()

        assertEquals(platform.cached.getValue(LocationManager.NETWORK_PROVIDER), result)
        assertEquals(0, platform.updateCalls)
        assertEquals(0, platform.currentCalls)
    }

    @Test(timeout = 5_000)
    fun staleCacheTimesOutWithRequiredFunctionalCode() = runBlocking {
        val now = System.currentTimeMillis()
        val platform = FakeLocationCapturePlatform()
        platform.cached[LocationManager.NETWORK_PROVIDER] = testFix(capturedAtUtcMillis = now - 20_000L)
        val manager = LocationCaptureManager(platform, apiLevel = 29) { now }

        val error = runCatching {
            manager.capture(LocationCaptureRequest(timeoutMs = 40L))
        }.exceptionOrNull() as LocationCaptureException

        assertEquals(LocationErrorCode.TIMEOUT, error.code)
        assertEquals(2, platform.updateHandleCancels)
    }

    @Test(timeout = 5_000)
    fun api26CoarseTriesNetworkThenUsesFreshGpsWhenNetworkHasNoFix() = runBlocking {
        val now = System.currentTimeMillis()
        val platform = FakeLocationCapturePlatform().apply {
            enabledProviders.clear()
            enabledProviders += LocationManager.NETWORK_PROVIDER
            enabledProviders += LocationManager.GPS_PROVIDER
        }
        val manager = LocationCaptureManager(platform, apiLevel = 26) { now }
        val capture = async { manager.capture(LocationCaptureRequest(timeoutMs = 2_000L)) }

        while (platform.updateProviders.isEmpty()) delay(1)
        assertEquals(LocationManager.NETWORK_PROVIDER, platform.updateProviders[0])
        while (LocationManager.GPS_PROVIDER !in platform.updateProviders) delay(1)
        assertEquals(
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER),
            platform.updateProviders.toList(),
        )
        val freshGpsFix = testFix(latitude = 52.5, longitude = 13.4, capturedAtUtcMillis = now)
        platform.updatesCallbacksByProvider[LocationManager.GPS_PROVIDER]?.invoke(freshGpsFix)

        assertEquals(freshGpsFix, capture.await())
        assertFalse(platform.permissions.fineGranted)
        assertEquals(2, platform.updateHandleCancels)
    }

    @Test(timeout = 5_000)
    fun coarseGpsProviderSecurityRejectionReturnsUnavailableWhenNoAlternativeExists() = runBlocking {
        val platform = FakeLocationCapturePlatform().apply {
            enabledProviders.clear()
            enabledProviders += LocationManager.GPS_PROVIDER
            deniedProviders += LocationManager.GPS_PROVIDER
        }
        val error = runCatching {
            LocationCaptureManager(platform, apiLevel = 26).capture()
        }.exceptionOrNull() as LocationCaptureException

        assertEquals(LocationErrorCode.UNAVAILABLE, error.code)
        assertTrue(platform.permissions.coarseGranted)
        assertFalse(platform.permissions.fineGranted)
    }

    @Test(timeout = 5_000)
    fun coarseGpsRejectionRemainsUnsupportedAfterNetworkProviderTimesOut() = runBlocking {
        val platform = FakeLocationCapturePlatform().apply {
            deniedProviders += LocationManager.GPS_PROVIDER
        }

        val error = runCatching {
            LocationCaptureManager(platform, apiLevel = 26).capture(
                LocationCaptureRequest(timeoutMs = 1_000L),
            )
        }.exceptionOrNull() as LocationCaptureException

        assertEquals(LocationErrorCode.UNAVAILABLE, error.code)
        assertEquals(LocationManager.GPS_PROVIDER, platform.updateProviders.last())
    }

    @Test(timeout = 5_000)
    fun cachedFixIsDiscardedWhenCoarsePermissionIsRevokedDuringCacheRead() = runBlocking {
        val now = System.currentTimeMillis()
        val platform = FakeLocationCapturePlatform().apply {
            cached[LocationManager.NETWORK_PROVIDER] = testFix(capturedAtUtcMillis = now)
            onLastKnownLocation = {
                permissions = LocationPermissionState(coarseGranted = false, fineGranted = false)
                throw SecurityException("location permission revoked during cache read")
            }
        }

        val error = runCatching {
            LocationCaptureManager(platform, apiLevel = 26) { now }.capture()
        }.exceptionOrNull() as LocationCaptureException

        assertEquals(LocationErrorCode.PERMISSION_REQUIRED, error.code)
        assertTrue(error.cause is SecurityException)
    }

    @Test(timeout = 5_000)
    fun cancelledApi26RequestRemovesRegisteredListener() = runBlocking {
        val platform = FakeLocationCapturePlatform()
        val manager = LocationCaptureManager(platform, apiLevel = 26)
        val request = async { manager.capture(LocationCaptureRequest(timeoutMs = 5_000L)) }
        while (platform.updatesCallback == null) delay(1)

        request.cancelAndJoin()

        assertEquals(1, platform.updateHandleCancels)
    }

    @Test(timeout = 5_000)
    fun disabledLocationAndMissingPermissionUseStableCodes() = runBlocking {
        val disabledPlatform = FakeLocationCapturePlatform().apply { enabled = false }
        val disabled = runCatching {
            LocationCaptureManager(disabledPlatform, apiLevel = 26).capture()
        }.exceptionOrNull() as LocationCaptureException
        assertEquals(LocationErrorCode.DISABLED, disabled.code)

        val deniedPlatform = FakeLocationCapturePlatform().apply {
            permissions = LocationPermissionState(coarseGranted = false, fineGranted = false)
        }
        val denied = runCatching {
            LocationCaptureManager(deniedPlatform, apiLevel = 26).capture()
        }.exceptionOrNull() as LocationCaptureException
        assertEquals(LocationErrorCode.PERMISSION_REQUIRED, denied.code)
        assertTrue(denied.message!!.startsWith(LocationErrorCode.PERMISSION_REQUIRED))

        val unavailablePlatform = FakeLocationCapturePlatform().apply { enabledProviders.clear() }
        val unavailable = runCatching {
            LocationCaptureManager(unavailablePlatform, apiLevel = 26).capture()
        }.exceptionOrNull() as LocationCaptureException
        assertEquals(LocationErrorCode.UNAVAILABLE, unavailable.code)
    }
}
