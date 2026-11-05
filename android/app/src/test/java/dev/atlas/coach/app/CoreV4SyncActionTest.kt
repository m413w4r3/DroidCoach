package dev.atlas.coach.app

import androidx.work.WorkInfo
import dev.atlas.coach.sync.v4.ManualCoreV4SyncRequestGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class CoreV4SyncActionTest {
    @Test
    fun rapidRequestsAreDebouncedAndTheGateExpiresWithoutAWorker() {
        var now = 1_000L
        val gate = ManualCoreV4SyncRequestGate(nowMillis = { now }, debounceMillis = 500L)
        val enqueued = AtomicInteger()

        assertTrue(gate.submit { enqueued.incrementAndGet() })
        assertTrue(gate.isBusy)
        assertFalse(gate.submit { enqueued.incrementAndGet() })
        assertEquals(1, enqueued.get())

        now += 501L
        assertFalse(gate.isBusy)
        assertTrue(gate.submit { enqueued.incrementAndGet() })
        assertEquals(2, enqueued.get())
    }

    @Test
    fun enqueueFailureReleasesBusyGate() {
        val gate = ManualCoreV4SyncRequestGate(nowMillis = { 1_000L })

        assertFalse(gate.submit { error("WorkManager unavailable") })
        assertFalse(gate.isBusy)
        assertTrue(gate.submit {})
    }

    @Test
    fun workStatesHaveClearUserFacingStatuses() {
        assertEquals("Synchronisation en attente.", coreV4SyncActionStatus(WorkInfo.State.ENQUEUED, "RUNNING", ""))
        assertEquals("Synchronisation en cours…", coreV4SyncActionStatus(WorkInfo.State.RUNNING, "RUNNING", ""))
        assertEquals(
            "Synchronisation terminée. Aucune projection à envoyer.",
            coreV4SyncActionStatus(WorkInfo.State.SUCCEEDED, "NO_PENDING_SOURCE", ""),
        )
        assertEquals(
            "Échec de la synchronisation. Vérifiez la connexion puis réessayez.",
            coreV4SyncActionStatus(WorkInfo.State.FAILED, "AUTH", "secret must not be displayed"),
        )
        assertEquals(
            "Échec de synchronisation. Reconnectez le relais dans les réglages.",
            coreV4SyncActionStatus(WorkInfo.State.SUCCEEDED, "AUTHENTICATION", "secret must not be displayed"),
        )
        assertEquals("Synchronisation annulée.", coreV4SyncActionStatus(WorkInfo.State.CANCELLED, "RUNNING", ""))
        assertEquals("Synchronisation en attente.", coreV4SyncActionStatus(WorkInfo.State.BLOCKED, "RUNNING", ""))
        assertFalse(coreV4SyncActionIsBusy(false, false, WorkInfo.State.CANCELLED))
        assertFalse(coreV4SyncActionIsBusy(false, false, WorkInfo.State.BLOCKED))
        assertTrue(coreV4SyncActionIsBusy(false, false, WorkInfo.State.RUNNING))
    }
}
