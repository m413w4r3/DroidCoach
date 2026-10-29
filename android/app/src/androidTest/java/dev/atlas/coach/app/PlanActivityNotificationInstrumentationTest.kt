package dev.atlas.coach.app

import android.Manifest
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.atlas.coach.data.StateBackend
import dev.atlas.coach.focus.android.AndroidFocusNotifications
import dev.atlas.coach.plan.ActivityKind
import dev.atlas.coach.plan.ActivityStatus
import dev.atlas.coach.plan.Priority
import dev.atlas.coach.app.ui.AtlasTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 37)
class PlanActivityNotificationInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private lateinit var context: android.content.Context
    private lateinit var app: AtlasApplication
    private lateinit var controller: AtlasController
    private lateinit var planController: PlanController
    private lateinit var planFile: File
    private var previousPlan: ByteArray? = null
    private var permissionWasGranted = false
    private lateinit var target: PlanActivityNotificationTarget
    private val notificationAdapter get() = AndroidFocusNotifications(context)

    @Before
    fun prepareSyntheticPlan() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext
        app = context.applicationContext as AtlasApplication
        permissionWasGranted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        setNotificationPermission(granted = true)

        app.resetControllerForTesting()
        controller = app.controller
        instrumentation.runOnMainSync {
            if (controller.isEmergencyPaused) controller.endEmergencyPause()
            controller.setCapability(AtlasController.CAP_PLAN_ACTIVITY_NOTIFICATIONS, false)
        }
        PlanActivityNotificationRuntime.cancel(context)
        notificationAdapter.ensurePlanActivityChannelForOptIn()

        val today = LocalDate.now()
        planFile = File(context.filesDir, "plans/day-plan-$today.json")
        previousPlan = planFile.takeIf(File::isFile)?.readBytes()
        planFile.delete()
        planController = app.planController
        planController.refresh()
        assertTrue(planController.createTodayPlan())
        val now = ZonedDateTime.now(ZoneId.of(planController.timezone))
        val candidateStart = (now.hour * 60 + now.minute - 5).coerceAtLeast(0)
        val startMinute = if (candidateStart + 30 <= 24 * 60) candidateStart else 0
        assertTrue(
            planController.addActivity(
                title = "Fixture synthétique confidentielle",
                kind = ActivityKind.FOCUS,
                startMinute = startMinute,
                durationMinutes = 30,
                priority = Priority.IMPORTANT,
                notes = "Note synthétique qui ne doit pas apparaître dans la notification",
            ),
        )
        assertTrue(planController.confirmPreview())
        val activity = planController.plan!!.activities.single()
        target = PlanActivityNotificationTarget(
            planId = planController.plan!!.planId,
            activityId = activity.activityId,
            planVersion = planController.plan!!.version,
            localDate = planController.today,
        )
    }

    @After
    fun restoreTestState() {
        if (!::context.isInitialized) return
        runCatching {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                if (::controller.isInitialized) {
                    controller.setCapability(AtlasController.CAP_PLAN_ACTIVITY_NOTIFICATIONS, false)
                }
            }
            PlanActivityNotificationRuntime.cancel(context)
            val parent = planFile.parentFile
            if (parent != null) parent.mkdirs()
            if (previousPlan == null) planFile.delete() else planFile.writeBytes(requireNotNull(previousPlan))
            if (::planController.isInitialized) planController.refresh()
            setNotificationPermission(granted = permissionWasGranted)
        }
    }

    @Test
    fun planReminderCapabilityDefaultsOffAndIsIndependent() {
        var storedState: String? = null
        val freshController = AtlasController(
            context,
            stateBackendOverride = object : StateBackend {
                override fun read(): String? = storedState
                override fun write(value: String) { storedState = value }
            },
        )

        assertFalse(freshController.capabilities[AtlasController.CAP_PLAN_ACTIVITY_NOTIFICATIONS] == true)
        assertTrue(freshController.capabilities[AtlasController.CAP_FOCUS] == true)
        assertFalse(freshController.capabilities[AtlasController.CAP_FOCUS_NOTIFICATIONS] == true)
        assertFalse(freshController.publishPlanActivityReminder(target))
    }

    @Test
    fun notificationsRequirePermissionAndStopAfterRevocation() {
        setNotificationPermission(granted = false)
        enableReminders()
        assertFalse(notificationAdapter.canPostPlanActivityReminder())
        assertFalse(controller.publishPlanActivityReminder(target))

        setNotificationPermission(granted = true)
        enableReminders()
        assertTrue(notificationAdapter.canPostPlanActivityReminder())
        controller.publishPlanActivityReminder(target)
        assertTrue(PlanActivityNotificationRuntime.wasPublished(context, target))
        setNotificationPermission(granted = false)
        assertFalse(notificationAdapter.canPostPlanActivityReminder())
        assertFalse(controller.publishPlanActivityReminder(target))
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            controller.handleNotificationIntent(notificationIntent(PlanActivityNotificationAction.OPEN))
        }
        assertNullPendingAction()
    }

    @Test
    fun missingOrMutedChannelCannotPublish() {
        val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))
        assertFalse(AndroidFocusNotifications.channelAllowsPosting(manager, "missing_${UUID.randomUUID()}"))

        val mutedChannel = "atlas_test_muted_${UUID.randomUUID()}"
        try {
            manager.createNotificationChannel(
                NotificationChannel(mutedChannel, "Synthetic muted channel", NotificationManager.IMPORTANCE_LOW),
            )
            assertFalse(AndroidFocusNotifications.channelAllowsPosting(manager, mutedChannel))
        } finally {
            manager.deleteNotificationChannel(mutedChannel)
        }
    }

    @Test
    fun postedNotificationAndPublicVersionAreNeutral() {
        enableReminders()
        controller.publishPlanActivityReminder(target)
        assertTrue(PlanActivityNotificationRuntime.wasPublished(context, target))
        val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))
        val active = manager.activeNotifications.single {
            it.tag == PlanActivityNotificationRuntime.NOTIFICATION_TAG_PREFIX + target.operationKey
        }.notification

        assertEquals(Notification.VISIBILITY_PRIVATE, active.visibility)
        assertEquals("Rappel du programme", active.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertFalse(active.extras.toString().contains("Fixture synthétique confidentielle"))
        assertFalse(active.extras.toString().contains("Note synthétique"))
        assertNotNull(active.publicVersion)
        assertEquals("ATLAS", active.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Rappel disponible", active.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun notificationPendingIntentRoutesThroughMainActivityToThePlan() {
        enableReminders()
        val instrumentation = getInstrumentation()
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        try {
            val options = ActivityOptions.makeBasic()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }
            PlanActivityNotificationRuntime.actionPendingIntent(
                context,
                target,
                PlanActivityNotificationAction.OPEN,
            ).send(options.toBundle())
            val activity = instrumentation.waitForMonitorWithTimeout(monitor, 5_000) as? MainActivity
            assertNotNull("The plan reminder did not open MainActivity", activity)
            instrumentation.waitForIdleSync()
            assertEquals(AtlasScreenId.PLAN, controller.navigationState.current)
            instrumentation.runOnMainSync { activity?.finish() }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun tapOpensTheTargetAndStartIsOneVersionedTransition() {
        enableReminders()
        showPlanScreen()
        dispatchNotificationAction(PlanActivityNotificationAction.OPEN)
        composeRule.onNodeWithTag("plan_notification_activity_target").assertIsDisplayed()
        composeRule.onNodeWithText("Fixture synthétique confidentielle").assertIsDisplayed()
        assertEquals(AtlasScreenId.PLAN, controller.navigationState.current)

        val initialVersion = planController.plan!!.version
        val startIntent = notificationIntent(PlanActivityNotificationAction.START)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { controller.handleNotificationIntent(startIntent) }
        composeRule.waitUntil(5_000) { planController.plan?.activities?.single()?.status == ActivityStatus.IN_PROGRESS }
        assertEquals(initialVersion + 1, planController.plan!!.version)

        InstrumentationRegistry.getInstrumentation().runOnMainSync { controller.handleNotificationIntent(startIntent) }
        assertEquals(ActivityStatus.IN_PROGRESS, planController.plan!!.activities.single().status)
        assertEquals(initialVersion + 1, planController.plan!!.version)
        assertNullPendingAction()
    }

    @Test
    fun doneActionRequiresConfirmationBeforeChangingStatus() {
        enableReminders()
        showPlanScreen()
        dispatchNotificationAction(PlanActivityNotificationAction.DONE)

        composeRule.onNodeWithText("Confirmer la fin de l’activité ?").assertIsDisplayed()
        assertEquals(ActivityStatus.PLANNED, planController.plan!!.activities.single().status)
        composeRule.onNodeWithText("Confirmer terminé").performClick()
        composeRule.waitUntil(5_000) { planController.plan?.activities?.single()?.status == ActivityStatus.DONE }
        assertEquals(ActivityStatus.DONE, planController.plan!!.activities.single().status)
    }

    @Test
    fun reportActionOpensFormAndCancelDoesNotMoveActivity() {
        enableReminders()
        showPlanScreen()
        val initialVersion = planController.plan!!.version
        dispatchNotificationAction(PlanActivityNotificationAction.REPORT)

        composeRule.onNodeWithText("Reporter l’activité").assertIsDisplayed()
        assertEquals(ActivityStatus.PLANNED, planController.plan!!.activities.single().status)
        assertEquals(initialVersion, planController.plan!!.version)
        composeRule.onNodeWithText("Fermer").performClick()
        assertEquals(ActivityStatus.PLANNED, planController.plan!!.activities.single().status)
        assertEquals(initialVersion, planController.plan!!.version)
    }

    @Test
    fun staleTapAndRevocationCancelPendingWorkWithoutEffect() {
        enableReminders()
        PlanActivityNotificationRuntime.refresh(
            context,
            listOf(requireNotNull(planController.plan)),
            eligible = true,
            nowUtcMillis = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1),
        )
        val workManager = WorkManager.getInstance(context)
        val scheduled = workManager.getWorkInfosByTag(PlanActivityNotificationRuntime.WORK_TAG)
            .get(5, TimeUnit.SECONDS)
        assertTrue(scheduled.any { it.state == WorkInfo.State.ENQUEUED })

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            controller.setCapability(AtlasController.CAP_PLAN_ACTIVITY_NOTIFICATIONS, false)
        }
        val cancelled = workManager.getWorkInfosByTag(PlanActivityNotificationRuntime.WORK_TAG)
            .get(5, TimeUnit.SECONDS)
        assertTrue(cancelled.none { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING })

        val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))
        assertTrue(manager.activeNotifications.none { it.notification.channelId == AndroidFocusNotifications.PLAN_ACTIVITY_CHANNEL_ID })
        val staleIntent = notificationIntent(PlanActivityNotificationAction.OPEN)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { controller.handleNotificationIntent(staleIntent) }
        assertEquals(AtlasController.PLAN_ACTIVITY_NOTIFICATION_UNAVAILABLE_MESSAGE, controller.latestMessage)
        assertNullPendingAction()
    }

    private fun enableReminders() {
        notificationAdapter.ensurePlanActivityChannelForOptIn()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            controller.setCapability(AtlasController.CAP_PLAN_ACTIVITY_NOTIFICATIONS, true)
        }
    }

    private fun showPlanScreen() {
        composeRule.setContent {
            AtlasTheme { PlanScreen(planController, controller) }
        }
        composeRule.waitForIdle()
    }

    private fun dispatchNotificationAction(action: PlanActivityNotificationAction) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            controller.handleNotificationIntent(notificationIntent(action))
        }
        composeRule.waitForIdle()
    }

    private fun notificationIntent(action: PlanActivityNotificationAction) =
        Intent(context, MainActivity::class.java)
            .setAction(PlanActivityNotificationRuntime.ACTION_OPEN_PLAN_ACTIVITY)
            .putExtra(PlanActivityNotificationRuntime.EXTRA_PLAN_ID, target.planId)
            .putExtra(PlanActivityNotificationRuntime.EXTRA_ACTIVITY_ID, target.activityId)
            .putExtra(PlanActivityNotificationRuntime.EXTRA_PLAN_VERSION, target.planVersion)
            .putExtra(PlanActivityNotificationRuntime.EXTRA_LOCAL_DATE, target.localDate)
            .putExtra(PlanActivityNotificationRuntime.EXTRA_ACTION, action.name)

    private fun assertNullPendingAction() {
        assertEquals(null, controller.pendingPlanActivityNotificationAction)
    }

    private fun setNotificationPermission(granted: Boolean) {
        if (Build.VERSION.SDK_INT < 33) return
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        if (granted) automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        else automation.revokeRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
    }
}
