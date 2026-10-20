package dev.atlas.coach.location

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Adapted from OpenClaw `LocationPrivacyTest.kt` @ e3fe108378f6eca2d4e2a4333fd08bf81bb87105 (MIT). */
class LocationPrivacyTest {
    @Test
    fun automaticSummaryIsCoarseEvenWithFineOsGrant() = runBlocking {
        val provider = FakeAtlasLocationProvider(
            permissions = LocationPermissionState(coarseGranted = true, fineGranted = true),
        )
        val consent = FakeLocationConsentStore(sharingEnabled = true)
        val repository = LocationRepository(provider, consent)

        val summary = repository.captureAutomaticSummary()

        assertTrue(provider.requests.single().desiredAccuracy == LocationAccuracy.COARSE)
        assertNotNullCoarse(summary)
        assertEquals(provider.fix.capturedAtUtcMillis, summary!!.measuredAtUtcMillis)
    }

    @Test
    fun osPermissionAndAgentSharingConsentAreIndependent() = runBlocking {
        val provider = FakeAtlasLocationProvider()
        val consent = FakeLocationConsentStore(sharingEnabled = false)
        val repository = LocationRepository(provider, consent)

        assertNull(repository.captureAutomaticSummary())
        assertEquals(0, provider.captureCount)

        consent.setLocationSummarySharingEnabled(true)
        provider.permissions = LocationPermissionState(coarseGranted = false, fineGranted = false)
        val error = runCatching { repository.captureAutomaticSummary() }.exceptionOrNull() as LocationCaptureException
        assertEquals(LocationErrorCode.PERMISSION_REQUIRED, error.code)
        assertEquals(0, provider.captureCount)
    }

    @Test
    fun sharingRevokedWhileCaptureSuspendsDiscardsTheResult() = runBlocking {
        val consent = FakeLocationConsentStore(sharingEnabled = true)
        val provider = FakeAtlasLocationProvider(onCapture = {
            consent.setLocationSummarySharingEnabled(false)
            testFix()
        })
        val repository = LocationRepository(provider, consent)

        assertNull(repository.captureAutomaticSummary())
        assertTrue(consent.allSummaries().isEmpty())
    }

    @Test
    fun manualCapturePersistsOnlyCoarseSummaryWhenSharingConsentIsActive() = runBlocking {
        val provider = FakeAtlasLocationProvider(
            permissions = LocationPermissionState(coarseGranted = true, fineGranted = true),
        )
        val consent = FakeLocationConsentStore(sharingEnabled = false)
        val repository = LocationRepository(provider, consent)

        assertNull(repository.captureManualSummaryForCoach())
        assertEquals(0, provider.captureCount)

        consent.setLocationSummarySharingEnabled(true)
        provider.fix = testFix(capturedAtUtcMillis = System.currentTimeMillis())
        val summary = repository.captureManualSummaryForCoach()

        assertNotNullCoarse(summary)
        assertEquals(LocationAccuracy.COARSE, provider.requests.single().desiredAccuracy)
        assertEquals(listOf(summary), consent.allSummaries())
    }

    @Test
    fun manualSummaryIsNotPersistedWhenSharingIsRevokedDuringCapture() = runBlocking {
        val consent = FakeLocationConsentStore(sharingEnabled = true)
        val provider = FakeAtlasLocationProvider(onCapture = {
            consent.setLocationSummarySharingEnabled(false)
            testFix()
        })
        val repository = LocationRepository(provider, consent)

        assertNull(repository.captureManualSummaryForCoach())
        assertTrue(consent.allSummaries().isEmpty())
    }

    @Test
    fun mobilityCaptureUsesCoarseOsPermissionWithoutCoachSharingConsent() = runBlocking {
        val provider = FakeAtlasLocationProvider(
            permissions = LocationPermissionState(coarseGranted = true, fineGranted = true),
        )
        val consent = FakeLocationConsentStore(sharingEnabled = false)
        val repository = LocationRepository(provider, consent)

        val summary = repository.captureMobilitySummary()
        assertTrue(repository.recordMobilityZone(summary))

        assertEquals(LocationAccuracy.COARSE, provider.requests.single().desiredAccuracy)
        assertNotNullCoarse(summary)
        assertEquals(1, consent.allSummaries().size)
        assertTrue(consent.allSummaries().single().accuracyMeters >= 2_000.0)
        assertTrue(repository.recentSummariesForCoach(System.currentTimeMillis()).isEmpty())
    }

    @Test
    fun osPermissionRevokedWhileCaptureSuspendsRejectsTheResult() = runBlocking {
        val consent = FakeLocationConsentStore(sharingEnabled = true)
        val provider = FakeAtlasLocationProvider()
        provider.onCapture = {
            provider.permissions = LocationPermissionState(coarseGranted = false, fineGranted = false)
            testFix()
        }
        val repository = LocationRepository(provider, consent)

        val error = runCatching { repository.captureAutomaticSummary() }.exceptionOrNull() as LocationCaptureException

        assertEquals(LocationErrorCode.PERMISSION_REQUIRED, error.code)
        assertTrue(consent.allSummaries().isEmpty())
    }

    @Test
    fun finePermissionRevokedDuringPreciseCaptureDiscardsThatFix() = runBlocking {
        val provider = FakeAtlasLocationProvider(
            permissions = LocationPermissionState(coarseGranted = true, fineGranted = true),
        )
        provider.onCapture = {
            provider.permissions = LocationPermissionState(coarseGranted = true, fineGranted = false)
            testFix()
        }
        val repository = LocationRepository(provider, FakeLocationConsentStore())

        val error = runCatching {
            repository.captureForLocalUse(LocationAccuracy.PRECISE)
        }.exceptionOrNull() as LocationCaptureException

        assertEquals(LocationErrorCode.PERMISSION_REQUIRED, error.code)
    }

    @Test
    fun preciseLocalCaptureRequiresAnExplicitRequestAndFineOsGrant() = runBlocking {
        val coarseOnly = FakeAtlasLocationProvider()
        val coarseRepository = LocationRepository(coarseOnly, FakeLocationConsentStore())
        val approximate = coarseRepository.captureForLocalUse(LocationAccuracy.PRECISE)
        assertEquals(LocationAccuracy.COARSE, coarseOnly.requests.single().desiredAccuracy)
        assertTrue(approximate.accuracyMeters >= LocationCoarsener.COARSE_METERS)

        val fine = FakeAtlasLocationProvider(
            permissions = LocationPermissionState(coarseGranted = true, fineGranted = true),
        )
        val fineRepository = LocationRepository(fine, FakeLocationConsentStore())
        val precise = fineRepository.captureForLocalUse(LocationAccuracy.PRECISE)
        assertEquals(LocationAccuracy.PRECISE, fine.requests.single().desiredAccuracy)
        assertEquals(5.0, precise.accuracyMeters, 0.0)
    }

    @Test
    fun coarseSummaryContainsOnlyCoordinatesAccuracyAndTimestamp() {
        val raw = testFix(accuracyMeters = 3.0)
        val summary = LocationCoarsener({ 0L }).coarsen(raw)

        assertTrue(summary.accuracyMeters >= 2_000.0)
        assertEquals(raw.capturedAtUtcMillis, summary.measuredAtUtcMillis)
        assertTrue(summary.latitude.isFinite() && summary.longitude.isFinite())
    }

    @Test
    fun coarseningStaysFiniteAtPolesAndAntimeridian() {
        val coarsener = LocationCoarsener({ 0L })
        listOf(
            90.0 to 180.0,
            -90.0 to -180.0,
            0.0 to 179.999999,
            0.0 to -179.999999,
        ).forEach { (latitude, longitude) ->
            val summary = coarsener.coarsen(testFix(latitude, longitude))
            assertTrue(summary.latitude.isFinite() && summary.latitude in -90.0..90.0)
            assertTrue(summary.longitude.isFinite() && summary.longitude >= -180.0 && summary.longitude < 180.0)
            assertTrue(summary.accuracyMeters >= 2_000.0)
        }
    }

    @Test
    fun defaultRepositoriesShareOneProcessWideCoarsener() = runBlocking {
        val consent = FakeLocationConsentStore(sharingEnabled = true)
        val fix = testFix(capturedAtUtcMillis = 1_234L)
        val first = LocationRepository(FakeAtlasLocationProvider(fix = fix), consent)
        val second = LocationRepository(FakeAtlasLocationProvider(fix = fix), consent)

        assertEquals(first.captureAutomaticSummary(), second.captureAutomaticSummary())
    }

    @Test
    fun retiredAlwaysModeDoesNotRestoreBackgroundTracking() {
        assertEquals(LocationMode.Off, LocationMode.fromRawValue("always"))
        assertEquals(LocationMode.WhileUsing, LocationMode.fromRawValue("WHILEUSING"))
        assertEquals(LocationMode.Off, LocationMode.fromRawValue("unknown"))
    }

    @Test
    fun initialPermissionRequestIsCoarseOnly() {
        assertEquals(
            listOf("android.permission.ACCESS_COARSE_LOCATION"),
            LocationPermissionPolicy.initialRequestPermissions().toList(),
        )
        assertTrue(LocationPermissionPolicy.preciseFeatureRequestPermissions(false).isEmpty())
        assertEquals(
            listOf(
                "android.permission.ACCESS_COARSE_LOCATION",
                "android.permission.ACCESS_FINE_LOCATION",
            ),
            LocationPermissionPolicy.preciseFeatureRequestPermissions(true).toList(),
        )
    }

    private fun assertNotNullCoarse(summary: LocationSummary?) {
        val value = requireNotNull(summary)
        assertTrue(value.accuracyMeters >= 2_000.0)
        assertTrue(value.latitude.isFinite() && value.longitude.isFinite())
    }
}
