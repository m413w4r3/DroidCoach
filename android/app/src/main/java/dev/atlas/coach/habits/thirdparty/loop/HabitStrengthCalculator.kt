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
// uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/models/ScoreList.kt, GPL-3.0-or-later.
// Loop's proprietary LocalDate/EntryList storage is replaced with ATLAS LocalDate and check-in records.
package dev.atlas.coach.habits.thirdparty.loop

import dev.atlas.coach.habits.Habit
import dev.atlas.coach.habits.HabitCheckIn
import dev.atlas.coach.habits.HabitSchedule
import dev.atlas.coach.habits.HabitScheduleKind
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.ArrayDeque
import kotlin.math.min

object HabitStrengthCalculator {
    private const val SKIP = 3
    private const val YES_MANUAL = 2
    private const val NO = 0

    fun recompute(habit: Habit, checkIns: Collection<HabitCheckIn>, through: LocalDate): List<Score> {
        val from = habit.createdAtUtcMillis.utcDate()
        if (from.isAfter(through)) return emptyList()

        val frequency = habit.schedule.toLoopFrequency()
        var numerator = frequency.numerator
        var denominator = frequency.denominator
        val freq = frequency.toDouble()

        // Loop doubles the rolling window for boolean habits below daily frequency.
        if (freq < 1.0) {
            numerator *= 2
            denominator *= 2
        }

        val checkedDays = checkIns.asSequence().map { it.dateEpochDay }.toHashSet()
        val window = ArrayDeque<Int>()
        var rollingSum = 0.0
        var previousScore = 0.0
        val result = ArrayList<Score>()
        val days = ChronoUnit.DAYS.between(from, through).toInt()
        for (offset in 0..days) {
            val date = from.plusDays(offset.toLong())
            val value = when {
                !habit.schedule.isDueOn(date, checkIns) -> SKIP
                date.toEpochDay() in checkedDays -> YES_MANUAL
                else -> NO
            }
            if (value != SKIP) {
                window.addLast(value)
                if (window.size > denominator) {
                    val oldest = window.removeFirst()
                    if (oldest == YES_MANUAL) rollingSum -= 1.0
                }
                if (value == YES_MANUAL) rollingSum += 1.0
                val percentageCompleted = min(1.0, rollingSum / numerator)
                previousScore = Score.compute(freq, previousScore, percentageCompleted)
            }
            result.add(Score(date, previousScore.coerceIn(0.0, 1.0)))
        }
        return result
    }

    fun currentStrength(habit: Habit, checkIns: Collection<HabitCheckIn>, through: LocalDate): Double =
        recompute(habit, checkIns, through).lastOrNull()?.value ?: 0.0

    private fun HabitSchedule.toLoopFrequency(): Frequency = when (kind) {
        HabitScheduleKind.DAILY -> Frequency.DAILY
        HabitScheduleKind.WEEKDAYS -> Frequency(Integer.bitCount(weekdaysMask), 7)
        HabitScheduleKind.TIMES_PER_WEEK -> Frequency(timesPerWeek, 7)
    }

    private fun Long.utcDate(): LocalDate =
        java.time.Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
}
