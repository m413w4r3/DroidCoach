package dev.atlas.coach.coach.proactive.android

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.atlas.coach.app.AtlasApplication
import dev.atlas.coach.sync.agent.coach.AgentApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Periodic, best-effort backstop for proactive Coach decisions. The decision budget is derived
 * from AgentApiClient's default connect/read timeouts plus a 30-second margin, and remains below
 * WorkManager's ten-minute execution limit. WorkManager may delay execution for Doze, battery
 * policy, or system scheduling; this worker does not promise a delivery time.
 *
 * Timing out this waiter does not prove that the shared controller drain or its HTTP call stopped.
 * A late response is still checked after transport against both controller request generations,
 * agent enablement, and current proactive consent; the scheduler checks current consent again
 * before notification reservation and the coordinator checks enablement before publishing. The
 * generation checks are not repeated at the final publisher boundary, so a concurrent revocation
 * in the short gap after those checks remains an integration race (see DECISIONS_AUTOMATIQUES.md).
 */
class ProactiveCoachWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val atlasApplication = applicationContext as? AtlasApplication ?: return Result.success()
        return runBoundedProactiveWork(
            timeoutMillis = DECISION_TIMEOUT_MILLIS,
            decision = {
                val controller = withContext(Dispatchers.Main.immediate) {
                    atlasApplication.controller
                }
                awaitDecision(controller.runProactiveWorkFromWorker())
            },
            afterDecision = {
                // A timeout, no due trigger, an offline adapter, or a rejected reservation all
                // wait for the next periodic window; none schedules a tight retry loop.
                if (!isStopped) {
                    withContext(Dispatchers.Main.immediate) {
                        atlasApplication.controller.refreshProactiveCoachWork()
                    }
                }
            },
        )
    }

    private suspend fun awaitDecision(future: CompletableFuture<*>): Any? = suspendCancellableCoroutine { continuation ->
        future.whenComplete { value, error ->
            if (!continuation.isActive) return@whenComplete
            if (error != null) continuation.resumeWithException(error)
            else continuation.resume(value)
        }
        continuation.invokeOnCancellation { future.cancel(true) }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "atlas_proactive_coach"
        const val REPEAT_INTERVAL_MINUTES = 30L
        const val DECISION_TIMEOUT_MARGIN_MILLIS = 30_000L
        const val DECISION_TIMEOUT_MILLIS =
            AgentApiClient.DEFAULT_HTTP_TRANSPORT_TIMEOUT_BUDGET_MILLIS + DECISION_TIMEOUT_MARGIN_MILLIS
        const val WORKMANAGER_MAX_RUNTIME_MILLIS = 10 * 60 * 1_000L
    }
}

/**
 * Completes the periodic job successfully after a bounded decision, including timeout and ordinary
 * adapter failures. This keeps WorkManager from retrying immediately; the next periodic window is
 * the retry opportunity. Worker cancellation itself remains cancellation and is propagated.
 */
internal suspend fun runBoundedProactiveWork(
    timeoutMillis: Long,
    decision: suspend () -> Unit,
    afterDecision: suspend () -> Unit,
): ListenableWorker.Result {
    require(timeoutMillis > 0L)
    try {
        withTimeoutOrNull(timeoutMillis) { decision() }
        afterDecision()
        return ListenableWorker.Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return ListenableWorker.Result.success()
    }
}

/** Owns only the proactive Coach job; it never schedules or cancels UsageStats observation. */
object ProactiveCoachWorkScheduler {
    const val UNIQUE_WORK_NAME = ProactiveCoachWorker.UNIQUE_WORK_NAME
    const val REPEAT_INTERVAL_MINUTES = ProactiveCoachWorker.REPEAT_INTERVAL_MINUTES

    fun refresh(context: Context, eligible: Boolean) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        if (!eligible) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<ProactiveCoachWorker>(
            REPEAT_INTERVAL_MINUTES,
            TimeUnit.MINUTES,
        ).build()
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK_NAME)
    }
}
