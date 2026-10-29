package dev.atlas.coach.plan

/** Why a local activity status transition is refused. */
enum class StatusTransitionRejection {
    SAME_STATUS,
    TERMINAL_STATUS,
    POSTPONED_UNSUPPORTED,
    MEASURED_STATUS_CANNOT_TRANSITION,
    USER_DECISION_REQUIRED,
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
        provenance: StatusProvenance = StatusProvenance.DECLARED,
        source: StatusSource = StatusSource.USER_UI,
    ): StatusTransitionRejection? {
        provenanceRejection(from, to, provenance, source)?.let { return it }
        structuralRejection(from, to)?.let { return it }
        if (to == ActivityStatus.MISSED) {
            val end = engine.localToInstant(schedule)
                .plusSeconds(schedule.durationMinutes.toLong() * 60L)
            if (nowUtcMillis < end.toEpochMilli()) return StatusTransitionRejection.MISSED_BEFORE_END
        }
        return null
    }

    /** A sensor or timer observation can be recorded as a measurement, never as a status decision. */
    fun provenanceRejection(
        from: ActivityStatus,
        to: ActivityStatus,
        provenance: StatusProvenance,
        source: StatusSource,
    ): StatusTransitionRejection? {
        if (provenance == StatusProvenance.MEASURED && from != to) {
            return StatusTransitionRejection.MEASURED_STATUS_CANNOT_TRANSITION
        }
        if (source == StatusSource.FOCUS_TIMER && provenance != StatusProvenance.MEASURED) {
            return StatusTransitionRejection.USER_DECISION_REQUIRED
        }
        if (provenance == StatusProvenance.UNKNOWN) {
            return StatusTransitionRejection.USER_DECISION_REQUIRED
        }
        if (to in setOf(ActivityStatus.DONE, ActivityStatus.MISSED, ActivityStatus.CANCELLED) &&
            (source != StatusSource.USER_UI || provenance !in setOf(StatusProvenance.DECLARED, StatusProvenance.CONFIRMED))
        ) {
            return StatusTransitionRejection.USER_DECISION_REQUIRED
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
