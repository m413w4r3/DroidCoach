package dev.atlas.coach.app

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
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
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import dev.atlas.coach.plan.PlanStatusEvent
import dev.atlas.coach.plan.PlanState
import dev.atlas.coach.plan.Priority
import dev.atlas.coach.plan.RevisionOrigin
import dev.atlas.coach.plan.RevisionStatus
import dev.atlas.coach.plan.StatusProvenance
import dev.atlas.coach.plan.StatusSource
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
    fun activeRowsExposeOnePrimaryActionAndTerminalRowsHaveNoActions() {
        val planned = activity(ID_1, "Marche", 16 * 60 + 30)
        val done = activity(ID_2, "Lecture terminée", 17 * 60).copy(status = ActivityStatus.DONE)

        composeRule.setContent {
            AtlasTheme {
                Column {
                    PlanActivityRow(
                        activity = planned,
                        timing = PlanActivityTiming.UPCOMING,
                        isNext = true,
                        onMove = {}, onEdit = {}, onRemove = {}, onPrimary = {}, onReport = {},
                    )
                    PlanActivityRow(
                        activity = done,
                        timing = PlanActivityTiming.PAST,
                        isNext = false,
                        onMove = {}, onEdit = {}, onRemove = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag("plan_activity_primary_$ID_1").assertIsDisplayed()
        composeRule.onNodeWithTag("plan_activity_report_$ID_1").assertIsDisplayed()
        composeRule.onAllNodesWithTag("plan_activity_primary_$ID_2").assertCountEquals(0)
        composeRule.onAllNodesWithTag("plan_activity_report_$ID_2").assertCountEquals(0)
        composeRule.onNodeWithContentDescription(
            "16:30 – 17:00. Marche. État : Prévue. Origine du statut inconnue. " +
                "Actions : Démarrer, Reporter, Plus d’actions.",
        )
            .assertIsDisplayed()
    }

    @Test
    fun activityRowShowsStatusProvenanceAndSourceInFrench() {
        val done = activity(ID_1, "Marche", 16 * 60 + 30).copy(status = ActivityStatus.DONE)
        val event = PlanStatusEvent(
            statusEventId = ID_3,
            planId = PLAN_ID,
            activityId = ID_1,
            continuityId = null,
            fromStatus = ActivityStatus.IN_PROGRESS,
            toStatus = ActivityStatus.DONE,
            provenance = StatusProvenance.CONFIRMED,
            source = StatusSource.USER_UI,
            decidedAt = "2026-10-26T17:00:00Z",
            observedAt = "2026-10-26T17:00:00Z",
            planVersion = 4,
            consentVersion = 0,
        )

        composeRule.setContent {
            AtlasTheme {
                PlanActivityRow(
                    activity = done,
                    statusEvent = event,
                    timing = PlanActivityTiming.PAST,
                    isNext = false,
                    onMove = {}, onEdit = {}, onRemove = {},
                )
            }
        }

        composeRule.onNodeWithText("Origine du statut · Confirmé par vous · interface ATLAS").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            "16:30 – 17:00. Marche. État : Terminée. Confirmé par vous · interface ATLAS. Actions : Plus d’actions.",
        ).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Plus d’actions pour Marche").performClick()
        composeRule.onNodeWithText("Origine du statut · Confirmé par vous · interface ATLAS").assertIsDisplayed()
    }

    @Test
    fun reportSheetShowsConflictsAndCrossDateFailureAfterConfirmation() {
        val source = activity(ID_1, "Lecture", 10 * 60, duration = 45).copy(notes = "NOTE_PRIVEE_NE_PAS_PARTAGER")
        val conflict = activity(ID_2, "Rendez-vous", 10 * 60 + 30, duration = 30)
        var submitted: PlanReportTarget? = null
        val plan = dayPlan(version = 1, revisionId = REVISION_ID, activities = listOf(source, conflict), updatedAt = 1L)

        composeRule.setContent {
            AtlasTheme {
                PlanReportBottomSheet(
                    plan = plan,
                    activity = source,
                    onDismiss = {},
                    onConfirm = { submitted = it; false },
                    failureMessage = { "CROSS_DATE_UNSUPPORTED : report vers une autre date indisponible." },
                )
            }
        }

        composeRule.onNodeWithText("Reporter l’activité").assertIsDisplayed()
        composeRule.onNodeWithText("Catégorie · Focus · priorité Importante").assertIsDisplayed()
        composeRule.onNodeWithText("Créneau actuel · Lundi 26 octobre 2026 · 10:00 – 10:45 · 45 min · Europe/Paris")
            .assertIsDisplayed()
        composeRule.onNodeWithTag("plan_report_proposed_slot").assertIsDisplayed()
        composeRule.onNodeWithTag("plan_report_proposed_slot")
            .assertTextContains("10:15 – 11:00 · 45 min · Europe/Paris · Focus · priorité Importante", substring = true)
        composeRule.onAllNodesWithText("NOTE_PRIVEE_NE_PAS_PARTAGER", substring = true).assertCountEquals(0)
        composeRule.onNodeWithText("Conflit avec :").assertIsDisplayed()
        composeRule.onNodeWithText("Rendez-vous · 10:30 – 11:00").assertIsDisplayed()
        composeRule.onNodeWithTag("plan_report_confirm").assertIsNotEnabled()

        composeRule.onNodeWithTag("plan_report_choice_tomorrow").performClick()
        composeRule.onNodeWithText("Les conflits de cette autre date ne sont pas chargés.").assertIsDisplayed()
        composeRule.onNodeWithTag("plan_report_confirm").performScrollTo().performClick()
        composeRule.onNodeWithText("CROSS_DATE_UNSUPPORTED : report vers une autre date indisponible.")
            .performScrollTo().assertIsDisplayed()
        assertTrue(submitted?.localDate == "2026-10-27")
    }

    @Test
    fun contextualCoachPromptIsStructuredConsentedAndDoesNotExposeActivityNotes() {
        val activityWithPrivateNote = activity(ID_1, "Lecture", 10 * 60).copy(notes = "NOTE_PRIVEE_NE_PAS_PARTAGER")
        var sentMessage: String? = null
        var dismissed = false

        composeRule.setContent {
            AtlasTheme {
                PlanActivityCoachDialog(
                    activityId = activityWithPrivateNote.activityId,
                    planVersion = 7,
                    offline = true,
                    onDismiss = { dismissed = true },
                    onSend = { sentMessage = it; true },
                )
            }
        }

        composeRule.onNodeWithText(
            "Contexte structuré · activity_id $ID_1 · plan version 7 · action demander un avis",
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("plan_activity_coach_message").assertIsDisplayed()
        composeRule.onNodeWithTag("plan_activity_coach_message")
            .assertTextContains("J’aimerais échanger sur cette activité.", substring = true)
        composeRule.onNodeWithText("J’autorise le partage de cet identifiant, de la version et de l’action avec ma question.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Mode hors ligne : traitement local sur cet appareil, sans transmission réseau.")
            .assertIsDisplayed()
        composeRule.onAllNodesWithText("NOTE_PRIVEE_NE_PAS_PARTAGER", substring = true).assertCountEquals(0)
        composeRule.onNodeWithTag("plan_activity_coach_send").assertIsNotEnabled()

        composeRule.onNodeWithTag("plan_activity_coach_context_consent").performClick()
        composeRule.onNodeWithTag("plan_activity_coach_send").performClick()

        assertTrue(dismissed)
        assertTrue(sentMessage?.contains("\"activity_id\":\"$ID_1\"") == true)
        assertTrue(sentMessage?.contains("\"plan_version\":7") == true)
        assertTrue(sentMessage?.contains("\"action\":\"ASK_COACH\"") == true)
        assertTrue(sentMessage?.contains("NOTE_PRIVEE_NE_PAS_PARTAGER") == false)
        val displayDensity = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val sendHeightDp = composeRule.onNodeWithTag("plan_activity_coach_send")
            .fetchSemanticsNode().boundsInRoot.height / displayDensity
        assertTrue(sendHeightDp >= 48f)
    }

    @Test
    fun activityMenuOpensTheContextualCoachAction() {
        val target = activity(ID_1, "Marche", 16 * 60 + 30)
        var requested = false
        composeRule.setContent {
            AtlasTheme {
                PlanActivityRow(
                    activity = target,
                    timing = PlanActivityTiming.UPCOMING,
                    isNext = false,
                    onMove = {}, onEdit = {}, onRemove = {},
                    onAskCoach = { requested = true },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Plus d’actions pour Marche").performClick()
        composeRule.onNodeWithText("Dire quelque chose au coach…").performClick()

        assertTrue(requested)
    }

    @Test
    fun actionTargetsStayAtLeast48DpAtDoubleScaleInLandscapeLayout() {
        val target = activity(ID_1, "Marche", 16 * 60 + 30)
        composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        composeRule.setContent {
            val originalDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(originalDensity.density, 2f)) {
                AtlasTheme {
                    PlanActivityRow(
                        activity = target,
                        timing = PlanActivityTiming.UPCOMING,
                        isNext = true,
                        onMove = {}, onEdit = {}, onRemove = {}, onPrimary = {}, onReport = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag("plan_activity_primary_$ID_1").assertIsDisplayed()
        composeRule.onNodeWithTag("plan_activity_report_$ID_1").assertIsDisplayed()
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val primaryHeightDp = composeRule.onNodeWithTag("plan_activity_primary_$ID_1")
            .fetchSemanticsNode().boundsInRoot.height / density
        val reportHeightDp = composeRule.onNodeWithTag("plan_activity_report_$ID_1")
            .fetchSemanticsNode().boundsInRoot.height / density
        assertTrue(primaryHeightDp >= 48f)
        assertTrue(reportHeightDp >= 48f)
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
        composeRule.onNodeWithText("Ajouter une note").assertIsDisplayed()
        composeRule.onNodeWithText("Annuler").assertIsDisplayed()
        composeRule.onAllNodesWithText("Pas fait").assertCountEquals(0)
        composeRule.onNodeWithText("Supprimer l’activité").performClick()
        composeRule.onNodeWithText("Supprimer cette activité ?").assertIsDisplayed()
        composeRule.onNodeWithText("Supprimer l’activité").performClick()
        composeRule.onNodeWithText("Suppression confirmée").assertIsDisplayed()
    }

    @Test
    fun missedMenuActionAppearsOnlyAfterTheScheduledSlotEnds() {
        val target = activity(ID_1, "Marche", 16 * 60 + 30)
        composeRule.setContent {
            AtlasTheme {
                PlanActivityRow(
                    activity = target,
                    timing = PlanActivityTiming.PAST,
                    isNext = false,
                    onMove = {}, onEdit = {}, onRemove = {},
                    canMarkMissed = false,
                )
            }
        }
        composeRule.onNodeWithContentDescription("Plus d’actions pour Marche").performClick()
        composeRule.onAllNodesWithText("Pas fait").assertCountEquals(0)
    }

    @Test
    fun missedMenuActionAppearsAfterTheScheduledSlotEnds() {
        val target = activity(ID_1, "Marche", 16 * 60 + 30)
        composeRule.setContent {
            AtlasTheme {
                PlanActivityRow(
                    activity = target,
                    timing = PlanActivityTiming.PAST,
                    isNext = false,
                    onMove = {}, onEdit = {}, onRemove = {},
                    canMarkMissed = true,
                )
            }
        }
        composeRule.onNodeWithContentDescription("Plus d’actions pour Marche").performClick()
        composeRule.onNodeWithText("Pas fait").assertIsDisplayed()
        composeRule.onNodeWithText("Annuler").assertIsDisplayed()
    }

    @Test
    fun terminalActivityMenuKeepsOnlyTheNoteAction() {
        val target = activity(ID_1, "Lecture terminée", 10 * 60).copy(status = ActivityStatus.DONE)
        composeRule.setContent {
            AtlasTheme {
                PlanActivityRow(
                    activity = target,
                    timing = PlanActivityTiming.PAST,
                    isNext = false,
                    onMove = {}, onEdit = {}, onRemove = {},
                    canMarkMissed = false,
                )
            }
        }

        composeRule.onNodeWithContentDescription("Plus d’actions pour Lecture terminée").performClick()
        composeRule.onNodeWithText("Ajouter une note").assertIsDisplayed()
        composeRule.onAllNodesWithText("Modifier").assertCountEquals(0)
        composeRule.onAllNodesWithText("Annuler").assertCountEquals(0)
        composeRule.onAllNodesWithText("Supprimer l’activité").assertCountEquals(0)
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
