package dev.atlas.coach.app

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.app.ui.AtlasRoutes
import dev.atlas.coach.app.ui.AtlasScreenFrame
import dev.atlas.coach.app.ui.AtlasTheme
import dev.atlas.coach.data.PersistenceDiagnosticsStore
import dev.atlas.coach.data.RevocationBarrierState
import dev.atlas.coach.data.RevocationBarrierStorage
import dev.atlas.coach.data.StateBackend
import dev.atlas.coach.focus.FakeClock
import dev.atlas.coach.focus.FocusPlan
import dev.atlas.coach.focus.FocusSession
import dev.atlas.coach.focus.FocusStatus
import dev.atlas.coach.focus.PhaseAlarmScheduler
import dev.atlas.coach.focus.PhaseNotificationResult
import dev.atlas.coach.focus.PhaseNotifier
import dev.atlas.coach.focus.PhaseTransition
import dev.atlas.coach.sync.EventOutbox
import dev.atlas.coach.sync.QueuedEvent
import dev.atlas.coach.sync.SyncConsent
import dev.atlas.coach.sync.SyncEvent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FocusGaugeInstrumentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun gaugeAndSessionControlsRemainAvailableWhenFocusIsPaused() {
        val backend = MemoryBackend()
        lateinit var controller: AtlasController
        composeRule.runOnUiThread {
            controller = AtlasController(
                context = isolatedContext(),
                clock = FakeClock(startUtcMillis = 1_800_000_000_000L),
                stateBackendOverride = backend,
                revocationBarrierStorageOverride = NoOpBarrierStorage(),
                persistenceDiagnosticsStoreOverride = NoOpDiagnosticsStore(),
                syncOutboxOverride = NoOpEventOutbox(),
                phaseAlarmSchedulerOverride = NoOpPhaseAlarmScheduler(),
                phaseNotifierOverride = NoOpPhaseNotifier(),
            )
            controller.setCapability(AtlasController.CAP_FOCUS, true)
            controller.startFocus(
                FocusPlan.custom(workSeconds = 1_500L, totalWorkCycles = 4)
                    .copy(taskTitle = "Session de test"),
            )
        }

        val displayedRevision = mutableStateOf(controller.revision)
        composeRule.setContent {
            AtlasTheme {
                AtlasScreenFrame(
                    title = "Focus",
                    activeDestination = AtlasRoutes.FOCUS,
                    canGoBack = false,
                    emergencyPaused = false,
                    onBack = {},
                    onEmergencyPause = {},
                    onNavigate = {},
                ) {
                    FocusSessionScreen(controller, revision = displayedRevision.value)
                }
            }
        }

        assertGaugeProgress(1f)
        assertSessionControlsVisible()
        composeRule.onNodeWithTag("focus_phase")
            .performScrollTo()
            .assertTextEquals("Concentration · en cours")
        composeRule.onNodeWithContentDescription("Activer la pause d'urgence de 30 minutes")
            .assertIsDisplayed()

        composeRule.onNodeWithTag("focus_pause_resume").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            controller.focusSession?.status == FocusStatus.PAUSED
        }
        composeRule.runOnUiThread { displayedRevision.value = controller.revision }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("focus_time_gauge").performScrollTo().assertIsDisplayed()
        assertGaugeProgress(1f)
        composeRule.onNodeWithTag("focus_phase")
            .performScrollTo()
            .assertTextEquals("Concentration · en pause")
        composeRule.onNodeWithTag("focus_pause_resume")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Reprendre").assertIsDisplayed()
        assertSessionControlsVisible()
        composeRule.onNodeWithContentDescription("Activer la pause d'urgence de 30 minutes")
            .assertIsDisplayed()
    }

    private fun assertGaugeProgress(expected: Float) {
        val semantics = composeRule.onNodeWithTag("focus_time_gauge").fetchSemanticsNode().config
        assertEquals(expected, semantics[SemanticsProperties.ProgressBarRangeInfo].current, 0.0001f)
        assertEquals(
            listOf("Temps restant 25 min 0 s"),
            semantics[SemanticsProperties.ContentDescription],
        )
    }

    private fun assertSessionControlsVisible() {
        listOf("focus_pause_resume", "focus_cancel", "focus_finish").forEach { tag ->
            composeRule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        }
    }

    private fun isolatedContext(): Context {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = UUID.randomUUID().toString()
        val files = File(target.cacheDir, "r5-i-focus-gauge-$namespace").apply {
            check(mkdirs() || isDirectory)
        }
        return object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getCacheDir(): File = files
            override fun getSharedPreferences(name: String, mode: Int) =
                target.getSharedPreferences("r5_i_focus_gauge_${namespace}_$name", mode)
        }
    }

    private class MemoryBackend : StateBackend {
        private var value: String? = null
        override fun read(): String? = value
        override fun write(value: String) { this.value = value }
        override fun quarantine(rawValue: String, reason: String) = true
    }

    private class NoOpBarrierStorage : RevocationBarrierStorage {
        override fun readState() = RevocationBarrierState()
        override fun writeState(state: RevocationBarrierState) = true
    }

    private class NoOpDiagnosticsStore : PersistenceDiagnosticsStore {
        override fun read(): String? = null
        override fun write(value: String) = true
    }

    private class NoOpEventOutbox : EventOutbox {
        override fun enqueue(event: SyncEvent, consent: SyncConsent) = true
        override fun pending(nowUtcMillis: Long): List<QueuedEvent> = emptyList()
        override fun acknowledge(clientEventIds: Set<String>) = Unit
        override fun defer(clientEventId: String, nextAttemptAtUtcMillis: Long) = Unit
        override fun discardDisallowed(consent: SyncConsent) = 0
        override fun clear() = 0
        override fun makeDueNow() = 0
        override fun size() = 0
        override fun nextAttemptAtUtcMillis(): Long? = null
    }

    private class NoOpPhaseAlarmScheduler : PhaseAlarmScheduler {
        override fun schedule(session: FocusSession) = Unit
        override fun cancel(sessionId: String) = Unit
    }

    private class NoOpPhaseNotifier : PhaseNotifier {
        override fun notify(transition: PhaseTransition) = PhaseNotificationResult.NOTIFICATIONS_DISABLED
        override fun cancel(sessionId: String) = Unit
    }
}
