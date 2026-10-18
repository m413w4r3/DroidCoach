package dev.atlas.coach.habits

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import dev.atlas.coach.habits.thirdparty.loop.HabitStrengthCalculator
import dev.atlas.coach.habits.thirdparty.loop.LoopHabitCsvExporter

fun interface HabitClock {
    fun nowUtcMillis(): Long
}

enum class HabitScheduleKind { DAILY, WEEKDAYS, TIMES_PER_WEEK }

/** A small, framework-free schedule whose fields can be persisted directly. */
data class HabitSchedule(
    val kind: HabitScheduleKind,
    val weekdaysMask: Int = 0,
    val timesPerWeek: Int = 0,
) {
    init {
        when (kind) {
            HabitScheduleKind.DAILY -> require(weekdaysMask == 0 && timesPerWeek == 0)
            HabitScheduleKind.WEEKDAYS -> require(weekdaysMask in 1..127 && timesPerWeek == 0)
            HabitScheduleKind.TIMES_PER_WEEK -> require(weekdaysMask == 0 && timesPerWeek in 1..7)
        }
    }

    fun isDueOn(date: LocalDate, checkIns: Collection<HabitCheckIn> = emptyList()): Boolean = when (kind) {
        HabitScheduleKind.DAILY -> true
        HabitScheduleKind.WEEKDAYS -> weekdaysMask and (1 shl (date.dayOfWeek.value - 1)) != 0
        HabitScheduleKind.TIMES_PER_WEEK -> {
            val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val completedEarlierThisWeek = checkIns.count { it.date >= monday && it.date < date }
            completedEarlierThisWeek < timesPerWeek
        }
    }

    fun toMap(): Map<String, Any> = mapOf(
        "kind" to kind.name,
        "weekdaysMask" to weekdaysMask,
        "timesPerWeek" to timesPerWeek,
    )

    companion object {
        fun daily() = HabitSchedule(HabitScheduleKind.DAILY)

        fun weekdays(days: Set<DayOfWeek>) = HabitSchedule(
            kind = HabitScheduleKind.WEEKDAYS,
            weekdaysMask = days.fold(0) { mask, day -> mask or (1 shl (day.value - 1)) },
        )

        fun timesPerWeek(count: Int) = HabitSchedule(HabitScheduleKind.TIMES_PER_WEEK, timesPerWeek = count)

        fun fromMap(map: Map<String, Any?>) = HabitSchedule(
            kind = HabitScheduleKind.valueOf(map.requiredString("kind")),
            weekdaysMask = map.requiredInt("weekdaysMask"),
            timesPerWeek = map.requiredInt("timesPerWeek"),
        )
    }
}

data class Habit(
    val id: String,
    val name: String,
    val schedule: HabitSchedule,
    val createdAtUtcMillis: Long,
    val archivedAtUtcMillis: Long? = null,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "name" to name,
        "schedule" to schedule.toMap(),
        "createdAtUtcMillis" to createdAtUtcMillis,
        "archivedAtUtcMillis" to archivedAtUtcMillis,
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): Habit {
            @Suppress("UNCHECKED_CAST")
            val scheduleMap = map["schedule"] as? Map<String, Any?>
                ?: throw IllegalArgumentException("schedule is required")
            return Habit(
                id = map.requiredString("id"),
                name = map.requiredString("name"),
                schedule = HabitSchedule.fromMap(scheduleMap),
                createdAtUtcMillis = map.requiredLong("createdAtUtcMillis"),
                archivedAtUtcMillis = map["archivedAtUtcMillis"]?.toLongValue(),
            )
        }
    }
}

data class HabitCheckIn(
    val id: String,
    val habitId: String,
    val dateEpochDay: Long,
    val createdAtUtcMillis: Long,
) {
    val date: LocalDate get() = LocalDate.ofEpochDay(dateEpochDay)

    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "habitId" to habitId,
        "dateEpochDay" to dateEpochDay,
        "createdAtUtcMillis" to createdAtUtcMillis,
    )

    companion object {
        fun fromMap(map: Map<String, Any?>) = HabitCheckIn(
            id = map.requiredString("id"),
            habitId = map.requiredString("habitId"),
            dateEpochDay = map.requiredLong("dateEpochDay"),
            createdAtUtcMillis = map.requiredLong("createdAtUtcMillis"),
        )
    }
}

enum class HabitDayStatus { DUE, DONE, NOT_DUE }

data class HabitHistoryDay(
    val dateEpochDay: Long,
    val status: HabitDayStatus,
    val isDue: Boolean,
    val isCheckedIn: Boolean,
) {
    val date: LocalDate get() = LocalDate.ofEpochDay(dateEpochDay)
}

data class HabitHistory(
    val habitId: String,
    val startDateEpochDay: Long,
    val endDateEpochDay: Long,
    val days: List<HabitHistoryDay>,
    val dueDays: Int,
    val completedDueDays: Int,
    val completionRate: Double,
    val currentStreak: Int,
) {
    val startDate: LocalDate get() = LocalDate.ofEpochDay(startDateEpochDay)
    val endDate: LocalDate get() = LocalDate.ofEpochDay(endDateEpochDay)
}

interface HabitRepository {
    fun list(): List<Habit>
    fun add(name: String, schedule: HabitSchedule): Habit
    fun checkIn(habitId: String, date: LocalDate): HabitCheckIn
    fun undoCheckIn(habitId: String, date: LocalDate): Boolean

    fun history(habitId: String, endDate: LocalDate, days: Int): HabitHistory
    fun checkIns(habitId: String): List<HabitCheckIn>
    fun strength(habitId: String, endDate: LocalDate): Double
    fun archive(habitId: String): Habit
    fun exportCsv(): String
    fun exportMaps(): List<Map<String, Any?>>
}

/** In-memory implementation for the offline demo; S3 can persist the returned maps. */
class InMemoryHabitRepository(
    private val clock: HabitClock = HabitClock(System::currentTimeMillis),
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : HabitRepository {
    private val habits = linkedMapOf<String, Habit>()
    private val checkInsByHabitAndDay = linkedMapOf<Pair<String, Long>, HabitCheckIn>()

    @Synchronized
    override fun list(): List<Habit> = habits.values.filter { it.archivedAtUtcMillis == null }

    @Synchronized
    override fun add(name: String, schedule: HabitSchedule): Habit {
        val normalizedName = name.trim()
        require(normalizedName.isNotEmpty()) { "Le nom de l'habitude est obligatoire." }
        val id = newId().also { require(it.isNotBlank() && it !in habits) { "ID d'habitude invalide ou déjà utilisé." } }
        return Habit(id, normalizedName, schedule, clock.nowUtcMillis()).also { habits[id] = it }
    }

    @Synchronized
    override fun checkIn(habitId: String, date: LocalDate): HabitCheckIn {
        val habit = habits[habitId] ?: throw IllegalArgumentException("Habitude inconnue : $habitId")
        require(habit.archivedAtUtcMillis == null) { "Cette habitude est archivée." }
        require(!date.isBefore(habit.createdAtUtcMillis.utcDate())) { "La date précède la création de l'habitude." }
        val key = habitId to date.toEpochDay()
        return checkInsByHabitAndDay.getOrPut(key) {
            HabitCheckIn(newId(), habitId, date.toEpochDay(), clock.nowUtcMillis())
        }
    }

    @Synchronized
    override fun undoCheckIn(habitId: String, date: LocalDate): Boolean {
        require(habitId in habits) { "Habitude inconnue : $habitId" }
        return checkInsByHabitAndDay.remove(habitId to date.toEpochDay()) != null
    }

    @Synchronized
    override fun history(habitId: String, endDate: LocalDate, days: Int): HabitHistory {
        val habit = habits[habitId] ?: throw IllegalArgumentException("Habitude inconnue : $habitId")
        require(days == 7 || days == 30) { "L'historique accepte 7 ou 30 jours." }
        val startDate = endDate.minusDays((days - 1).toLong())
        val createdDate = habit.createdAtUtcMillis.utcDate()
        val checkIns = checkInsByHabitAndDay.values.filter { it.habitId == habitId }
        val checkInsByDay = checkIns.associateBy { it.dateEpochDay }
        val historyDays = (0 until days).map { offset ->
            val date = startDate.plusDays(offset.toLong())
            val isDue = !date.isBefore(createdDate) && habit.schedule.isDueOn(date, checkIns)
            val isCheckedIn = date.toEpochDay() in checkInsByDay
            HabitHistoryDay(
                dateEpochDay = date.toEpochDay(),
                status = when {
                    isCheckedIn -> HabitDayStatus.DONE
                    isDue -> HabitDayStatus.DUE
                    else -> HabitDayStatus.NOT_DUE
                },
                isDue = isDue,
                isCheckedIn = isCheckedIn,
            )
        }
        val dueDays = historyDays.count { it.isDue }
        val completedDueDays = historyDays.count { it.isDue && it.isCheckedIn }
        var streak = 0
        if (!endDate.isBefore(createdDate)) {
            var cursor = endDate
            // An uncompleted due day at the requested end does not break the streak until it is over.
            if (habit.schedule.isDueOn(cursor, checkIns) && cursor.toEpochDay() !in checkInsByDay) {
                cursor = cursor.minusDays(1)
            }
            while (!cursor.isBefore(createdDate)) {
                val due = habit.schedule.isDueOn(cursor, checkIns)
                when {
                    !due -> cursor = cursor.minusDays(1)
                    cursor.toEpochDay() in checkInsByDay -> {
                        streak++
                        cursor = cursor.minusDays(1)
                    }
                    else -> break
                }
            }
        }
        return HabitHistory(
            habitId = habitId,
            startDateEpochDay = startDate.toEpochDay(),
            endDateEpochDay = endDate.toEpochDay(),
            days = historyDays,
            dueDays = dueDays,
            completedDueDays = completedDueDays,
            completionRate = if (dueDays == 0) 0.0 else completedDueDays.toDouble() / dueDays,
            currentStreak = streak,
        )
    }

    @Synchronized
    override fun checkIns(habitId: String): List<HabitCheckIn> {
        require(habitId in habits) { "Habitude inconnue : $habitId" }
        return checkInsByHabitAndDay.values.filter { it.habitId == habitId }.sortedBy { it.dateEpochDay }
    }

    @Synchronized
    override fun strength(habitId: String, endDate: LocalDate): Double {
        val habit = habits[habitId] ?: throw IllegalArgumentException("Habitude inconnue : $habitId")
        val checkIns = checkInsByHabitAndDay.values.filter { it.habitId == habitId }
        return HabitStrengthCalculator.currentStrength(habit, checkIns, endDate)
    }

    @Synchronized
    override fun archive(habitId: String): Habit {
        val habit = habits[habitId] ?: throw IllegalArgumentException("Habitude inconnue : $habitId")
        return habit.copy(archivedAtUtcMillis = habit.archivedAtUtcMillis ?: clock.nowUtcMillis())
            .also { habits[habitId] = it }
    }

    @Synchronized
    override fun exportCsv(): String = LoopHabitCsvExporter.export(
        habits = habits.values,
        checkIns = checkInsByHabitAndDay.values,
        today = LocalDate.now(ZoneOffset.UTC),
    )

    @Synchronized
    override fun exportMaps(): List<Map<String, Any?>> = buildList {
        habits.values.forEach { add(mapOf("recordType" to "habit", "habit" to it.toMap())) }
        checkInsByHabitAndDay.values.forEach { add(mapOf("recordType" to "checkin", "checkIn" to it.toMap())) }
    }

    /** Restores a snapshot produced by [exportMaps], replacing the current in-memory state. */
    @Synchronized
    fun restoreMaps(maps: List<Map<String, Any?>>) {
        val restoredHabits = linkedMapOf<String, Habit>()
        val restoredCheckIns = linkedMapOf<Pair<String, Long>, HabitCheckIn>()
        maps.forEach { record ->
            when (record["recordType"] as? String) {
                "habit" -> {
                    @Suppress("UNCHECKED_CAST")
                    val values = record["habit"] as? Map<String, Any?>
                        ?: throw IllegalArgumentException("habit is required")
                    val habit = Habit.fromMap(values)
                    require(restoredHabits.put(habit.id, habit) == null) { "Duplicate habit ID" }
                }
                "checkin" -> {
                    @Suppress("UNCHECKED_CAST")
                    val values = record["checkIn"] as? Map<String, Any?>
                        ?: throw IllegalArgumentException("checkIn is required")
                    val checkIn = HabitCheckIn.fromMap(values)
                    val key = checkIn.habitId to checkIn.dateEpochDay
                    require(checkIn.habitId in restoredHabits) { "Check-in references an unknown habit" }
                    require(restoredCheckIns.put(key, checkIn) == null) { "Duplicate habit check-in" }
                }
                else -> throw IllegalArgumentException("Unknown habit record type")
            }
        }
        habits.clear()
        habits.putAll(restoredHabits)
        checkInsByHabitAndDay.clear()
        checkInsByHabitAndDay.putAll(restoredCheckIns)
    }
}

/** Focus completion never calls this implicitly: the caller must confirm or configure pre-engagement. */
class HabitLinkPolicy(
    private val habits: HabitRepository,
    preEngagedHabitIds: Set<String> = emptySet(),
) {
    private val preEngaged = preEngagedHabitIds.toMutableSet()

    init {
        require(preEngaged.all { id -> habits.list().any { it.id == id } }) {
            "Un pré-engagement référence une habitude active inconnue."
        }
    }

    @Synchronized
    fun setPreEngagement(habitId: String, enabled: Boolean) {
        require(habits.list().any { it.id == habitId }) { "Habitude active inconnue : $habitId" }
        if (enabled) preEngaged += habitId else preEngaged -= habitId
    }

    @Synchronized
    fun preEngagedHabitIds(): Set<String> = preEngaged.toSet()

    /** This method itself is the explicit confirmation boundary. */
    fun confirmLink(sessionId: String, habitId: String, date: LocalDate): HabitCheckIn {
        require(sessionId.isNotBlank()) { "ID de session obligatoire." }
        return habits.checkIn(habitId, date)
    }

    /** S3 calls this only after a completed focus session; no pre-engagement means no check-in. */
    @Synchronized
    fun onSessionCompleted(sessionId: String, date: LocalDate): List<HabitCheckIn> {
        require(sessionId.isNotBlank()) { "ID de session obligatoire." }
        return preEngaged.sorted().map { habits.checkIn(it, date) }
    }

    fun toMap(): Map<String, Any> = mapOf("preEngagedHabitIds" to preEngagedHabitIds().sorted())

    companion object {
        fun fromMap(habits: HabitRepository, map: Map<String, Any?>): HabitLinkPolicy {
            val ids = (map["preEngagedHabitIds"] as? List<*>)
                ?.map { it as? String ?: throw IllegalArgumentException("ID de pré-engagement invalide.") }
                ?.toSet().orEmpty()
            return HabitLinkPolicy(habits, ids)
        }
    }
}

private fun Map<String, Any?>.requiredString(key: String): String =
    this[key] as? String ?: throw IllegalArgumentException("$key must be a string")

private fun Map<String, Any?>.requiredInt(key: String): Int =
    (this[key] as? Number)?.toInt() ?: throw IllegalArgumentException("$key must be a number")

private fun Map<String, Any?>.requiredLong(key: String): Long =
    (this[key] as? Number)?.toLong() ?: throw IllegalArgumentException("$key must be a number")

private fun Any.toLongValue(): Long = (this as? Number)?.toLong()
    ?: throw IllegalArgumentException("Expected a numeric timestamp")

private fun Long.utcDate(): LocalDate = java.time.Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
