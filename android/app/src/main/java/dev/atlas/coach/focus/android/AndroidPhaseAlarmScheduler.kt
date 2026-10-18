package dev.atlas.coach.focus.android

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.atlas.coach.focus.FocusSession
import dev.atlas.coach.focus.PhaseAlarmScheduler
import dev.atlas.coach.focus.PhaseSchedule

/** Inexact RTC alarm adapter; it schedules one phase at a time and never owns timer state. */
class AndroidPhaseAlarmScheduler(context: Context) : PhaseAlarmScheduler {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)

    override fun schedule(session: FocusSession) {
        cancel(session.id)
        val transition = PhaseSchedule.nextTransition(session) ?: return
        val intent = phaseIntent(session.id).apply {
            putExtra(EXTRA_SESSION_ID, transition.sessionId)
            putExtra(EXTRA_SEQUENCE, transition.sequence)
            putExtra(EXTRA_AT_UTC, transition.atUtcMillis)
            putExtra(EXTRA_ENDED_PHASE, transition.endedPhase.name)
            putExtra(EXTRA_NEXT_PHASE, transition.nextPhase?.name)
            putExtra(EXTRA_COMPLETED_CYCLES, transition.completedWorkCycles)
        }
        val pending = PendingIntent.getBroadcast(
            appContext,
            requestCode(session.id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Deliberately inexact; no SCHEDULE_EXACT_ALARM permission is requested.
        alarmManager.set(AlarmManager.RTC_WAKEUP, transition.atUtcMillis, pending)
    }

    override fun cancel(sessionId: String) {
        val pending = PendingIntent.getBroadcast(
            appContext,
            requestCode(sessionId),
            phaseIntent(sessionId),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarmManager.cancel(pending)
        pending.cancel()
    }

    private fun phaseIntent(sessionId: String) = Intent(appContext, FocusPhaseAlarmReceiver::class.java)
        .setAction(ACTION_PHASE)
        .setData(Uri.parse("atlas-focus://phase/${Uri.encode(sessionId)}"))

    private fun requestCode(sessionId: String): Int = sessionId.hashCode()

    companion object {
        const val ACTION_PHASE = "dev.atlas.coach.focus.action.PHASE_ALARM"
        const val EXTRA_SESSION_ID = "focus_session_id"
        const val EXTRA_SEQUENCE = "focus_phase_sequence"
        const val EXTRA_AT_UTC = "focus_phase_at_utc"
        const val EXTRA_ENDED_PHASE = "focus_phase_ended"
        const val EXTRA_NEXT_PHASE = "focus_phase_next"
        const val EXTRA_COMPLETED_CYCLES = "focus_completed_cycles"
        const val ACTION_RESCHEDULE = "dev.atlas.coach.focus.action.RESCHEDULE"
    }
}
