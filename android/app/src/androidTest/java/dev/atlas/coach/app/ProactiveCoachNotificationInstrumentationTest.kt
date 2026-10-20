package dev.atlas.coach.app

import android.app.Activity
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.coach.proactive.CoachTriggerSource
import dev.atlas.coach.coach.proactive.ProactiveEngagementMode
import dev.atlas.coach.coach.proactive.ProactiveNotification
import dev.atlas.coach.coach.proactive.android.ProactiveCoachWorkScheduler
import dev.atlas.coach.sync.FileOutboxStorage
import dev.atlas.coach.sync.agent.AgentActionType
import dev.atlas.coach.sync.agent.ProposalLevel
import dev.atlas.coach.sync.agent.ProposalState
import dev.atlas.coach.sync.agent.ProposalV2
import dev.atlas.coach.sync.agent.inbox.AgentInboxRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProactiveCoachNotificationInstrumentationTest {
    @Test
    fun tappingNotificationOpensAndHighlightsOnlyTheLocalProposal() {
        val fixture = prepareFixture(expired = false)
        val activity = tapNotification(fixture)

        assertEquals(AtlasScreenId.COACH, fixture.controller.navigationState.current)
        assertEquals(fixture.proposalId, fixture.controller.highlightedProactiveProposalId)
        assertEquals(ProposalState.PROPOSED, fixture.controller.agentInbox.single().proposal.state)
        assertEquals(
            setOf(
                ProactiveCoachNotificationPublisher.EXTRA_LOCAL_PROPOSAL_ID,
                ProactiveCoachNotificationPublisher.EXTRA_DESTINATION,
            ),
            activity.intent.extras?.keySet(),
        )
        finish(activity)
    }

    @Test
    fun tappingAnExpiredNotificationOpensItsExpiredStateWithoutChangingIt() {
        val fixture = prepareFixture(expired = true)
        val activity = tapNotification(fixture)

        assertEquals(AtlasScreenId.COACH, fixture.controller.navigationState.current)
        assertEquals(fixture.proposalId, fixture.controller.highlightedProactiveProposalId)
        assertEquals(ProposalState.EXPIRED, fixture.controller.agentInbox.single().proposal.state)
        assertTrue(fixture.controller.latestMessage.orEmpty().contains("a expiré"))
        finish(activity)
    }

    @Test
    fun tappingAfterMandateRevocationShowsNeutralMessageAndDoesNotNavigateOrDispatch() {
        val fixture = prepareFixture(expired = false)
        val workManager = WorkManager.getInstance(fixture.context)
        ProactiveCoachWorkScheduler.refresh(fixture.context, eligible = true)
        assertEquals(1, workManager.getWorkInfosForUniqueWork(ProactiveCoachWorkScheduler.UNIQUE_WORK_NAME)
            .get(5, TimeUnit.SECONDS).size)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            fixture.controller.revokeAgentMandate()
            fixture.controller.navigationState.selectTab(AtlasScreenId.HOME)
        }
        val scheduledStates = workManager.getWorkInfosForUniqueWork(ProactiveCoachWorkScheduler.UNIQUE_WORK_NAME)
            .get(5, TimeUnit.SECONDS).map { it.state }
        assertTrue(scheduledStates.none { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.RUNNING })
        val activity = tapNotification(fixture)

        assertEquals(AtlasScreenId.HOME, fixture.controller.navigationState.current)
        assertEquals(null, fixture.controller.highlightedProactiveProposalId)
        assertEquals(ProposalState.CANCELLED, fixture.controller.agentInbox.single().proposal.state)
        assertTrue(fixture.controller.latestMessage.orEmpty().contains("Aucune action n'a été effectuée"))
        finish(activity)
    }

    private fun prepareFixture(expired: Boolean): Fixture {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as AtlasApplication
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val createdAt = now - 60_000L
        val expiresAt = if (expired) now - 1L else now + 10 * 60_000L
        val seedNow = if (expired) now - 2L else now
        val inboxFile = File(context.filesDir, INBOX_FILE_NAME)
        inboxFile.delete()
        val seedInbox = AgentInboxRepository(FileOutboxStorage(inboxFile)) { seedNow }
        assertNotNull(seedInbox.insertLocal(proposal(id, createdAt, expiresAt)))

        app.resetControllerForTesting()
        lateinit var controller: AtlasController
        instrumentation.runOnMainSync {
            controller = app.controller
            controller.enableAgent()
            controller.navigationState.selectTab(AtlasScreenId.HOME)
        }
        return Fixture(context, controller, id)
    }

    private fun proposal(id: String, createdAt: Long, expiresAt: Long) = ProposalV2(
        schemaVersion = 2,
        proposalId = id,
        idempotencyKey = "notification-test-$id",
        conversationId = null,
        mandateId = "synthetic-mandate",
        mandateVersion = 1L,
        contextVersion = "synthetic-context-v1",
        createdAtUtcMillis = createdAt,
        expiresAtUtcMillis = expiresAt,
        actionType = AgentActionType.HOME_SET_DIRECTIVE.wireValue,
        parameters = linkedMapOf(
            "body" to "Une proposition synthétique sans action automatique.",
            "primary_action" to "open_coach",
            "title" to "Proposition de test",
        ),
        reason = "Fixture synthétique de test",
        requiredLevel = ProposalLevel.CONFIRM,
        state = ProposalState.PROPOSED,
    )

    private fun tapNotification(fixture: Fixture): MainActivity {
        val notification = ProactiveNotification(
            notificationId = "notification-${fixture.proposalId}",
            localProposalId = fixture.proposalId,
            title = "Votre plan ATLAS est prêt",
            body = "Ouvrez le Coach pour consulter la proposition.",
            mode = ProactiveEngagementMode.NORMAL,
            triggerSources = setOf(CoachTriggerSource.DAILY_PLAN_DUE),
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        ProactiveCoachNotificationPublisher(fixture.context).contentIntentFor(notification).send()
        val activity = instrumentation.waitForMonitorWithTimeout(monitor, 5_000) as? MainActivity
        instrumentation.removeMonitor(monitor)
        assertNotNull("Notification did not open MainActivity", activity)
        instrumentation.waitForIdleSync()
        return requireNotNull(activity)
    }

    private fun finish(activity: Activity) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { activity.finish() }
    }

    private data class Fixture(
        val context: Context,
        val controller: AtlasController,
        val proposalId: String,
    )

    private companion object {
        const val INBOX_FILE_NAME = "atlas_agent_inbox_v2.json"
    }
}
