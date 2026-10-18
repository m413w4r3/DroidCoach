package dev.atlas.coach.focus

/** A phase boundary derived only from a persisted FocusSession snapshot. */
data class PhaseTransition(
    val sessionId: String,
    val sequence: Int,
    val atUtcMillis: Long,
    val endedPhase: FocusPhase,
    val nextPhase: FocusPhase?,
    val completedWorkCycles: Int,
) {
    val completesSession: Boolean get() = nextPhase == null
}

/**
 * Pure alarm calculation. The sequence is lazy: continuous plans can run forever, and Android
 * schedules only its first boundary before deriving the next one from the saved engine state.
 */
object PhaseSchedule {
    fun transitions(session: FocusSession): Sequence<PhaseTransition> = sequence {
        if (session.status != FocusStatus.RUNNING) return@sequence

        var phase = session.phase
        var completedCycles = session.completedWorkCycles
        var atUtcMillis = safeAdd(session.updatedAtUtcMillis, session.remainingMillis)
        var sequence = 0

        while (true) {
            val nextPhase = if (phase == FocusPhase.WORK) {
                FocusTransitions.afterWork(session, completedCycles + 1)
            } else {
                FocusTransitions.afterBreak()
            }
            if (phase == FocusPhase.WORK) completedCycles++
            yield(
                PhaseTransition(
                    sessionId = session.id,
                    sequence = sequence++,
                    atUtcMillis = atUtcMillis,
                    endedPhase = phase,
                    nextPhase = nextPhase,
                    completedWorkCycles = completedCycles,
                ),
            )
            if (nextPhase == null) return@sequence

            phase = nextPhase
            val durationSeconds = when (phase) {
                FocusPhase.WORK -> session.workSeconds
                FocusPhase.SHORT_BREAK -> session.shortBreakSeconds
                FocusPhase.LONG_BREAK -> session.longBreakSeconds
            }
            atUtcMillis = safeAdd(atUtcMillis, durationSeconds * MILLIS_PER_SECOND)
        }
    }

    fun nextTransition(session: FocusSession): PhaseTransition? = transitions(session).firstOrNull()

    private fun safeAdd(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private const val MILLIS_PER_SECOND = 1_000L
}

enum class PhaseNotificationResult { POSTED, PERMISSION_REQUIRED, NOTIFICATIONS_DISABLED }

interface PhaseNotifier {
    fun notify(transition: PhaseTransition): PhaseNotificationResult
    fun cancel(sessionId: String)
}

interface PhaseAlarmScheduler {
    fun schedule(session: FocusSession)
    fun cancel(sessionId: String)
}

/** JVM fake that records only the next alarm, matching the production rolling schedule. */
class FakePhaseAlarmScheduler : PhaseAlarmScheduler {
    private val alarms = linkedMapOf<String, PhaseTransition>()

    val scheduledTransitions: List<PhaseTransition> get() = alarms.values.toList()

    override fun schedule(session: FocusSession) {
        cancel(session.id)
        PhaseSchedule.nextTransition(session)?.let { alarms[session.id] = it }
    }

    override fun cancel(sessionId: String) {
        alarms.remove(sessionId)
    }
}

class FakePhaseNotifier(var permissionGranted: Boolean = true) : PhaseNotifier {
    private val posted = mutableListOf<PhaseTransition>()
    val notifications: List<PhaseTransition> get() = posted.toList()

    override fun notify(transition: PhaseTransition): PhaseNotificationResult {
        if (!permissionGranted) return PhaseNotificationResult.PERMISSION_REQUIRED
        posted += transition
        return PhaseNotificationResult.POSTED
    }

    override fun cancel(sessionId: String) {
        posted.removeAll { it.sessionId == sessionId }
    }
}
