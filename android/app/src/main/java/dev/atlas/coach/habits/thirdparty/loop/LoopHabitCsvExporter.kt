/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */
// Adapted from uhabits@c06b682f6b7b9c09eb278a06ffb57a42f0cddd38:
// uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/io/HabitsCSVExporter.kt, GPL-3.0-or-later.
// Writes into ATLAS's local repository format; no Loop database, ZIP library or Android storage is used.
package dev.atlas.coach.habits.thirdparty.loop

import dev.atlas.coach.habits.Habit
import dev.atlas.coach.habits.HabitCheckIn
import java.time.LocalDate
import java.util.Locale

internal object LoopHabitCsvExporter {
    private val header = listOf(
        "record_type", "id", "name", "schedule", "habit_id", "date_epoch_day",
        "created_at_utc_ms", "archived_at_utc_ms", "strength_score",
    )

    fun export(habits: Collection<Habit>, checkIns: Collection<HabitCheckIn>, today: LocalDate): String = buildString {
        append(loopCsvLine(header))
        val checkInsByHabit = checkIns.groupBy { it.habitId }
        for (habit in habits) {
            val records = checkInsByHabit[habit.id].orEmpty()
            val scores = HabitStrengthCalculator.recompute(habit, records, today)
            val currentStrength = scores.lastOrNull()?.value ?: 0.0
            append(loopCsvLine(listOf(
                "habit", habit.id, habit.name, habit.schedule.toString(), "", "",
                habit.createdAtUtcMillis.toString(), habit.archivedAtUtcMillis?.toString().orEmpty(),
                currentStrength.toCsvScore(),
            )))
            records.sortedBy { it.dateEpochDay }.forEach { checkIn ->
                append(loopCsvLine(listOf(
                    "checkin", checkIn.id, "", "", checkIn.habitId, checkIn.dateEpochDay.toString(),
                    checkIn.createdAtUtcMillis.toString(), "", "",
                )))
            }
            scores.forEach { score ->
                append(loopCsvLine(listOf(
                    "score", habit.id, habit.name, habit.schedule.toString(), habit.id,
                    score.date.toEpochDay().toString(), "", "", score.value.toCsvScore(),
                )))
            }
        }
    }

    private fun Double.toCsvScore(): String = String.format(Locale.ROOT, "%.4f", this)
}
