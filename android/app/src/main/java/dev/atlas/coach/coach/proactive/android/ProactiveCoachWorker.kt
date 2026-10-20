package dev.atlas.coach.coach.proactive.android

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.atlas.coach.app.AtlasApplication
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
 * Periodic, best-effort backstop for proactive Coach decisions. WorkManager may delay execution
 * for Doze, battery policy, or system scheduling; this worker does not promise a delivery time.
 */
class ProactiveCoachWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val atlasApplication = applicationContext as? AtlasApplication ?: return Result.success()
        try {
            withTimeoutOrNull(DECISION_TIMEOUT_MILLIS) {
                val controller = withContext(Dispatchers.Main.immediate) {
                    atlasApplication.controller
                }
                awaitDecision(controller.runProactiveWorkFromWorker())
            }
            // A timeout, no due trigger, an offline adapter, or a rejected reservation all wait
            // for the next periodic window. None of them schedules a tight retry loop.
            if (!isStopped) {
                withContext(Dispatchers.Main.immediate) {
                    atlasApplication.controller.refreshProactiveCoachWork()
                }
            }
            return Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return Result.success()
        }
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
        const val DECISION_TIMEOUT_MILLIS = 60_000L
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
