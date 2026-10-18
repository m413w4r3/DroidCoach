/*
 * Adapted from Reef@044358fb26bf11aa85dddadf919106ca4053d926:
 * Reef/src/main/java/dev/pranav/reef/data/Routine.kt,
 * services/routines/RoutineEvaluator.kt and RoutineTimeCalculator.kt, MIT.
 * This model schedules a focus suggestion only; app quotas, alarms, blocker sessions and services are omitted.
 */
package dev.atlas.coach.routines

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

data class FocusRoutine(
    val id: String,
    val name: String,
    val schedule: RoutineSchedule,
    val focusMinutes: Int = 25,
    val isEnabled: Boolean = true,
    val preauthorizedStart: Boolean = false,
) {
    init {
        require(id.isNotBlank() && name.isNotBlank())
        require(focusMinutes in 1..180)
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "name" to name,
        "schedule" to schedule.toMap(),
        "focusMinutes" to focusMinutes,
        "isEnabled" to isEnabled,
        "preauthorizedStart" to preauthorizedStart,
    )

    companion object {
        fun create(
            name: String,
            schedule: RoutineSchedule,
            focusMinutes: Int = 25,
            preauthorizedStart: Boolean = false,
        ) = FocusRoutine(
            UUID.randomUUID().toString(), name.trim(), schedule, focusMinutes,
            preauthorizedStart = preauthorizedStart,
        )

        fun fromMap(values: Map<String, Any?>): FocusRoutine {
            val schedule = (values["schedule"] as? Map<*, *>)
                ?.entries?.associate { (key, value) -> (key as? String ?: error("Invalid schedule key")) to value }
                ?: error("Missing routine schedule")
            return FocusRoutine(
                id = values["id"] as? String ?: error("Missing routine ID"),
                name = values["name"] as? String ?: error("Missing routine name"),
                schedule = RoutineSchedule.fromMap(schedule),
                focusMinutes = (values["focusMinutes"] as? Number)?.toInt() ?: 25,
                isEnabled = values["isEnabled"] as? Boolean ?: true,
                preauthorizedStart = values["preauthorizedStart"] as? Boolean ?: false,
            )
        }
    }
}

data class RoutineSchedule(
    val type: ScheduleType,
    val timeHour: Int? = null,
    val timeMinute: Int? = null,
    val endTimeHour: Int? = null,
    val endTimeMinute: Int? = null,
    val daysOfWeek: Set<DayOfWeek> = emptySet(),
    val isRecurring: Boolean = true,
    /** Required for a one-shot schedule; null means no one-shot trigger is authorized. */
    val startDate: LocalDate? = null,
) {
    enum class ScheduleType { DAILY, WEEKLY, MANUAL }

    init {
        require(timeHour == null || timeHour in 0..23)
        require(timeMinute == null || timeMinute in 0..59)
        require(endTimeHour == null || endTimeHour in 0..23)
        require(endTimeMinute == null || endTimeMinute in 0..59)
        require(type != ScheduleType.WEEKLY || daysOfWeek.isNotEmpty())
    }

    val time: LocalTime?
        get() = if (timeHour != null && timeMinute != null) LocalTime.of(timeHour, timeMinute) else null
    val endTime: LocalTime?
        get() = if (endTimeHour != null && endTimeMinute != null) LocalTime.of(endTimeHour, endTimeMinute) else null

    fun toMap(): Map<String, Any?> = mapOf(
        "type" to type.name,
        "timeHour" to timeHour,
        "timeMinute" to timeMinute,
        "endTimeHour" to endTimeHour,
        "endTimeMinute" to endTimeMinute,
        "daysOfWeek" to daysOfWeek.map { it.name }.sorted(),
        "isRecurring" to isRecurring,
        "startDate" to startDate?.toString(),
    )

    companion object {
        fun daily(start: LocalTime, end: LocalTime, startDate: LocalDate? = null, isRecurring: Boolean = true) = RoutineSchedule(
            ScheduleType.DAILY, start.hour, start.minute, end.hour, end.minute,
            isRecurring = isRecurring, startDate = startDate,
        )

        fun weekdays(start: LocalTime, end: LocalTime, startDate: LocalDate? = null, isRecurring: Boolean = true) = RoutineSchedule(
            ScheduleType.WEEKLY, start.hour, start.minute, end.hour, end.minute,
            setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            isRecurring, startDate,
        )

        fun manual() = RoutineSchedule(ScheduleType.MANUAL)

        fun fromMap(values: Map<String, Any?>): RoutineSchedule {
            val days = (values["daysOfWeek"] as? List<*>)?.map {
                DayOfWeek.valueOf(it as? String ?: error("Invalid weekday"))
            }?.toSet().orEmpty()
            val startDate = (values["startDate"] as? String)?.let(LocalDate::parse)
            val storedRecurring = values["isRecurring"] as? Boolean ?: true
            return RoutineSchedule(
                type = ScheduleType.valueOf(values["type"] as? String ?: error("Missing routine type")),
                timeHour = (values["timeHour"] as? Number)?.toInt(),
                timeMinute = (values["timeMinute"] as? Number)?.toInt(),
                endTimeHour = (values["endTimeHour"] as? Number)?.toInt(),
                endTimeMinute = (values["endTimeMinute"] as? Number)?.toInt(),
                daysOfWeek = days,
                // Pre-migration false values had no occurrence date and behaved as recurring.
                isRecurring = storedRecurring || startDate == null,
                startDate = startDate,
            )
        }
    }
}

object RoutineTimeCalculator {
    data class SessionWindow(val start: ZonedDateTime, val end: ZonedDateTime)

    fun isActiveAt(schedule: RoutineSchedule, now: ZonedDateTime, zone: ZoneId = now.zone): Boolean =
        getSessionWindow(schedule, now, zone) != null

    fun getSessionWindow(schedule: RoutineSchedule, now: ZonedDateTime, zone: ZoneId = now.zone): SessionWindow? {
        if (schedule.type == RoutineSchedule.ScheduleType.MANUAL) return null
        val startTime = schedule.time ?: return null
        val endTime = schedule.endTime ?: return null
        val overnight = endTime <= startTime
        val localNow = now.withZoneSameInstant(zone)
        val today = localNow.toLocalDate()
        val candidates = buildList {
            val todayStart = LocalDateTime.of(today, startTime)
            add(todayStart to LocalDateTime.of(if (overnight) today.plusDays(1) else today, endTime))
            if (overnight) add(LocalDateTime.of(today.minusDays(1), startTime) to LocalDateTime.of(today, endTime))
        }
        for ((start, end) in candidates) {
            if (!isOccurrenceDate(schedule, start.toLocalDate())) continue
            val startZoned = start.atZone(zone)
            val endZoned = end.atZone(zone)
            if (localNow.isBefore(startZoned) || !localNow.isBefore(endZoned)) continue
            if (schedule.type == RoutineSchedule.ScheduleType.WEEKLY && start.dayOfWeek !in schedule.daysOfWeek) continue
            return SessionWindow(startZoned, endZoned)
        }
        return null
    }

    /** Returns the next start strictly after [after], in the explicitly supplied time zone. */
    fun nextTrigger(schedule: RoutineSchedule, after: ZonedDateTime, zone: ZoneId): ZonedDateTime? {
        if (schedule.type == RoutineSchedule.ScheduleType.MANUAL) return null
        val startTime = schedule.time ?: return null
        val localAfter = after.withZoneSameInstant(zone)
        val firstDate = when {
            !schedule.isRecurring -> schedule.startDate ?: return null
            schedule.type == RoutineSchedule.ScheduleType.DAILY -> localAfter.toLocalDate()
            else -> localAfter.toLocalDate()
        }
        val searchDays = when {
            !schedule.isRecurring -> 1
            schedule.type == RoutineSchedule.ScheduleType.DAILY -> 2
            else -> 8
        }
        for (offset in 0 until searchDays) {
            val date = firstDate.plusDays(offset.toLong())
            if (!isOccurrenceDate(schedule, date)) continue
            val candidate = LocalDateTime.of(date, startTime).atZone(zone)
            if (candidate.isAfter(localAfter)) return candidate
        }
        return null
    }

    private fun isOccurrenceDate(schedule: RoutineSchedule, date: LocalDate): Boolean {
        if (!schedule.isRecurring && schedule.startDate != date) return false
        return when (schedule.type) {
            RoutineSchedule.ScheduleType.DAILY -> true
            RoutineSchedule.ScheduleType.WEEKLY -> date.dayOfWeek in schedule.daysOfWeek
            RoutineSchedule.ScheduleType.MANUAL -> false
        }
    }
}

object RoutineEvaluator {
    data class Proposal(
        val routine: FocusRoutine,
        val window: RoutineTimeCalculator.SessionWindow,
        val requiresConfirmation: Boolean,
    )

    data class Trigger(val routine: FocusRoutine, val at: ZonedDateTime)

    fun active(
        routines: List<FocusRoutine>,
        now: ZonedDateTime,
        zone: ZoneId = now.zone,
    ): List<Pair<FocusRoutine, RoutineTimeCalculator.SessionWindow>> =
        routines.asSequence().filter { it.isEnabled }.mapNotNull { routine ->
            RoutineTimeCalculator.getSessionWindow(routine.schedule, now, zone)?.let { routine to it }
        }.sortedBy { it.first.id }.toList()

    /** Routines always surface a proposal; a stored mandate is visible for an explicit caller. */
    fun proposals(routines: List<FocusRoutine>, now: ZonedDateTime, zone: ZoneId = now.zone): List<Proposal> =
        active(routines, now, zone).map { (routine, window) ->
            Proposal(routine, window, requiresConfirmation = !routine.preauthorizedStart)
        }

    /** Returns every routine sharing the earliest upcoming trigger, preserving simultaneous windows. */
    fun nextTriggers(routines: List<FocusRoutine>, after: ZonedDateTime, zone: ZoneId): List<Trigger> {
        val candidates = routines.asSequence().filter { it.isEnabled }.mapNotNull { routine ->
            RoutineTimeCalculator.nextTrigger(routine.schedule, after, zone)?.let { Trigger(routine, it) }
        }.toList()
        val first = candidates.minOfOrNull { it.at.toInstant() } ?: return emptyList()
        return candidates.filter { it.at.toInstant() == first }.sortedBy { it.routine.id }
    }
}
