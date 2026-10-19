package dev.atlas.coach.usage

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.telecom.TelecomManager
import android.util.Log
import dev.atlas.coach.rules.FrictionPresentationResult
import dev.atlas.coach.rules.Intervention
import dev.atlas.coach.rules.LocalPolicy
import dev.atlas.coach.rules.RuleEvaluator
import dev.atlas.coach.usage.android.AndroidFrictionPresenter
import dev.atlas.coach.usage.android.AndroidProtectedPackages
import dev.atlas.coach.usage.android.AndroidUsageAccessStatus
import dev.atlas.coach.usage.android.SharedPreferencesUsageStores
import dev.atlas.coach.app.AtlasController
import java.io.BufferedReader
import java.io.InputStreamReader
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry

/** Runs only on the declared Android 17 emulator. Fixture launches are issued as shell `am start`s. */
@RunWith(AndroidJUnit4::class)
class UsageFixtureInstrumentationTest {
    @Test
    fun actualAppOpsDenialStopsBeforeQueryingUsageStats() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val access = AndroidUsageAccessStatus(context)
        val stores = SharedPreferencesUsageStores(context)
        var queried = 0
        val source = object : UsageEventSource {
            override fun query(cursor: UsageCursor, throughUtc: Long, maxRawEvents: Int): UsageEventBatch {
                queried++
                return UsageEventBatch(emptyList(), UsageCursor(throughUtc), false)
            }
        }
        val observer = UsageObserver(
            eventSource = source,
            accessStatus = access,
            consentStore = stores,
            cursorStore = stores,
            stateStore = stores,
            recordStore = stores,
            ruleEvaluator = RuleEvaluator(ZoneId.systemDefault()),
            policyProvider = { LocalPolicy(allowlistedPackageNames = setOf(FIXTURE_PACKAGE)) },
            frictionPresenter = { FrictionPresentationResult.UNAVAILABLE },
        )
        val now = System.currentTimeMillis()
        observer.revokeConsent()
        observer.acceptConsent(ObservationConsent.grant(ObservationPurpose.GENTLE_FRICTION, setOf(FIXTURE_PACKAGE), now), now)
        try {
            // A prior Settings grant may be stored at UID scope on Android 17.
            // Override that scope too so this isolated denial check stays hermetic.
            shell("appops set --uid ${context.packageName} GET_USAGE_STATS ignore")
            assertTrue("GET_USAGE_STATS should be denied for this isolated check", !access.isAllowed())
            assertEquals(UsagePollStatus.PERMISSION_REQUIRED, observer.poll(now + 1_000L).status)
            assertEquals(0, queried)
            assertTrue(stores.allRecords().isEmpty())
            Log.i(TAG, "denied_usage_access status=PERMISSION_REQUIRED queries=$queried records=0")
        } finally {
            observer.revokeConsent()
            shell("appops set --uid ${context.packageName} GET_USAGE_STATS default")
            shell("appops set ${context.packageName} GET_USAGE_STATS allow")
        }
    }

    @Test
    fun notificationPresenterReportsMissingManifestPermissionInsteadOfPosting() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val deniedContext = object : ContextWrapper(context) {
            override fun checkSelfPermission(permission: String): Int = PackageManager.PERMISSION_DENIED
        }
        val result = AndroidFrictionPresenter(deniedContext).present(
            Intervention("instrumentation", "event", FIXTURE_PACKAGE, "rule", System.currentTimeMillis(), 0L),
        )
        assertEquals(FrictionPresentationResult.PERMISSION_REQUIRED, result)
        Log.i(TAG, "friction_notification_denied status=PERMISSION_REQUIRED")
    }

    @Test
    fun fourRealFixtureOpensReachTheRuleAfterReturningToAtlas() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        Log.i(TAG, "POST_NOTIFICATIONS granted for the isolated AVD integration run")
        if (!AndroidUsageAccessStatus(context).isAllowed()) {
            Log.w(TAG, "BLOCKED: GET_USAGE_STATS is not allowed; four real fixture launches were not queried")
            return
        }
        if (!isFixtureInstalled(context)) {
            Log.w(TAG, "BLOCKED: distraction fixture APK is not installed")
            return
        }

        context.getSharedPreferences("atlas_local_state", Context.MODE_PRIVATE).edit().clear().commit()
        val controller = AtlasController(context)
        controller.revokeUsageObservation()
        val dialerPackage = context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        assertTrue("Fixture must appear in the launched-app picker", controller.selectableUsageApps.any { it.packageName == FIXTURE_PACKAGE })
        assertTrue("ATLAS must remove its default dialer from the app picker", dialerPackage !in controller.selectableUsageApps.map { it.packageName })
        assertTrue("Explicit consent for the fixture allowlist was rejected", controller.activateUsageObservation(setOf(FIXTURE_PACKAGE)))
        assertTrue(controller.usageConsentActive)
        assertEquals(setOf(FIXTURE_PACKAGE), controller.selectedUsagePackages)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.cancelAll()
        val outsideAllowlist = controller.selectableUsageApps.firstOrNull { it.packageName != FIXTURE_PACKAGE }
        val outsideComponent = outsideAllowlist?.let { context.packageManager.getLaunchIntentForPackage(it.packageName)?.component }
        val outsidePackageName = outsideAllowlist?.packageName ?: "com.android.settings"
        if (outsideAllowlist != null && outsideComponent != null) {
            shell("am start -W -n ${outsideComponent.flattenToShortString()}")
            SystemClock.sleep(300)
            shell("am force-stop ${outsideAllowlist.packageName}")
            shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME")
            SystemClock.sleep(200)
            Log.i(TAG, "outside_allowlist_launch=${outsideAllowlist.packageName}")
        } else {
            shell("am start -W -a android.settings.SETTINGS")
            SystemClock.sleep(300)
            shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME")
            SystemClock.sleep(200)
            Log.i(TAG, "outside_allowlist_launch=$outsidePackageName (platform app fallback)")
        }

        if (!dialerPackage.isNullOrBlank()) {
            shell("am start -a android.intent.action.DIAL")
            SystemClock.sleep(300)
            shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME")
            SystemClock.sleep(200)
        }

        var fourthLaunchAt = 0L
        repeat(4) { index ->
            shell("am force-stop $FIXTURE_PACKAGE")
            shell("am start -W -n $FIXTURE_PACKAGE/$FIXTURE_ACTIVITY")
            if (index == 3) fourthLaunchAt = SystemClock.elapsedRealtime()
            SystemClock.sleep(500)
            shell("am force-stop $FIXTURE_PACKAGE")
            shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME")
            SystemClock.sleep(200)
        }

        // Returning to ATLAS starts the visible Activity poll loop; background observation is not claimed.
        shell("am start -n ${context.packageName}/dev.atlas.coach.app.MainActivity")
        val deadline = SystemClock.elapsedRealtime() + 8_000L
        while (controller.usageHistoryCount < 4 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(250)
        val detectionLatency = SystemClock.elapsedRealtime() - fourthLaunchAt
        Log.i(TAG, "fixture_open_to_visible_poll_latency_ms=$detectionLatency")
        assertEquals(4, controller.usageHistoryCount)
        assertTrue(controller.usageConsentActive)
        Log.i(TAG, "fixture_records=${controller.usageHistoryCount} consent=${controller.usageConsentActive} interventions=${controller.interventions.size}")

        val records = SharedPreferencesUsageStores(context).allRecords()
        assertEquals(4, records.count { it.packageName == FIXTURE_PACKAGE })
        assertTrue("An app outside the explicit allowlist was recorded", records.none { it.packageName == outsidePackageName })
        if (!dialerPackage.isNullOrBlank()) assertTrue(records.none { it.packageName == dialerPackage })

        val hasFrictionNotification = notificationManager.activeNotifications.any {
            it.notification.extras?.getString(android.app.Notification.EXTRA_TITLE) == "Une pause ?"
        }
        Log.i(TAG, "friction_notification_active=$hasFrictionNotification")
        assertTrue("Four fixture opens did not post a friction notification", hasFrictionNotification)
        shell("am start -n ${context.packageName}/dev.atlas.coach.app.MainActivity")
        SystemClock.sleep(500)
        shell("cmd statusbar expand-notifications")
        SystemClock.sleep(1_200)
        shell("screencap -p /sdcard/H1-friction-notification.png")
        shell("cmd statusbar collapse")
        SystemClock.sleep(300)
        val friction = notificationManager.activeNotifications.first {
            it.notification.extras?.getString(android.app.Notification.EXTRA_TITLE) == "Une pause ?"
        }.notification
        val intentionAction = friction.actions?.firstOrNull { it.title.toString().contains("intention", ignoreCase = true) }
        assertTrue("Friction notification has no action that opens IntentionActivity", intentionAction != null)
        intentionAction!!.actionIntent.send()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        val intentionVisible = shell("dumpsys activity activities").contains("IntentionActivity")
        Log.i(TAG, "intention_activity_visible=$intentionVisible")
        assertTrue(intentionVisible)
        shell("screencap -p /sdcard/H1-friction-intention.png")

        controller.revokeUsageObservation()
        Log.i(TAG, "revoke_status=${SharedPreferencesUsageStores(context).currentConsent().enabled} history=${controller.usageHistoryCount}")
        assertEquals(ObservationConsent.REVOKED, SharedPreferencesUsageStores(context).currentConsent())
        assertEquals(0, controller.usageHistoryCount)
        controller.pollUsageObservation()
        assertEquals("Revoked observation continued collecting", 0, controller.usageHistoryCount)
        val restoredController = AtlasController(context)
        restoredController.pollUsageObservation()
        assertEquals("Revoked observation resumed after controller recreation", 0, restoredController.usageHistoryCount)
        Log.i(TAG, "revocation_verified persisted=true polls_after_revoke=2 history=0")
    }

    private fun isFixtureInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getApplicationInfo(FIXTURE_PACKAGE, 0)
    }.isSuccess

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
            BufferedReader(InputStreamReader(stream)).readText()
        }
    }

    private companion object {
        const val FIXTURE_PACKAGE = "dev.atlas.fixture.distraction"
        const val FIXTURE_ACTIVITY = "dev.atlas.fixture.distraction.DistractionActivity"
        const val TAG = "ATLAS_R3"
    }
}
