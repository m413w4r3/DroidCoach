package dev.atlas.coach.coach.proactive.android

import androidx.work.ListenableWorker
import dev.atlas.coach.sync.agent.coach.AgentApiClient
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveCoachWorkerTimeoutTest {
    @Test
    fun workerDecisionTimeoutExceedsTransportBudgetAndWorkManagerLimit() {
        assertEquals(69_000L, AgentApiClient.DEFAULT_HTTP_TRANSPORT_TIMEOUT_BUDGET_MILLIS)
        assertEquals(
            AgentApiClient.DEFAULT_HTTP_TRANSPORT_TIMEOUT_BUDGET_MILLIS +
                ProactiveCoachWorker.DECISION_TIMEOUT_MARGIN_MILLIS,
            ProactiveCoachWorker.DECISION_TIMEOUT_MILLIS,
        )
        assertTrue(ProactiveCoachWorker.DECISION_TIMEOUT_MILLIS > AgentApiClient.DEFAULT_HTTP_TRANSPORT_TIMEOUT_BUDGET_MILLIS)
        assertTrue(ProactiveCoachWorker.DECISION_TIMEOUT_MILLIS < ProactiveCoachWorker.WORKMANAGER_MAX_RUNTIME_MILLIS)
    }

    @Test
    fun timeoutCompletesSuccessfullyAndDoesNotAskWorkManagerToRetry() = runBlocking {
        var decisionCancelled = false
        var afterDecisionCalled = false

        val result = runBoundedProactiveWork(
            timeoutMillis = 20L,
            decision = {
                try {
                    awaitCancellation()
                } finally {
                    decisionCancelled = true
                }
            },
            afterDecision = { afterDecisionCalled = true },
        )

        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
        assertFalse(result is ListenableWorker.Result.Retry)
        assertTrue(decisionCancelled)
        assertTrue(afterDecisionCalled)
    }
}
