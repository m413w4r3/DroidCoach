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
    )

    companion object {
        fun create(name: String, schedule: RoutineSchedule, focusMinutes: Int = 25) =
            FocusRoutine(UUID.randomUUID().toString(), name.trim(), schedule, focusMinutes)

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
    )

    companion object {
        fun daily(start: LocalTime, end: LocalTime) = RoutineSchedule(
            ScheduleType.DAILY, start.hour, start.minute, end.hour, end.minute,
        )

        fun weekdays(start: LocalTime, end: LocalTime) = RoutineSchedule(
            ScheduleType.WEEKLY, start.hour, start.minute, end.hour, end.minute,
            setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
        )

        fun manual() = RoutineSchedule(ScheduleType.MANUAL)

        fun fromMap(values: Map<String, Any?>): RoutineSchedule {
            val days = (values["daysOfWeek"] as? List<*>)?.map {
                DayOfWeek.valueOf(it as? String ?: error("Invalid weekday"))
            }?.toSet().orEmpty()
            return RoutineSchedule(
                type = ScheduleType.valueOf(values["type"] as? String ?: error("Missing routine type")),
                timeHour = (values["timeHour"] as? Number)?.toInt(),
                timeMinute = (values["timeMinute"] as? Number)?.toInt(),
                endTimeHour = (values["endTimeHour"] as? Number)?.toInt(),
                endTimeMinute = (values["endTimeMinute"] as? Number)?.toInt(),
                daysOfWeek = days,
                isRecurring = values["isRecurring"] as? Boolean ?: true,
            )
        }
    }
}

object RoutineTimeCalculator {
    data class SessionWindow(val start: ZonedDateTime, val end: ZonedDateTime)

    fun isActiveAt(schedule: RoutineSchedule, now: ZonedDateTime): Boolean = getSessionWindow(schedule, now) != null

    fun getSessionWindow(schedule: RoutineSchedule, now: ZonedDateTime): SessionWindow? {
        if (schedule.type == RoutineSchedule.ScheduleType.MANUAL) return null
        val startTime = schedule.time ?: return null
        val endTime = schedule.endTime ?: return null
        val overnight = endTime <= startTime
        val today = now.toLocalDate()
        val candidates = buildList {
            val todayStart = LocalDateTime.of(today, startTime)
            add(todayStart to LocalDateTime.of(if (overnight) today.plusDays(1) else today, endTime))
            if (overnight) add(LocalDateTime.of(today.minusDays(1), startTime) to LocalDateTime.of(today, endTime))
        }
        for ((start, end) in candidates) {
            val startZoned = start.atZone(now.zone)
            val endZoned = end.atZone(now.zone)
            if (now.isBefore(startZoned) || !now.isBefore(endZoned)) continue
            if (schedule.type == RoutineSchedule.ScheduleType.WEEKLY && start.dayOfWeek !in schedule.daysOfWeek) continue
            return SessionWindow(startZoned, endZoned)
        }
        return null
    }
}

object RoutineEvaluator {
    fun active(routines: List<FocusRoutine>, now: ZonedDateTime): List<Pair<FocusRoutine, RoutineTimeCalculator.SessionWindow>> =
        routines.asSequence().filter { it.isEnabled }.mapNotNull { routine ->
            RoutineTimeCalculator.getSessionWindow(routine.schedule, now)?.let { routine to it }
        }.toList()
}
