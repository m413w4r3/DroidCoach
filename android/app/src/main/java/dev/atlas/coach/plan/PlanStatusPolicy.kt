package dev.atlas.coach.plan

/** Why a local activity status transition is refused. */
enum class StatusTransitionRejection {
    SAME_STATUS,
    TERMINAL_STATUS,
    POSTPONED_UNSUPPORTED,
    MISSED_BEFORE_END,
    INVALID_TRANSITION,
}

/**
 * Deterministic local status rules. The engine applies the structural part; the
 * controller adds the clock-dependent part (MISSED only after the slot ends).
 * CANCELLED, DONE, MISSED and POSTPONED are terminal in this version.
 */
object PlanStatusPolicy {
    private val TERMINAL = setOf(
        ActivityStatus.CANCELLED,
        ActivityStatus.DONE,
        ActivityStatus.MISSED,
        ActivityStatus.POSTPONED,
    )

    fun structuralRejection(
        from: ActivityStatus,
        to: ActivityStatus,
    ): StatusTransitionRejection? {
        if (from == to) return StatusTransitionRejection.SAME_STATUS
        if (from in TERMINAL) return StatusTransitionRejection.TERMINAL_STATUS
        if (to == ActivityStatus.POSTPONED) return StatusTransitionRejection.POSTPONED_UNSUPPORTED
        return when (from) {
            ActivityStatus.PLANNED -> when (to) {
                ActivityStatus.IN_PROGRESS,
                ActivityStatus.DONE,
                ActivityStatus.MISSED,
                ActivityStatus.CANCELLED,
                -> null
                else -> StatusTransitionRejection.INVALID_TRANSITION
            }
            ActivityStatus.IN_PROGRESS -> when (to) {
                ActivityStatus.DONE,
                ActivityStatus.MISSED,
                ActivityStatus.CANCELLED,
                -> null
                else -> StatusTransitionRejection.INVALID_TRANSITION
            }
            else -> StatusTransitionRejection.INVALID_TRANSITION
        }
    }

    fun rejection(
        from: ActivityStatus,
        to: ActivityStatus,
        nowUtcMillis: Long,
        schedule: LocalSchedule,
        engine: PlanRevisionEngine = PlanRevisionEngine(),
    ): StatusTransitionRejection? {
        structuralRejection(from, to)?.let { return it }
        if (to == ActivityStatus.MISSED) {
            val end = engine.localToInstant(schedule)
                .plusSeconds(schedule.durationMinutes.toLong() * 60L)
            if (nowUtcMillis < end.toEpochMilli()) return StatusTransitionRejection.MISSED_BEFORE_END
        }
        return null
    }

    fun allowedTargets(
        from: ActivityStatus,
        nowUtcMillis: Long,
        schedule: LocalSchedule,
        engine: PlanRevisionEngine = PlanRevisionEngine(),
    ): List<ActivityStatus> = listOf(
        ActivityStatus.IN_PROGRESS,
        ActivityStatus.DONE,
        ActivityStatus.MISSED,
        ActivityStatus.CANCELLED,
    ).filter { to -> rejection(from, to, nowUtcMillis, schedule, engine) == null }
}
