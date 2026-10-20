package dev.atlas.coach.usage.android

import org.junit.Assert.assertEquals
import org.junit.Test

class UsageObservationWorkerTest {
    @Test
    fun `worker uses one named 30 minute observation schedule`() {
        assertEquals("atlas_usage_observation", UsageObservationWorkScheduler.UNIQUE_WORK_NAME)
        assertEquals(30L, UsageObservationWorkScheduler.REPEAT_INTERVAL_MINUTES)
    }
}
