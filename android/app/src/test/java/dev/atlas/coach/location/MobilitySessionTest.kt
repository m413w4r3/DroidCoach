package dev.atlas.coach.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MobilitySessionTest {
    @Test
    fun sessionIsBoundedToOneHourAndNeverSamplesMoreOftenThanEveryFiveMinutes() {
        val session = MobilitySession.start(10_000L)
        assertEquals(MobilitySession.MAX_DURATION_MS, session.remainingMs(10_000L))
        assertTrue(session.canBeginMeasurement(10_000L))

        val firstAttempt = session.beginMeasurement(10_000L)!!
        assertFalse(firstAttempt.canBeginMeasurement(10_000L + MobilitySession.MIN_SAMPLE_INTERVAL_MS - 1))
        assertTrue(firstAttempt.canBeginMeasurement(10_000L + MobilitySession.MIN_SAMPLE_INTERVAL_MS))
        assertTrue(firstAttempt.isFinished(10_000L + MobilitySession.MAX_DURATION_MS))
        assertFalse(firstAttempt.canBeginMeasurement(10_000L + MobilitySession.MAX_DURATION_MS))
    }

    @Test
    fun onlyChangedApproximateZonesProduceRecords() {
        val started = MobilitySession.start(0L)
        val first = started.beginMeasurement(0L)!!.recordZone(testSummary())
        assertTrue(first.zoneChanged)

        val afterFiveMinutes = first.session
            .beginMeasurement(MobilitySession.MIN_SAMPLE_INTERVAL_MS)!!
            .recordZone(testSummary(measuredAtUtcMillis = System.currentTimeMillis() + 1_000L))
        assertFalse(afterFiveMinutes.zoneChanged)

        val nextAttemptAt = MobilitySession.MIN_SAMPLE_INTERVAL_MS * 2
        val changedZone = afterFiveMinutes.session
            .beginMeasurement(nextAttemptAt)!!
            .recordZone(testSummary(latitude = 12.36, measuredAtUtcMillis = System.currentTimeMillis() + 2_000L))
        assertTrue(changedZone.zoneChanged)
    }

    @Test
    fun spacingStartsAtMeasurementEvenWhenZoneDidNotChange() {
        val session = MobilitySession.start(0L)
            .beginMeasurement(0L)!!
            .recordZone(testSummary()).session
            .beginMeasurement(MobilitySession.MIN_SAMPLE_INTERVAL_MS)!!
            .recordZone(testSummary()).session

        assertNull(session.beginMeasurement(MobilitySession.MIN_SAMPLE_INTERVAL_MS * 2 - 1L))
        assertEquals(1L, session.delayUntilNextMeasurement(MobilitySession.MIN_SAMPLE_INTERVAL_MS * 2 - 1L))
    }

    @Test
    fun processStatusExposesActiveSessionAndOnlyMarksMeasuredCompletionReliable() {
        MobilitySessionStatusStore.resetForTests()
        MobilitySessionStatusStore.sessionStarted(100L)
        assertTrue(MobilitySessionStatusStore.status.value.active)

        MobilitySessionStatusStore.measurementRecorded(testSummary(measuredAtUtcMillis = 150L), zoneChanged = true)
        MobilitySessionStatusStore.sessionStopped(MobilitySessionStopReason.MANUAL, 200L)
        val completed = MobilitySessionStatusStore.status.value.lastCompletion!!

        assertFalse(MobilitySessionStatusStore.status.value.active)
        assertEquals(1, completed.measurementCount)
        assertTrue(completed.completedReliably)
        assertTrue(completed.shouldEmitCompletedEvent)

        MobilitySessionStatusStore.resetForTests()
        MobilitySessionStatusStore.sessionStarted(300L)
        MobilitySessionStatusStore.sessionStopped(MobilitySessionStopReason.MANUAL, 400L)
        val emptyCompletion = MobilitySessionStatusStore.status.value.lastCompletion!!
        assertFalse(emptyCompletion.completedReliably)
        assertFalse(emptyCompletion.shouldEmitCompletedEvent)
    }

    @Test
    fun interruptedSessionNeverQualifiesAsCompletedEvent() {
        MobilitySessionStatusStore.resetForTests()
        MobilitySessionStatusStore.sessionStarted(500L)
        MobilitySessionStatusStore.measurementRecorded(testSummary(measuredAtUtcMillis = 550L), zoneChanged = false)
        MobilitySessionStatusStore.sessionStopped(MobilitySessionStopReason.SERVICE_INTERRUPTED, 600L)

        val completion = MobilitySessionStatusStore.status.value.lastCompletion!!
        assertTrue(completion.completedReliably)
        assertFalse(completion.shouldEmitCompletedEvent)
        MobilitySessionStatusStore.resetForTests()
    }
}
