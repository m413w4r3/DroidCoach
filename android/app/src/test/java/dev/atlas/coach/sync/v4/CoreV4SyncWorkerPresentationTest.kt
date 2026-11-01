package dev.atlas.coach.sync.v4

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreV4SyncWorkerPresentationTest {
    @Test
    fun completedSyncStatusIsNotResetByANoOpFollowUpPass() {
        assertFalse(shouldRecordCoreV4Running("SYNCED"))
        assertTrue(shouldRecordCoreV4Running("RUNNING"))
        assertTrue(shouldRecordCoreV4Running("LOCAL_ONLY"))
        assertTrue(shouldRecordCoreV4Running("UNAVAILABLE"))
        assertTrue(shouldRecordCoreV4Running("NO_PENDING_SOURCE"))
    }
}
