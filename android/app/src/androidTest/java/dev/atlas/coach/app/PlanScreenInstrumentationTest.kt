package dev.atlas.coach.app

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.app.ui.AtlasRoutes
import dev.atlas.coach.app.ui.AtlasScreenFrame
import dev.atlas.coach.app.ui.AtlasTheme
import dev.atlas.coach.plan.ActivityKind
import dev.atlas.coach.plan.ActivityStatus
import dev.atlas.coach.plan.DayPlan
import dev.atlas.coach.plan.LocalSchedule
import dev.atlas.coach.plan.OverlapWarning
import dev.atlas.coach.plan.PlanActivity
import dev.atlas.coach.plan.PlanChange
import dev.atlas.coach.plan.PlanClock
import dev.atlas.coach.plan.PlanOrigin
import dev.atlas.coach.plan.PlanPreview
import dev.atlas.coach.plan.PlanRevision
import dev.atlas.coach.plan.PlanState
import dev.atlas.coach.plan.Priority
import dev.atlas.coach.plan.RevisionOrigin
import dev.atlas.coach.plan.RevisionStatus
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PlanScreenInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun missingPlanShowsItsSinglePrimaryAction() {
        val context = isolatedContext()
        val controller = PlanController(File(context.cacheDir, "plan-ui-empty-${UUID.randomUUID()}"))

        setPlanScreen(controller, context)

        composeRule.onNodeWithText("Créer le programme du jour").assertIsDisplayed()
        composeRule.onAllNodesWithText("Ajouter une activité").assertCountEquals(0)
    }

    @Test
    fun selectingTomorrowShowsItsSeparateEmptyPlanAction() {
        val context = isolatedContext()
        val controller = PlanController(File(context.cacheDir, "plan-ui-tomorrow-${UUID.randomUUID()}"))

        setPlanScreen(controller, context)

        composeRule.onNodeWithTag("plan_day_tomorrow").performClick()
        composeRule.onNodeWithText("Créer le programme de demain").assertIsDisplayed()
        composeRule.onAllNodesWithText("Créer le programme du jour").assertCountEquals(0)
    }

    @Test
    fun tomorrowMorningActivitiesAreNotMarkedPastWhenTodayIsLate() {
        val context = isolatedContext()
        val directory = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "plan-ui-tomorrow-activity-${UUID.randomUUID()}",
        )
        assertTrue(directory.mkdirs() || directory.isDirectory)
        val today = LocalDate.of(2027, 1, 15)
        val tomorrow = today.plusDays(1)
        val fixedNow = 1_800_000_000_000L
        val zone = ZoneId.of("Europe/Paris")
        val tomorrowController = PlanController(
            directory = directory,
            clock = PlanClock { fixedNow },
            zoneIdProvider = { zone },
            todayProvider = { tomorrow },
        )
        assertTrue(tomorrowController.createTodayPlan())
        assertTrue(
            tomorrowController.addActivity(
                title = "Réviser le chapitre",
                kind = ActivityKind.FOCUS,
                startMinute = 9 * 60,
                durationMinutes = 45,
                priority = Priority.IMPORTANT,
            ),
        )
        assertTrue(tomorrowController.confirmPreview())

        val todayController = PlanController(
            directory = directory,
            clock = PlanClock { fixedNow },
            zoneIdProvider = { zone },
            todayProvider = { today },
        )
        setPlanScreen(todayController, context)

        composeRule.onNodeWithTag("plan_day_tomorrow").performClick()
        composeRule.onNodeWithTag("plan_next_activity_card").assertIsDisplayed()
        composeRule.onAllNodesWithText("Réviser le chapitre").assertCountEquals(2)
        composeRule.onAllNodesWithText("Passée").assertCountEquals(0)
    }

    @Test
    fun threeActivitiesRenderChronologicallyAndHighlightTheNextOne() {
        val activities = listOf(
            activity(ID_3, "Étirements", 18 * 60 + 30, duration = 30),
            activity(ID_1, "Lecture", 10 * 60, duration = 45),
            activity(ID_2, "Marche", 16 * 60 + 30, duration = 30),
        )

        composeRule.setContent {
            AtlasTheme {
                PlanActivityList(
                    activities = activities,
                    nowMinute = 17 * 60,
                    onMove = {},
                    onEdit = {},
                    onStatus = {},
                    onRemove = {},
                )
            }
        }

        val firstTop = composeRule.onNodeWithTag("plan_activity_row_$ID_1").fetchSemanticsNode().boundsInRoot.top
        val secondTop = composeRule.onNodeWithTag("plan_activity_row_$ID_2").fetchSemanticsNode().boundsInRoot.top
        val thirdTop = composeRule.onNodeWithTag("plan_activity_row_$ID_3").fetchSemanticsNode().boundsInRoot.top
        assertTrue(firstTop < secondTop && secondTop < thirdTop)
        composeRule.onNodeWithText("Prochaine").assertIsDisplayed()
        composeRule.onNodeWithText("À venir · 18:30 – 19:00 · 30 min").assertIsDisplayed()
    }

    @Test
    fun overflowListsManagementActionsAndRequiresExplicitDeleteConfirmation() {
        val target = activity(ID_1, "Marche", 16 * 60 + 30)

        composeRule.setContent {
            AtlasTheme {
                var showConfirmation by remember { mutableStateOf(false) }
                var confirmed by remember { mutableStateOf(false) }
                Column {
                    PlanActivityRow(
                        activity = target,
                        timing = PlanActivityTiming.UPCOMING,
                        isNext = true,
                        onMove = {},
                        onEdit = {},
                        onStatus = {},
                        onRemove = { showConfirmation = true },
                    )
                    if (showConfirmation) {
                        PlanRemoveConfirmationDialog(
                            activity = target,
                            onDismiss = { showConfirmation = false },
                            onConfirm = {
                                confirmed = true
                                showConfirmation = false
                            },
                        )
                    }
                    if (confirmed) androidx.compose.material3.Text("Suppression confirmée")
                }
            }
        }

        composeRule.onNodeWithContentDescription("Plus d’actions pour Marche").performClick()
        composeRule.onNodeWithText("Déplacer").assertIsDisplayed()
        composeRule.onNodeWithText("Modifier").assertIsDisplayed()
        composeRule.onNodeWithText("Changer le statut").assertIsDisplayed()
        composeRule.onNodeWithText("Supprimer l’activité").performClick()
        composeRule.onNodeWithText("Supprimer cette activité ?").assertIsDisplayed()
        composeRule.onNodeWithText("Supprimer l’activité").performClick()
        composeRule.onNodeWithText("Suppression confirmée").assertIsDisplayed()
    }

    @Test
    fun primaryActionRemainsVisibleAtDoubleFontScale() {
        val context = isolatedContext()
        val controller = PlanController(File(context.cacheDir, "plan-ui-font-${UUID.randomUUID()}"))

        composeRule.setContent {
            val originalDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides context,
                LocalDensity provides Density(originalDensity.density, 2f),
            ) {
                AtlasTheme {
                    AtlasScreenFrame(
                        title = "Plan du jour",
                        activeDestination = AtlasRoutes.HOME,
                        canGoBack = false,
                        emergencyPaused = false,
                        onBack = {},
                        onEmergencyPause = {},
                        onNavigate = {},
                    ) {
                        PlanScreen(controller)
                    }
                }
            }
        }

        composeRule.onNodeWithText("Créer le programme du jour").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun coachPreviewListsEveryChangeVersionEffectsSummaryAndWarnings() {
        composeRule.setContent {
            AtlasTheme {
                RevisionPreviewDialog(
                    preview = fullChangePreview(),
                    onCancel = {},
                    onConfirm = {},
                )
            }
        }

        composeRule.onNodeWithText("Réorganisation proposée", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Version 1 → 2 · 6 effet(s) local(aux)").assertIsDisplayed()
        // The dialog body is scrollable; every effect must exist even when below the fold.
        composeRule.onAllNodesWithText("Ajouter « Marche » à 18:30", substring = true).assertCountEquals(1)
        composeRule.onAllNodesWithText("Supprimer « Ancien créneau »", substring = true).assertCountEquals(1)
        composeRule.onAllNodesWithText("Déplacer de 10:00 à 10:30", substring = true).assertCountEquals(1)
        composeRule.onAllNodesWithText("Modifier « Lecture active »", substring = true).assertCountEquals(1)
        composeRule.onAllNodesWithText("Statut : Prévue → Terminée", substring = true).assertCountEquals(1)
        composeRule.onAllNodesWithText("2 chevauchement(s) signalé(s)", substring = true).assertCountEquals(1)
    }

    private fun setPlanScreen(controller: PlanController, context: Context) {
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                AtlasTheme {
                    AtlasScreenFrame(
                        title = "Plan du jour",
                        activeDestination = AtlasRoutes.HOME,
                        canGoBack = false,
                        emergencyPaused = false,
                        onBack = {},
                        onEmergencyPause = {},
                        onNavigate = {},
                    ) {
                        PlanScreen(controller)
                    }
                }
            }
        }
    }

    private fun isolatedContext(): Context {
        val testPackageContext = InstrumentationRegistry.getInstrumentation().context
        return object : ContextWrapper(testPackageContext) {
            override fun getApplicationContext(): Context = this
        }
    }

    private fun fullChangePreview(): PlanPreview {
        val beforeActivity = activity(ID_1, "Lecture", 10 * 60)
        val removedActivity = activity(ID_2, "Ancien créneau", 11 * 60)
        val addedActivity = activity(ID_3, "Marche", 18 * 60 + 30)
        val editedActivity = beforeActivity.copy(title = "Lecture active")
        val movedTo = beforeActivity.scheduledLocal.copy(startMinute = 10 * 60 + 30)
        val changes = listOf(
            PlanChange.SlotAdded(addedActivity),
            PlanChange.SlotRemoved(ID_2, removedActivity),
            PlanChange.SlotMoved(ID_1, beforeActivity.scheduledLocal, movedTo),
            PlanChange.SlotEdited(ID_1, beforeActivity, editedActivity),
            PlanChange.StatusChanged(ID_1, ActivityStatus.PLANNED, ActivityStatus.DONE),
        )
        val before = dayPlan(version = 1, revisionId = REVISION_ID, activities = listOf(beforeActivity, removedActivity), updatedAt = 1L)
        val after = dayPlan(version = 2, revisionId = NEXT_REVISION_ID, activities = listOf(editedActivity, addedActivity), updatedAt = 2L)
        val revision = PlanRevision(
            revisionId = NEXT_REVISION_ID,
            planId = PLAN_ID,
            baseVersion = 1,
            newVersion = 2,
            origin = RevisionOrigin.AGENT,
            changes = changes,
            summary = "Réorganisation proposée : créneaux ajustés.",
            createdAt = 1L,
            expiresAt = 10_000L,
            status = RevisionStatus.PROPOSED,
        )
        return PlanPreview(
            before = before,
            after = after,
            revision = revision,
            effectIds = listOf("E1", "E2", "E3", "E4", "E5", "E6"),
            scopeHash = "preview-hash",
            overlapWarnings = listOf(
                OverlapWarning(ID_1, ID_2, java.time.Instant.ofEpochMilli(1L), java.time.Instant.ofEpochMilli(2L)),
                OverlapWarning(ID_1, ID_3, java.time.Instant.ofEpochMilli(2L), java.time.Instant.ofEpochMilli(3L)),
            ),
        )
    }

    private fun dayPlan(version: Int, revisionId: String, activities: List<PlanActivity>, updatedAt: Long) = DayPlan(
        planId = PLAN_ID,
        localDate = DATE,
        timezone = "Europe/Paris",
        version = version,
        currentRevisionId = revisionId,
        state = PlanState.APPROVED,
        activities = activities,
        createdAt = 1L,
        updatedAt = updatedAt,
        approvedAt = 1L,
        origin = PlanOrigin.LOCAL,
    )

    private fun activity(id: String, title: String, start: Int, duration: Int = 30) = PlanActivity(
        activityId = id,
        planId = PLAN_ID,
        kind = ActivityKind.FOCUS,
        title = title,
        status = ActivityStatus.PLANNED,
        scheduledLocal = LocalSchedule(DATE, start, duration, "Europe/Paris"),
        priority = Priority.IMPORTANT,
        createdRevisionId = REVISION_ID,
        lastRevisionId = REVISION_ID,
    )

    private companion object {
        const val DATE = "2026-10-26"
        const val PLAN_ID = "00000000-0000-0000-0000-000000000001"
        const val REVISION_ID = "00000000-0000-0000-0000-000000000002"
        const val NEXT_REVISION_ID = "00000000-0000-0000-0000-000000000003"
        const val ID_1 = "00000000-0000-0000-0000-000000000011"
        const val ID_2 = "00000000-0000-0000-0000-000000000012"
        const val ID_3 = "00000000-0000-0000-0000-000000000013"
    }
}
