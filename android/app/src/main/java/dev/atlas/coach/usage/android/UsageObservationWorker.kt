package dev.atlas.coach.usage.android

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.atlas.coach.app.AtlasApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Background backstop for the visible 10-second poll. It delegates to the existing controller,
 * which owns consent gates, cursor, retention, filtering, and policy evaluation. No usage data or
 * rules are duplicated here.
 */
class UsageObservationWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = try {
        withContext(Dispatchers.Main.immediate) {
            val atlasApplication = applicationContext as? AtlasApplication
                ?: return@withContext Result.failure()
            atlasApplication.controller.pollUsageObservation()
            Result.success()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Leave the unique periodic request scheduled; the next 30-minute run will re-check the
        // controller's current OS permission and ATLAS consent. Do not turn a transient error into
        // an immediate polling loop.
        Result.failure()
    }
}

/** S6 owns calls to these helpers when observation is explicitly enabled or revoked. */
object UsageObservationWorkScheduler {
    const val UNIQUE_WORK_NAME = "atlas_usage_observation"
    const val REPEAT_INTERVAL_MINUTES = 30L

    fun enqueueUniquePeriodicWork(context: Context) {
        val request = PeriodicWorkRequestBuilder<UsageObservationWorker>(
            REPEAT_INTERVAL_MINUTES,
            TimeUnit.MINUTES,
        ).build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK_NAME)
    }
}
