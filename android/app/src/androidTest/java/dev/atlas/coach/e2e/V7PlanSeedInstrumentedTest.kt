package dev.atlas.coach.e2e

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.app.PlanController
import dev.atlas.coach.plan.ActivityKind
import dev.atlas.coach.plan.PlanState
import dev.atlas.coach.plan.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class V7PlanSeedInstrumentedTest {
    @Test
    fun seedSyntheticApprovedPlanInAppStorage() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val planController = PlanController(directory = File(targetContext.filesDir, "plans"))
        planController.refresh()

        if (planController.plan == null) {
            assertTrue("today's plan should be created", planController.createTodayPlan())
        }

        val currentPlan = planController.plan ?: error("plan missing after refresh/create")
        val seededActivity = currentPlan.activities.isEmpty()
        if (seededActivity) {
            assertTrue(
                "synthetic activity should be added",
                planController.addActivity(
                    title = SYNTHETIC_ACTIVITY_TITLE,
                    kind = ActivityKind.FOCUS,
                    startMinute = 600,
                    durationMinutes = 30,
                    priority = Priority.IMPORTANT,
                ),
            )
            assertTrue("synthetic activity preview should be approved", planController.confirmPreview())
        }

        planController.refresh()
        val approvedPlan = planController.plan ?: error("plan missing after seeding")
        assertEquals("plan should be approved", PlanState.APPROVED, approvedPlan.state)
        assertTrue("approved plan should be at least version 2", approvedPlan.version >= 2)
        assertTrue("plan should contain an activity", approvedPlan.activities.isNotEmpty())
        if (seededActivity) {
            assertTrue(
                "plan should contain the synthetic focus activity",
                approvedPlan.activities.any { it.title == SYNTHETIC_ACTIVITY_TITLE },
            )
        }

        Log.i(
            LOG_TAG,
            "ATLAS_V7_SEED plan_id=${approvedPlan.planId} " +
                "version=${approvedPlan.version} activities=${approvedPlan.activities.size}",
        )
    }

    private companion object {
        const val LOG_TAG = "ATLAS_V7_SEED"
        const val SYNTHETIC_ACTIVITY_TITLE = "ATLAS V7 synthetic focus"
    }
}
