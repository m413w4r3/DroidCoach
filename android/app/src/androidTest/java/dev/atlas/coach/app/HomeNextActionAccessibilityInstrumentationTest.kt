package dev.atlas.coach.app

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.atlas.coach.app.ui.AtlasTheme
import dev.atlas.coach.plan.ActivityKind
import dev.atlas.coach.plan.ActivityStatus
import dev.atlas.coach.plan.LocalSchedule
import dev.atlas.coach.plan.PlanActivity
import dev.atlas.coach.plan.Priority
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeNextActionAccessibilityInstrumentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun selectedCardExposesOneCompleteAccessibilityDescription() {
        val activity = PlanActivity(
            activityId = "00000000-0000-0000-0000-000000000040",
            planId = "00000000-0000-0000-0000-000000000001",
            kind = ActivityKind.FOCUS,
            title = "Préparer le dossier",
            status = ActivityStatus.PLANNED,
            scheduledLocal = LocalSchedule(
                localDate = "2026-10-26",
                startMinute = 9 * 60 + 30,
                durationMinutes = 1,
                timezone = "Europe/Paris",
            ),
            priority = Priority.REQUIRED,
            goalId = "00000000-0000-0000-0000-000000000099",
            createdRevisionId = "00000000-0000-0000-0000-000000000002",
            lastRevisionId = "00000000-0000-0000-0000-000000000002",
        )

        composeRule.setContent {
            AtlasTheme {
                HomeNextActionCard(
                    state = HomeNextActionState.Selected(activity),
                    goalTitle = "Dossier final",
                )
            }
        }

        composeRule.onAllNodesWithContentDescription(
            "Prochaine action : Préparer le dossier. Prévue, 09:30 – 09:31, 1 minute, priorité requise, objectif Dossier final. Actions : Démarrer, Reporter.",
        ).assertCountEquals(1)
        composeRule.onNodeWithTag("home_next_action_card").assertIsDisplayed()
        composeRule.onNodeWithText("Démarrer").assertIsDisplayed()
        composeRule.onNodeWithText("Reporter").assertIsDisplayed()
    }

    @Test
    fun nonePlannedCardExposesOneCompleteAccessibilityDescription() {
        composeRule.setContent {
            AtlasTheme {
                HomeNextActionCard(
                    state = HomeNextActionState.NonePlanned,
                    goalTitle = null,
                )
            }
        }

        composeRule.onAllNodesWithContentDescription("Aucune action planifiée.").assertCountEquals(1)
        composeRule.onNodeWithTag("home_next_action_card").assertIsDisplayed()
    }
}
