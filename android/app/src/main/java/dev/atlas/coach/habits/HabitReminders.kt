package dev.atlas.coach.habits

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * A local notification schedule. Delivering a reminder never creates a check-in;
 * the user must confirm one through [HabitRepository.checkIn].
 */
data class HabitReminder(
    val hour: Int,
    val minute: Int = 0,
    val days: Set<DayOfWeek> = DayOfWeek.values().toSet(),
    val enabled: Boolean = true,
) {
    init {
        require(hour in 0..23) { "L'heure du rappel doit être comprise entre 0 et 23." }
        require(minute in 0..59) { "Les minutes du rappel doivent être comprises entre 0 et 59." }
        require(days.isNotEmpty()) { "Sélectionnez au moins un jour pour le rappel." }
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "hour" to hour,
        "minute" to minute,
        "days" to days.sortedBy(DayOfWeek::getValue).map(DayOfWeek::name),
        "enabled" to enabled,
    )

    /** Returns the first scheduled local time strictly after [after], resolving DST via `atZone`. */
    fun nextOccurrence(after: ZonedDateTime, zone: ZoneId = after.zone): ZonedDateTime? {
        if (!enabled) return null
        val localAfter = after.withZoneSameInstant(zone)
        return (0L..7L).asSequence()
            .map { offset -> localAfter.toLocalDate().plusDays(offset) }
            .filter { it.dayOfWeek in days }
            .map { date -> date.atTime(hour, minute).atZone(zone) }
            .firstOrNull { it.isAfter(localAfter) }
    }

    companion object {
        fun fromMap(map: Map<String, Any?>): HabitReminder {
            val parsedDays = (map["days"] as? Collection<*>)
                ?.mapNotNull { raw -> (raw as? String)?.let { runCatching { DayOfWeek.valueOf(it) }.getOrNull() } }
                ?.toSet()
                ?: (map["weekdaysMask"] as? Number)?.toInt()?.let { mask ->
                    DayOfWeek.values().filter { mask and (1 shl (it.value - 1)) != 0 }.toSet()
                }
                ?: DayOfWeek.values().toSet()
            return HabitReminder(
                hour = (map["hour"] as? Number)?.toInt() ?: 9,
                minute = (map["minute"] as? Number)?.toInt() ?: 0,
                days = parsedDays.ifEmpty { DayOfWeek.values().toSet() },
                enabled = map["enabled"] as? Boolean ?: true,
            )
        }

        fun dailyAt(hour: Int, minute: Int = 0) = HabitReminder(hour, minute)

        fun weekdaysAt(hour: Int, minute: Int = 0, days: Set<DayOfWeek>) =
            HabitReminder(hour, minute, days)
    }
}

data class HabitReminderSchedulingResult(
    val alarmScheduled: Boolean,
    val notificationsAvailable: Boolean,
    val message: String? = null,
)

/** Android boundary; implementations schedule notifications only, never habit actions. */
interface HabitReminderScheduler {
    fun schedule(
        habitId: String,
        habitName: String,
        reminder: HabitReminder,
        zone: ZoneId = ZoneId.systemDefault(),
    ): HabitReminderSchedulingResult

    fun cancel(habitId: String)
}

/** Pure JVM fake for repository and integration tests. */
class FakeHabitReminderScheduler(
    var notificationsAvailable: Boolean = true,
) : HabitReminderScheduler {
    private val reminders = linkedMapOf<String, Pair<String, HabitReminder>>()

    override fun schedule(
        habitId: String,
        habitName: String,
        reminder: HabitReminder,
        zone: ZoneId,
    ): HabitReminderSchedulingResult {
        require(habitId.isNotBlank())
        if (reminder.enabled) reminders[habitId] = habitName to reminder else reminders.remove(habitId)
        return HabitReminderSchedulingResult(
            alarmScheduled = reminder.enabled,
            notificationsAvailable = notificationsAvailable,
            message = if (notificationsAvailable) null else "Les notifications sont désactivées ou non autorisées.",
        )
    }

    override fun cancel(habitId: String) {
        reminders.remove(habitId)
    }

    fun scheduled(): Map<String, Pair<String, HabitReminder>> = reminders.toMap()
}
