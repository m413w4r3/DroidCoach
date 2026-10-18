package dev.atlas.coach.focus.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import dev.atlas.coach.data.AndroidStateBackend
import dev.atlas.coach.data.LocalStateStore
import dev.atlas.coach.data.StateReadResult
import dev.atlas.coach.focus.Clock
import dev.atlas.coach.focus.FocusEngine
import dev.atlas.coach.focus.FocusPhase
import dev.atlas.coach.focus.FocusSession
import dev.atlas.coach.focus.FocusStatus
import dev.atlas.coach.focus.PhaseTransition

/** Receives one inexact boundary, advances the same persisted FocusEngine state, then replans. */
class FocusPhaseAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AndroidPhaseAlarmScheduler.ACTION_PHASE) return
        val transition = intent.toTransition() ?: return
        AndroidFocusAlarmRuntime.handlePhase(context.applicationContext, transition)
    }
}

/** Boot/time-change adapter. Its state source is LocalStateStore, the application's existing source. */
class FocusRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                AndroidFocusAlarmRuntime.reschedule(context.applicationContext)
                AndroidRoutineProposalScheduler(context).rescheduleFromStoredState()
            }
        }
    }
}

object AndroidFocusAlarmRuntime {
    fun reschedule(context: Context) {
        val loaded = readState(context) ?: return
        val saved = loaded.focusSession() ?: return
        val current = FocusEngine(AndroidClock).restore(saved)
        writeSession(loaded, current)
        val scheduler = AndroidPhaseAlarmScheduler(context)
        if (current.status == FocusStatus.RUNNING) scheduler.schedule(current)
        else scheduler.cancel(current.id)

        if (saved.status == FocusStatus.RUNNING && current.status == FocusStatus.FINISHED) {
            AndroidFocusNotifications(context).notify(
                PhaseTransition(
                    sessionId = current.id,
                    sequence = 0,
                    atUtcMillis = current.updatedAtUtcMillis,
                    endedPhase = FocusPhase.WORK,
                    nextPhase = null,
                    completedWorkCycles = current.completedWorkCycles,
                ),
            )
        }
    }

    internal fun handlePhase(context: Context, transition: PhaseTransition) {
        val loaded = readState(context) ?: return
        val saved = loaded.focusSession() ?: return
        if (saved.id != transition.sessionId || saved.status != FocusStatus.RUNNING) {
            AndroidPhaseAlarmScheduler(context).cancel(transition.sessionId)
            return
        }
        if (System.currentTimeMillis() < transition.atUtcMillis) {
            AndroidPhaseAlarmScheduler(context).schedule(saved)
            return
        }

        val current = FocusEngine(AndroidClock).restore(saved)
        if (current.status == FocusStatus.RUNNING || current.status == FocusStatus.FINISHED) {
            val notice = when {
                current.status == FocusStatus.FINISHED -> transition.copy(
                    endedPhase = FocusPhase.WORK,
                    nextPhase = null,
                    completedWorkCycles = current.completedWorkCycles,
                )
                current.phase != transition.nextPhase -> transition.copy(
                    nextPhase = current.phase,
                    completedWorkCycles = current.completedWorkCycles,
                )
                else -> transition
            }
            AndroidFocusNotifications(context).notify(notice)
        }
        writeSession(loaded, current)
        val scheduler = AndroidPhaseAlarmScheduler(context)
        if (current.status == FocusStatus.RUNNING) scheduler.schedule(current)
        else scheduler.cancel(current.id)
    }

    private fun readState(context: Context): LoadedFocusState? {
        val store = LocalStateStore(AndroidStateBackend(context))
        val result = store.readDocument() as? StateReadResult.Loaded ?: return null
        return LoadedFocusState(store, result.state)
    }

    private fun writeSession(loaded: LoadedFocusState, session: FocusSession) {
        val mutable = loaded.state.toMutableMap()
        mutable["focus"] = session.toMap()
        if (session.status == FocusStatus.FINISHED || session.status == FocusStatus.CANCELLED) {
            val oldHistory = loaded.state["focusHistory"].asMapList()
                .filterNot { it["id"] == session.id }
            mutable["focusHistory"] = (listOf(session.toMap()) + oldHistory)
                .sortedByDescending { (it["createdAtUtcMillis"] as? Number)?.toLong() ?: 0L }
                .take(MAX_HISTORY)
        }
        loaded.store.write(mutable, durable = true)
    }

    private fun LoadedFocusState.focusSession(): FocusSession? {
        val values = state["focus"].asStringMap() ?: return null
        return runCatching { FocusSession.fromMap(values) }.getOrNull()
    }

    private data class LoadedFocusState(val store: LocalStateStore, val state: Map<String, Any?>)

    private object AndroidClock : Clock {
        override fun nowMillis(): Long = System.currentTimeMillis()
        override fun monotonicMillis(): Long = SystemClock.elapsedRealtime()
    }

    private const val MAX_HISTORY = 100
}

private fun Intent.toTransition(): PhaseTransition? = runCatching {
    val sessionId = getStringExtra(AndroidPhaseAlarmScheduler.EXTRA_SESSION_ID) ?: return null
    val ended = FocusPhase.valueOf(getStringExtra(AndroidPhaseAlarmScheduler.EXTRA_ENDED_PHASE) ?: return null)
    val next = getStringExtra(AndroidPhaseAlarmScheduler.EXTRA_NEXT_PHASE)?.let(FocusPhase::valueOf)
    PhaseTransition(
        sessionId = sessionId,
        sequence = getIntExtra(AndroidPhaseAlarmScheduler.EXTRA_SEQUENCE, 0),
        atUtcMillis = getLongExtra(AndroidPhaseAlarmScheduler.EXTRA_AT_UTC, 0L),
        endedPhase = ended,
        nextPhase = next,
        completedWorkCycles = getIntExtra(AndroidPhaseAlarmScheduler.EXTRA_COMPLETED_CYCLES, 0),
    )
}.getOrNull()

private fun Any?.asStringMap(): Map<String, Any?>? = (this as? Map<*, *>)
    ?.entries?.mapNotNull { (key, value) -> (key as? String)?.let { it to value } }?.toMap()

private fun Any?.asMapList(): List<Map<String, Any?>> = (this as? List<*>)
    ?.mapNotNull { it.asStringMap() }.orEmpty()
