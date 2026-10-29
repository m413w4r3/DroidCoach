package dev.atlas.coach.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Data
import androidx.work.CoroutineWorker
import androidx.work.workDataOf
import dev.atlas.coach.focus.android.AndroidFocusNotifications
import dev.atlas.coach.plan.ActivityStatus
import dev.atlas.coach.plan.DayPlan
import dev.atlas.coach.plan.PlanRevisionEngine
import dev.atlas.coach.plan.PlanState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class PlanActivityNotificationAction {
    OPEN,
    START,
    DONE,
    REPORT,
}

data class PlanActivityNotificationTarget(
    val planId: String,
    val activityId: String,
    val planVersion: Int,
    val localDate: String,
) {
    val operationKey: String get() = "$planId:$activityId:v$planVersion"

    init {
        require(UUID.fromString(planId).toString() == planId)
        require(UUID.fromString(activityId).toString() == activityId)
        require(planVersion >= 1)
        require(LocalDate.parse(localDate).toString() == localDate)
    }
}

data class PlanActivityNotificationActionRequest(
    val target: PlanActivityNotificationTarget,
    val action: PlanActivityNotificationAction,
)

/** WorkManager-backed local reminders for approved activities. WorkManager remains best effort. */
object PlanActivityNotificationRuntime {
    const val ACTION_OPEN_PLAN_ACTIVITY = "dev.atlas.coach.action.OPEN_PLAN_ACTIVITY_NOTIFICATION"
    const val EXTRA_PLAN_ID = "atlas_plan_notification_plan_id"
    const val EXTRA_ACTIVITY_ID = "atlas_plan_notification_activity_id"
    const val EXTRA_PLAN_VERSION = "atlas_plan_notification_plan_version"
    const val EXTRA_LOCAL_DATE = "atlas_plan_notification_local_date"
    const val EXTRA_ACTION = "atlas_plan_notification_action"

    const val WORK_TAG = "atlas_plan_activity_reminders"
    const val UNIQUE_WORK_PREFIX = "atlas_plan_activity_reminder:"
    const val NOTIFICATION_TAG_PREFIX = "atlas_plan_activity:"
    private const val INPUT_PLAN_ID = "plan_id"
    private const val INPUT_ACTIVITY_ID = "activity_id"
    private const val INPUT_PLAN_VERSION = "plan_version"
    private const val INPUT_LOCAL_DATE = "local_date"
    private const val PREFERENCES_NAME = "atlas_plan_activity_notifications"
    private const val KEY_PUBLISHED_OPERATIONS = "publishedOperations"
    private const val MAX_PUBLISHED_OPERATIONS = 500

    /** Replaces scheduled work from the last snapshot; each item has a stable operation key. */
    fun refresh(
        context: Context,
        plans: List<DayPlan>,
        eligible: Boolean,
        nowUtcMillis: Long = System.currentTimeMillis(),
    ) {
        val appContext = context.applicationContext
        val workManager = WorkManager.getInstance(appContext)
        workManager.cancelAllWorkByTag(WORK_TAG)
        if (!eligible) {
            AndroidFocusNotifications(appContext).cancelPlanActivityNotifications()
            return
        }

        val engine = PlanRevisionEngine()
        plans.distinctBy { it.planId to it.localDate }.forEach planLoop@{ plan ->
            if (plan.state != PlanState.APPROVED) return@planLoop
            plan.activities.asSequence()
                .filter { activity ->
                    activity.planId == plan.planId &&
                        activity.status == ActivityStatus.PLANNED &&
                        activity.scheduledLocal.localDate == plan.localDate
                }
                .forEach activityLoop@{ activity ->
                    val target = PlanActivityNotificationTarget(
                        planId = plan.planId,
                        activityId = activity.activityId,
                        planVersion = plan.version,
                        localDate = plan.localDate,
                    )
                    if (wasPublished(appContext, target)) return@activityLoop
                    val triggerAt = runCatching { engine.localToInstant(activity.scheduledLocal).toEpochMilli() }
                        .getOrNull() ?: return@activityLoop
                    val endsAt = triggerAt + activity.scheduledLocal.durationMinutes * 60_000L
                    if (endsAt < nowUtcMillis) return@activityLoop
                    val delayMillis = (triggerAt - nowUtcMillis).coerceAtLeast(0L)
                    val request = OneTimeWorkRequestBuilder<PlanActivityNotificationWorker>()
                        .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                        .setInputData(
                            workDataOf(
                                INPUT_PLAN_ID to target.planId,
                                INPUT_ACTIVITY_ID to target.activityId,
                                INPUT_PLAN_VERSION to target.planVersion,
                                INPUT_LOCAL_DATE to target.localDate,
                            ),
                        )
                        .addTag(WORK_TAG)
                        .build()
                    workManager.enqueueUniqueWork(
                        UNIQUE_WORK_PREFIX + target.operationKey,
                        ExistingWorkPolicy.REPLACE,
                        request,
                    )
                }
        }
    }

    fun refreshFromApplication(context: Context, enabled: Boolean, controller: AtlasController? = null) {
        val app = context.applicationContext as? AtlasApplication ?: return
        val today = runCatching { LocalDate.parse(app.planController.today) }.getOrNull() ?: return
        val plans = listOf(today, today.plusDays(1)).mapNotNull(app.planController::planForDate)
        val notifications = AndroidFocusNotifications(app.applicationContext)
        val atlasController = controller ?: app.controller
        val eligible = enabled && !atlasController.isEmergencyPaused && notifications.canPostPlanActivityReminder()
        refresh(app.applicationContext, plans, eligible)
    }

    fun cancel(context: Context, clearPublishedOperations: Boolean = false) {
        WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag(WORK_TAG)
        AndroidFocusNotifications(context.applicationContext).cancelPlanActivityNotifications()
        if (clearPublishedOperations) {
            context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_PUBLISHED_OPERATIONS)
                .commit()
        }
    }

    internal fun wasPublished(context: Context, target: PlanActivityNotificationTarget): Boolean =
        target.operationKey in context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_PUBLISHED_OPERATIONS, emptySet())
            .orEmpty()

    internal fun markPublished(context: Context, target: PlanActivityNotificationTarget) {
        val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val published = preferences.getStringSet(KEY_PUBLISHED_OPERATIONS, emptySet()).orEmpty().toMutableSet()
        published += target.operationKey
        preferences.edit()
            .putStringSet(KEY_PUBLISHED_OPERATIONS, published.sorted().takeLast(MAX_PUBLISHED_OPERATIONS).toSet())
            .commit()
    }

    fun targetFromIntent(intent: Intent?): PlanActivityNotificationActionRequest? {
        if (intent?.action != ACTION_OPEN_PLAN_ACTIVITY) return null
        return runCatching {
            val planId = intent.getStringExtra(EXTRA_PLAN_ID) ?: return null
            val activityId = intent.getStringExtra(EXTRA_ACTIVITY_ID) ?: return null
            val localDate = intent.getStringExtra(EXTRA_LOCAL_DATE) ?: return null
            if (!intent.hasExtra(EXTRA_PLAN_VERSION)) return null
            val version = intent.getIntExtra(EXTRA_PLAN_VERSION, -1)
            val action = PlanActivityNotificationAction.valueOf(intent.getStringExtra(EXTRA_ACTION) ?: return null)
            PlanActivityNotificationActionRequest(
                target = PlanActivityNotificationTarget(planId, activityId, version, localDate),
                action = action,
            )
        }.getOrNull()
    }

    fun actionPendingIntent(
        context: Context,
        target: PlanActivityNotificationTarget,
        action: PlanActivityNotificationAction,
    ): PendingIntent {
        val identity = "atlas-plan://${action.name.lowercase()}/${target.localDate}/${target.operationKey}"
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_PLAN_ACTIVITY)
            .setData(Uri.parse(identity))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_PLAN_ID, target.planId)
            .putExtra(EXTRA_ACTIVITY_ID, target.activityId)
            .putExtra(EXTRA_PLAN_VERSION, target.planVersion)
            .putExtra(EXTRA_LOCAL_DATE, target.localDate)
            .putExtra(EXTRA_ACTION, action.name)
        val requestCode = 31 * target.operationKey.hashCode() + action.ordinal
        return PendingIntent.getActivity(
            context.applicationContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    internal fun targetFromWorkData(data: Data): PlanActivityNotificationTarget? = runCatching {
        PlanActivityNotificationTarget(
            planId = data.getString(INPUT_PLAN_ID) ?: return null,
            activityId = data.getString(INPUT_ACTIVITY_ID) ?: return null,
            planVersion = data.getInt(INPUT_PLAN_VERSION, -1),
            localDate = data.getString(INPUT_LOCAL_DATE) ?: return null,
        )
    }.getOrNull()
}

class PlanActivityNotificationWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? AtlasApplication ?: return Result.success()
        val target = PlanActivityNotificationRuntime.targetFromWorkData(inputData) ?: return Result.success()
        return withContext(Dispatchers.Main.immediate) {
            if (isStopped) Result.success()
            else {
                app.controller.publishPlanActivityReminder(target)
                Result.success()
            }
        }
    }
}
