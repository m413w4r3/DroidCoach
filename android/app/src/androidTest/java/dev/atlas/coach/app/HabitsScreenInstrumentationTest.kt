package dev.atlas.coach.app

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.app.ui.AtlasTheme
import dev.atlas.coach.habits.HabitDayStatus
import dev.atlas.coach.habits.HabitHistory
import dev.atlas.coach.habits.HabitHistoryDay
import dev.atlas.coach.habits.HabitSchedule
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HabitsScreenInstrumentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun newController(): AtlasController {
        context.getSharedPreferences("atlas_local_state", Context.MODE_PRIVATE).edit().clear().commit()
        return AtlasController(context)
    }

    private fun show(controller: AtlasController, fontScale: Float = 1f) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AtlasTheme {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        HabitsScreen(controller, controller.revision, onExport = {})
                    }
                }
            }
        }
    }

    private fun addHabit(controller: AtlasController, name: String): String {
        controller.addHabit(name, HabitSchedule.daily())
        return requireNotNull(controller.activeHabits.firstOrNull { it.name == name }).id
    }

    @Test
    fun emptyListShowsAddAction() {
        val controller = newController()
        show(controller)

        composeRule.onNodeWithText("Aucune habitude pour le moment.").assertIsDisplayed()
        composeRule.onNodeWithTag("habits_add").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun fiveHabitsShowListAndDailyProgress() {
        val controller = newController()
        repeat(5) { addHabit(controller, "Habitude ${it + 1}") }
        show(controller)

        composeRule.onNodeWithText("0 sur 5 validées aujourd'hui").assertIsDisplayed()
        (1..5).forEach { index ->
            composeRule.onNodeWithText("Habitude $index").performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun checkInCanBeToggledAndUndone() {
        val controller = newController()
        val habitId = addHabit(controller, "Lire")
        show(controller)

        composeRule.onNodeWithContentDescription("Valider Lire aujourd'hui").performScrollTo().performClick()
        assertTrue("check-in should be recorded", "${habitId}" in controller.checkedHabitIds)
        composeRule.onNodeWithContentDescription("Annuler la validation de Lire aujourd'hui").performScrollTo().performClick()
        assertTrue("check-in should be undone", habitId !in controller.checkedHabitIds)
    }

    @Test
    fun detailStartsWithSevenDaysAndCanShowThirty() {
        val controller = newController()
        val habitId = addHabit(controller, "Marcher")
        show(controller)

        composeRule.onNodeWithTag("habit_open_$habitId").performScrollTo().performClick()
        composeRule.onNodeWithText("Historique · 7 jours").assertIsDisplayed()
        composeRule.onNodeWithText("30 jours").performClick()
        composeRule.onNodeWithText("Historique · 30 jours").assertIsDisplayed()
    }

    @Test
    fun missedAndNotPlannedDaysHaveDifferentAccessibleLabels() {
        val today = LocalDate.now()
        val history = HabitHistory(
            habitId = "grid-fixture",
            startDateEpochDay = today.minusDays(6).toEpochDay(),
            endDateEpochDay = today.toEpochDay(),
            days = listOf(
                gridDay(today.minusDays(6), due = true, checked = true),
                gridDay(today.minusDays(5), due = true),
                gridDay(today.minusDays(4), due = false),
                gridDay(today.minusDays(3), due = true, checked = true),
                gridDay(today.minusDays(2), due = true),
                gridDay(today.minusDays(1), due = false),
                gridDay(today, due = true, checked = true),
            ),
            dueDays = 5,
            completedDueDays = 3,
            completionRate = 0.6,
            currentStreak = 1,
            bestStreak = 2,
            notDueDays = 2,
        )
        composeRule.setContent {
            AtlasTheme {
                HabitHistoryGrid(
                    history = history,
                    today = today,
                    createdDate = today.minusDays(10),
                )
            }
        }

        composeRule.onNodeWithContentDescription("${today.minusDays(2)}, dû et manqué")
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("${today.minusDays(1)}, non planifié")
            .assertIsDisplayed()
        composeRule.onNodeWithText("! Dû et manqué").assertIsDisplayed()
        composeRule.onNodeWithText("– Non planifié").assertIsDisplayed()
    }

    private fun gridDay(date: LocalDate, due: Boolean, checked: Boolean = false) = HabitHistoryDay(
        dateEpochDay = date.toEpochDay(),
        status = when {
            checked -> HabitDayStatus.DONE
            due -> HabitDayStatus.DUE
            else -> HabitDayStatus.NOT_DUE
        },
        isDue = due,
        isCheckedIn = checked,
    )

    @Test
    fun dailyEditorNeedsOnlyNameAndSave() {
        val controller = newController()
        show(controller)

        composeRule.onNodeWithTag("habits_add").performClick()
        composeRule.onNodeWithTag("habit_name").assertIsDisplayed().performTextInput("Lire")
        composeRule.onNodeWithTag("habit_save").performClick()
        composeRule.onNodeWithText("Lire").performScrollTo().assertIsDisplayed()
        assertEquals(1, controller.activeHabits.size)
    }

    @Test
    fun archiveRequiresConfirmationAndRemovesHabitFromActiveList() {
        val controller = newController()
        val habitId = addHabit(controller, "Étirements")
        show(controller)

        composeRule.onNodeWithTag("habit_open_$habitId").performScrollTo().performClick()
        composeRule.onNodeWithTag("habit_archive").performScrollTo().performClick()
        composeRule.onNodeWithText("Archiver Étirements ?").assertIsDisplayed()
        composeRule.onNodeWithText("Son historique local sera conservé.", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("habit_archive_confirm").performClick()
        assertTrue(controller.activeHabits.none { it.id == habitId })
        composeRule.onNodeWithText("Aucune habitude pour le moment.").assertIsDisplayed()
    }

    @Test
    fun checkInAndAddStayReachableAtFontScaleTwo() {
        val controller = newController()
        val habitId = addHabit(controller, "Boire de l'eau")
        show(controller, fontScale = 2f)

        composeRule.onNodeWithTag("habit_check_$habitId").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        composeRule.onNodeWithTag("habits_add").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        composeRule.onNodeWithTag("habit_name").performScrollTo().performTextInput("Respirer")
        composeRule.onNodeWithTag("habit_save").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(2, controller.activeHabits.size)
        composeRule.onAllNodesWithText("Respirer").assertCountEquals(1)
    }
}
