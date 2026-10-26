package dev.atlas.coach.sync.relay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.app.AtlasApplication
import dev.atlas.coach.app.AtlasController
import dev.atlas.coach.plan.ActivityKind
import dev.atlas.coach.plan.ActivityStatus
import dev.atlas.coach.plan.DayPlan
import dev.atlas.coach.plan.LocalSchedule
import dev.atlas.coach.plan.PlanActivity
import dev.atlas.coach.plan.PlanOrigin
import dev.atlas.coach.plan.PlanRevisionApiRequest
import dev.atlas.coach.plan.PlanRevisionApiResult
import dev.atlas.coach.plan.PlanState
import dev.atlas.coach.plan.Priority
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Uses the real application controller in RELAY mode: capability/mandate gate,
 * RelayPlanRevisionClient, protected relay, PC connector and FastAPI proposal.
 */
@RunWith(AndroidJUnit4::class)
class RelayAppPlanRevisionInstrumentedTest {
    @Test
    fun applicationControllerUsesRelayForAPlanRevision() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val controller = (context.applicationContext as AtlasApplication).controller

        controller.setCapability(AtlasController.CAP_AGENT, true)
        controller.setCapability(AtlasController.CAP_PLAN_REVISION, true)
        controller.relaySettings.connect()
        val deadline = System.currentTimeMillis() + 25_000
        while (controller.relaySettings.state != RelayConnectionState.RELAY_CONNECTED &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(200)
        }
        assertTrue(
            "relay state=${controller.relaySettings.state}",
            controller.relaySettings.state == RelayConnectionState.RELAY_CONNECTED,
        )

        val client = controller.planRevisionClient()
        assertNotNull("the controller did not select the relayed client", client)

        val now = System.currentTimeMillis()
        val planId = UUID.randomUUID().toString()
        val activityId = UUID.randomUUID().toString()
        val revisionId = UUID.randomUUID().toString()
        val localDate = LocalDate.now().toString()
        val timezone = ZoneId.systemDefault().id
        val plan = DayPlan(
            planId = planId,
            localDate = localDate,
            timezone = timezone,
            version = 4,
            currentRevisionId = revisionId,
            state = PlanState.DRAFT,
            activities = listOf(
                PlanActivity(
                    activityId = activityId,
                    planId = planId,
                    kind = ActivityKind.FOCUS,
                    title = "Réviser les notes",
                    status = ActivityStatus.PLANNED,
                    scheduledLocal = LocalSchedule(localDate, 480, 50, timezone),
                    priority = Priority.IMPORTANT,
                    createdRevisionId = revisionId,
                    lastRevisionId = revisionId,
                ),
            ),
            createdAt = now,
            updatedAt = now,
            origin = PlanOrigin.LOCAL,
        )
        val request = PlanRevisionApiRequest(
            requestId = UUID.randomUUID().toString(),
            conversationId = "conversation-r3a-app-relay",
            message = "Propose un petit décalage du bloc de révision.",
            planContextConsented = true,
            mandate = controller.planRevisionMandate,
            plan = plan,
        )

        val result = requireNotNull(client).propose(request)
        assertTrue("plan revision result=$result", result is PlanRevisionApiResult.Success)
        val response = (result as PlanRevisionApiResult.Success).response
        assertTrue(response.agentStatus == "ok")
        assertNotNull(response.proposal)
        assertTrue(response.proposal?.parameters?.baseVersion == 4)
        assertTrue(response.proposal?.parameters?.planId == planId)
    }
}
