package dev.atlas.coach.app

import dev.atlas.coach.plan.ActivityKind
import dev.atlas.coach.plan.PlanClock
import dev.atlas.coach.plan.Priority
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlanControllerDateTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val zone = ZoneId.of("Europe/Paris")
    private val dayOne = LocalDate.of(2027, 1, 15)
    private val dayTwo = dayOne.plusDays(1)
    private val fixedNow = 1_800_000_000_000L

    @Test
    fun dateControllersCreateConfirmAndReloadIndependentPlans() {
        var currentDay = dayOne
        val directory = temporaryFolder.root
        val original = PlanController(
            directory = directory,
            clock = PlanClock { fixedNow },
            zoneIdProvider = { zone },
            todayProvider = { currentDay },
        )

        assertTrue(original.createTodayPlan())
        addConfirmedActivity(original, "Aujourd’hui", 9 * 60)
        val originalPlan = original.plan
        assertEquals(dayOne.toString(), original.today)

        val tomorrow = original.forDate(dayTwo)
        assertEquals(dayTwo.toString(), tomorrow.today)
        assertNull(tomorrow.plan)
        assertTrue(tomorrow.createTodayPlan())
        addConfirmedActivity(tomorrow, "Demain", 10 * 60)

        assertEquals(dayOne.toString(), original.today)
        assertEquals(originalPlan, original.plan)
        assertEquals(dayOne.toString(), original.plan?.localDate)
        assertEquals("Aujourd’hui", original.plan?.activities?.single()?.title)
        assertEquals(dayTwo.toString(), tomorrow.plan?.localDate)
        assertEquals("Demain", tomorrow.plan?.activities?.single()?.title)
        assertTrue(File(directory, "day-plan-$dayOne.json").isFile)
        assertTrue(File(directory, "day-plan-$dayTwo.json").isFile)

        val reloadedToday = PlanController(
            directory = directory,
            clock = PlanClock { fixedNow },
            zoneIdProvider = { zone },
            todayProvider = { dayOne },
        )
        val reloadedTomorrow = PlanController(
            directory = directory,
            clock = PlanClock { fixedNow },
            zoneIdProvider = { zone },
            todayProvider = { dayTwo },
        )

        assertEquals(dayOne.toString(), reloadedToday.plan?.localDate)
        assertEquals("Aujourd’hui", reloadedToday.plan?.activities?.single()?.title)
        assertEquals(dayTwo.toString(), reloadedTomorrow.plan?.localDate)
        assertEquals("Demain", reloadedTomorrow.plan?.activities?.single()?.title)
        assertFalse(reloadedToday.plan?.planId == reloadedTomorrow.plan?.planId)
        assertEquals(dayOne.toString(), original.today)
        assertEquals(originalPlan, original.plan)
        currentDay = dayTwo.plusDays(1)
        tomorrow.refresh()
        assertEquals(dayTwo.plusDays(1).toString(), original.today)
        assertEquals(dayTwo.toString(), tomorrow.today)
        assertEquals(dayTwo.toString(), tomorrow.plan?.localDate)
        assertEquals(originalPlan, original.plan)
    }

    private fun addConfirmedActivity(controller: PlanController, title: String, startMinute: Int) {
        assertTrue(
            controller.addActivity(
                title = title,
                kind = ActivityKind.FOCUS,
                startMinute = startMinute,
                durationMinutes = 30,
                priority = Priority.IMPORTANT,
            ),
        )
        assertTrue(controller.confirmPreview())
    }
}
