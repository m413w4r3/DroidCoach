package dev.atlas.coach.focus.android

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.atlas.coach.data.AndroidStateBackend
import dev.atlas.coach.data.LocalStateStore
import dev.atlas.coach.data.StateReadResult
import dev.atlas.coach.routines.FocusRoutine
import dev.atlas.coach.routines.RoutineEvaluator
import dev.atlas.coach.routines.RoutineProposalScheduler
import dev.atlas.coach.routines.RoutineTimeCalculator
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Inexact local proposals. This adapter never starts Focus or an Activity from the background. */
class AndroidRoutineProposalScheduler(context: Context) : RoutineProposalScheduler {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)

    fun scheduleAll(
        routines: Collection<FocusRoutine>,
        after: ZonedDateTime = ZonedDateTime.now(),
        zone: ZoneId = after.zone,
    ) {
        routines.forEach { schedule(it, after, zone) }
    }

    override fun schedule(routine: FocusRoutine, after: ZonedDateTime, zone: ZoneId) {
        cancel(routine.id)
        if (!routine.isEnabled) return
        val trigger = RoutineTimeCalculator.nextTrigger(routine.schedule, after, zone) ?: return
        val pending = PendingIntent.getBroadcast(
            appContext,
            requestCode(routine.id),
            proposalIntent(routine.id).apply {
                putExtra(EXTRA_ROUTINE_ID, routine.id)
                putExtra(EXTRA_TRIGGER_AT_UTC, trigger.toInstant().toEpochMilli())
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Inexact alarms need no exact-alarm special access and can be delayed by Android.
        alarmManager.set(AlarmManager.RTC_WAKEUP, trigger.toInstant().toEpochMilli(), pending)
    }

    override fun cancel(routineId: String) {
        val pending = PendingIntent.getBroadcast(
            appContext,
            requestCode(routineId),
            proposalIntent(routineId),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarmManager.cancel(pending)
        pending.cancel()
    }

    internal fun rescheduleFromStoredState() {
        val store = LocalStateStore(AndroidStateBackend(appContext))
        val loaded = store.readDocument() as? StateReadResult.Loaded ?: return
        val capabilities = loaded.state["capabilities"].asStringMap().orEmpty()
        val routinesEnabled = capabilities["focusRoutines"] == true
        val routines = loaded.state["focusRoutines"].asRoutineMaps().mapNotNull {
            runCatching { FocusRoutine.fromMap(it) }.getOrNull()
        }
        routines.forEach { routine ->
            if (routinesEnabled && routine.isEnabled) schedule(routine, ZonedDateTime.now(), ZoneId.systemDefault())
            else cancel(routine.id)
        }
    }

    private fun proposalIntent(routineId: String) = Intent(appContext, FocusRoutineProposalReceiver::class.java)
        .setAction(ACTION_ROUTINE_PROPOSAL)
        .setData(Uri.parse("atlas-routine://proposal/${Uri.encode(routineId)}"))

    private fun requestCode(routineId: String): Int = routineId.hashCode()

    companion object {
        const val ACTION_ROUTINE_PROPOSAL = "dev.atlas.coach.routines.action.PROPOSAL_ALARM"
        const val EXTRA_ROUTINE_ID = "focus_routine_id"
        const val EXTRA_TRIGGER_AT_UTC = "focus_routine_trigger_at_utc"
    }
}

class FocusRoutineProposalReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AndroidRoutineProposalScheduler.ACTION_ROUTINE_PROPOSAL) return
        val routineId = intent.getStringExtra(AndroidRoutineProposalScheduler.EXTRA_ROUTINE_ID) ?: return
        val store = LocalStateStore(AndroidStateBackend(context))
        val loaded = store.readDocument() as? StateReadResult.Loaded ?: return
        if (loaded.state["capabilities"].asStringMap()?.get("focusRoutines") != true) return
        val routine = loaded.state["focusRoutines"].asRoutineMaps().asSequence().mapNotNull {
            runCatching { FocusRoutine.fromMap(it) }.getOrNull()
        }.firstOrNull { it.id == routineId && it.isEnabled } ?: return

        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        val window = RoutineTimeCalculator.getSessionWindow(routine.schedule, now, zone) ?: return
        val expectedAt = intent.getLongExtra(AndroidRoutineProposalScheduler.EXTRA_TRIGGER_AT_UTC, Long.MIN_VALUE)
        if (window.start.toInstant() != Instant.ofEpochMilli(expectedAt)) return

        AndroidFocusNotifications(context).notify(
            RoutineEvaluator.Proposal(
                routine = routine,
                window = window,
                requiresConfirmation = !routine.preauthorizedStart,
            ),
        )
        AndroidRoutineProposalScheduler(context).schedule(routine, now, zone)
    }
}

private fun Any?.asRoutineMaps(): List<Map<String, Any?>> = (this as? List<*>)
    ?.mapNotNull { value ->
        (value as? Map<*, *>)?.entries?.mapNotNull { (key, item) ->
            (key as? String)?.let { it to item }
        }?.toMap()
    }.orEmpty()

private fun Any?.asStringMap(): Map<String, Any?>? = (this as? Map<*, *>)
    ?.entries?.mapNotNull { (key, value) -> (key as? String)?.let { it to value } }?.toMap()
