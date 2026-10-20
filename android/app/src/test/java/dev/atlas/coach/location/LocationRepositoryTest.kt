package dev.atlas.coach.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Adapts OpenClaw `LocationHandlerTest.kt` @ e3fe108378f6eca2d4e2a4333fd08bf81bb87105 (MIT). */
class LocationRepositoryTest {
    @Test
    fun storedCoarsenedSummaryIsStableAcrossStorageAndRepeatedReads() {
        val now = System.currentTimeMillis()
        val consent = FakeLocationConsentStore(sharingEnabled = true)
        val repository = LocationRepository(FakeAtlasLocationProvider(), consent)
        val summary = LocationSummary(
            latitude = 48.856123,
            longitude = 2.352345,
            accuracyMeters = 2_500.0,
            measuredAtUtcMillis = now,
        )

        assertTrue(repository.recordMobilityZone(summary))
        assertFalse(repository.recordMobilityZone(summary))
        assertEquals(listOf(summary), consent.allSummaries())
        assertEquals(listOf(summary), repository.recentSummariesForCoach(now))
        assertEquals(listOf(summary), repository.recentSummariesForCoach(now))
    }

    @Test
    fun mobilityTrackingStoresChangedCoarseZonesLocallyAndConsentFiltersOldData() {
        val consent = FakeLocationConsentStore(sharingEnabled = false)
        val repository = LocationRepository(FakeAtlasLocationProvider(), consent)
        val localZone = testSummary(measuredAtUtcMillis = System.currentTimeMillis() - 5_000L)

        assertTrue(repository.recordMobilityZone(localZone))
        assertFalse(repository.recordMobilityZone(localZone))
        assertEquals(1, consent.allSummaries().size)
        assertTrue(consent.allSummaries().single().accuracyMeters >= 2_000.0)
        assertTrue(repository.recentSummariesForCoach(System.currentTimeMillis()).isEmpty())

        consent.setLocationSummarySharingEnabled(true)
        assertTrue(repository.recentSummariesForCoach(System.currentTimeMillis()).isEmpty())

        val postConsentZone = testSummary(latitude = 12.36,
            measuredAtUtcMillis = maxOf(
                consent.locationSummarySharingConsentAtUtcMillis() ?: 1L,
                System.currentTimeMillis(),
            ))
        assertTrue(repository.recordMobilityZone(postConsentZone))
        assertFalse(repository.recordMobilityZone(postConsentZone))
        val visible = repository.recentSummariesForCoach(System.currentTimeMillis()).single()
        assertEquals(postConsentZone.measuredAtUtcMillis, visible.measuredAtUtcMillis)
        assertTrue(visible.accuracyMeters >= 2_000.0)
        assertTrue(consent.allSummaries().all { it.accuracyMeters >= 2_000.0 })
    }

    @Test
    fun coachReadIsConsentGatedAndLimitedToRecentTimestampedSummaries() {
        val now = System.currentTimeMillis()
        val consent = FakeLocationConsentStore(sharingEnabled = true)
        consent.appendMobilitySummary(testSummary(measuredAtUtcMillis = now - 5_000L))
        consent.appendMobilitySummary(testSummary(latitude = 12.35, measuredAtUtcMillis = now - 20L * 60L * 1_000L))
        val repository = LocationRepository(FakeAtlasLocationProvider(), consent)

        val visible = repository.recentSummariesForCoach(now)

        assertEquals(1, visible.size)
        assertEquals(now - 5_000L, visible.single().measuredAtUtcMillis)
        consent.setLocationSummarySharingEnabled(false)
        assertTrue(repository.recentSummariesForCoach(now).isEmpty())
        assertTrue(consent.allSummaries().isEmpty())
    }
}
