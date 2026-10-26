package dev.atlas.coach.app

import dev.atlas.coach.training.PlannedExercise
import dev.atlas.coach.training.SportPlan
import dev.atlas.coach.training.SportPlanFixture
import dev.atlas.coach.training.SportPlanJson
import dev.atlas.coach.training.SportPlanParseResult
import dev.atlas.coach.training.SportPlanSource
import dev.atlas.coach.training.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SportPresentationTest {
    @Test
    fun fixtureIsValidAndContainsOnlyAPrescription() {
        val parsed = SportPlanJson.parse(SportPlanFixture.json)
        assertTrue(parsed is SportPlanParseResult.Valid)
        val plan = (parsed as SportPlanParseResult.Valid).plan
        assertTrue(plan.planned)
        assertTrue(plan.exercises.isNotEmpty())
        assertTrue(plan.performed.isEmpty())
    }

    @Test
    fun prescriptionFormatsSetsRepsWeightAndRest() {
        val exercise = PlannedExercise(
            exerciseId = "56",
            name = "Squat",
            sets = 3,
            reps = 8,
            weightKg = 40.0,
            restSeconds = 90,
            lastPerformance = null,
        )
        assertEquals("3 séries × 8 répétitions · 40 kg · repos 90 s", formatPrescription(exercise))
    }

    @Test
    fun prescriptionOmitsMissingOptionalValues() {
        val exercise = PlannedExercise(
            exerciseId = "56",
            name = null,
            sets = 2,
            reps = 12,
            weightKg = null,
            restSeconds = null,
            lastPerformance = null,
        )
        assertEquals("2 séries × 12 répétitions", formatPrescription(exercise))
    }

    @Test
    fun weightFormattingKeepsOneDecimalOnlyWhenNeeded() {
        assertEquals("40", formatWeight(40.0))
        assertEquals("42.5", formatWeight(42.5))
    }

    @Test
    fun syncAndSourceLabelsAreHumanReadable() {
        assertEquals("à jour", syncLabel(SyncState.FRESH))
        assertEquals("périmée", syncLabel(SyncState.STALE))
        assertEquals("indisponible", syncLabel(SyncState.UNAVAILABLE))
        assertEquals("incohérente", syncLabel(SyncState.MISMATCH))
        val fixturePlan = SportPlan(
            schemaVersion = 1,
            localDate = "2026-10-26",
            timezone = "Europe/Paris",
            source = SportPlanSource.FIXTURE,
            syncState = SyncState.FRESH,
            routineId = null,
            dayId = null,
            dayName = null,
            planned = false,
            exercises = emptyList(),
            performed = emptyList(),
            generatedAt = null,
            limits = dev.atlas.coach.training.SportPlanLimits(maxExercises = 50, truncated = false),
        )
        assertEquals("fixture de démonstration", sourceLabel(fixturePlan))
    }
}
