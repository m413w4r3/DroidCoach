package dev.atlas.coach.calendar.local

import android.Manifest
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.app.CalendarWriteConsentStore
import dev.atlas.coach.app.PlanController
import dev.atlas.coach.app.SharedPreferencesCalendarWriteConsentStore
import dev.atlas.coach.app.SharedPreferencesCalendarWriteSelectionStore
import dev.atlas.coach.calendar.SharedPreferencesCalendarReadConsentStore
import dev.atlas.coach.plan.ActivityKind
import dev.atlas.coach.plan.Priority
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Seeds a real, approved version-2 plan plus the local write consent and the
 * ATLAS calendar selection. The production Plan UI then performs the grouped
 * confirmation and the actual calendar sync.
 */
@RunWith(AndroidJUnit4::class)
class CalendarUiSeedInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    @Test
    fun seedApprovedPlanConsentAndCalendarSelection() {
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.READ_CALENDAR,
        )
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.WRITE_CALENDAR,
        )
        val provisioned = AtlasLocalCalendar.ensure(context)
        assertTrue(provisioned is AtlasLocalCalendar.Result.Ready)
        val calendar = provisioned as AtlasLocalCalendar.Result.Ready

        assertTrue(SharedPreferencesCalendarReadConsentStore(context).grant())
        val consentStore: CalendarWriteConsentStore =
            SharedPreferencesCalendarWriteConsentStore(context)
        assertTrue(consentStore.grant())
        assertTrue(SharedPreferencesCalendarWriteSelectionStore(context).select(calendar.calendarId))

        val planController = PlanController(directory = File(context.filesDir, "plans"))
        planController.refresh()
        if (planController.plan == null) {
            assertTrue(planController.createTodayPlan())
        }
        val current = planController.plan ?: error("plan missing after creation")
        if (current.version < 2) {
            assertTrue(
                planController.addActivity(
                    title = "ATLAS TEST calendar sync",
                    kind = ActivityKind.FOCUS,
                    startMinute = 600,
                    durationMinutes = 45,
                    priority = Priority.IMPORTANT,
                ),
            )
            assertTrue(planController.confirmPreview())
        }
        assertTrue((planController.plan?.version ?: 0) >= 2)
        assertTrue(planController.plan?.activities?.isNotEmpty() == true)
    }

    @Test
    fun moveApprovedPlanActivity() {
        val planController = approvedController()
        val activity = planController.plan?.activities?.firstOrNull {
            it.title == "ATLAS TEST calendar sync"
        } ?: error("seeded activity missing")
        assertTrue(planController.moveActivity(activity.activityId, 660))
        assertTrue(planController.confirmPreview())
        assertTrue((planController.plan?.version ?: 0) >= 3)
    }

    @Test
    fun removeApprovedPlanActivity() {
        val planController = approvedController()
        val activity = planController.plan?.activities?.firstOrNull {
            it.title == "ATLAS TEST calendar sync"
        } ?: return
        assertTrue(planController.removeActivity(activity.activityId))
        assertTrue(planController.confirmPreview())
        assertTrue((planController.plan?.version ?: 0) >= 4)
    }

    private fun approvedController(): PlanController {
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.READ_CALENDAR,
        )
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.WRITE_CALENDAR,
        )
        return PlanController(directory = File(context.filesDir, "plans")).also { it.refresh() }
    }
}
