package dev.atlas.coach.usage

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import dev.atlas.coach.rules.FrictionPresentationResult
import dev.atlas.coach.rules.Intervention
import dev.atlas.coach.rules.LocalPolicy
import dev.atlas.coach.rules.RuleEvaluator
import dev.atlas.coach.usage.android.AndroidFrictionPresenter
import dev.atlas.coach.usage.android.AndroidProtectedPackages
import dev.atlas.coach.usage.android.AndroidUsageAccessStatus
import dev.atlas.coach.usage.android.AndroidUsageStatsEventSource
import dev.atlas.coach.usage.android.SharedPreferencesUsageStores
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
        if (access.isAllowed()) {
            Log.i(TAG, "AppOps is granted; denied-path assertion skipped in this AVD state")
            return
        }

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
        observer.acceptConsent(ObservationConsent.grant(ObservationPurpose.GENTLE_FRICTION, setOf(FIXTURE_PACKAGE), now), now)

        assertEquals(UsagePollStatus.PERMISSION_REQUIRED, observer.poll(now + 1_000L).status)
        assertEquals(0, queried)
        assertTrue(stores.allRecords().isEmpty())
        observer.revokeConsent()
    }

    @Test
    fun notificationPresenterReportsMissingManifestPermissionInsteadOfPosting() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = AndroidFrictionPresenter(context).present(
            Intervention("instrumentation", "event", FIXTURE_PACKAGE, "rule", System.currentTimeMillis(), 0L),
        )
        assertEquals(FrictionPresentationResult.PERMISSION_REQUIRED, result)
    }

    @Test
    fun fourRealFixtureOpensReachTheRuleAfterReturningToAtlas() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (!AndroidUsageAccessStatus(context).isAllowed()) {
            Log.w(TAG, "BLOCKED: GET_USAGE_STATS is not allowed; four real fixture launches were not queried")
            return
        }
        if (!isFixtureInstalled(context)) {
            Log.w(TAG, "BLOCKED: distraction fixture APK is not installed")
            return
        }

        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val source = AndroidUsageStatsEventSource(manager)
        val stores = SharedPreferencesUsageStores(context)
        val rules = RuleEvaluator(ZoneId.systemDefault())
        var presented = 0
        val allowlist = setOf(FIXTURE_PACKAGE)
        val observer = UsageObserver(
            eventSource = source,
            accessStatus = AndroidUsageAccessStatus(context),
            consentStore = stores,
            cursorStore = stores,
            stateStore = stores,
            recordStore = stores,
            ruleEvaluator = rules,
            policyProvider = {
                LocalPolicy(openThreshold = 4, allowlistedPackageNames = allowlist)
            },
            frictionPresenter = {
                presented++
                FrictionPresentationResult.POSTED
            },
            platformProtectedPackages = {
                currentHomePackage(context)?.let(::setOf).orEmpty() + context.packageName
            },
        )

        observer.revokeConsent()
        val consentTime = System.currentTimeMillis()
        observer.acceptConsent(
            ObservationConsent.grant(ObservationPurpose.GENTLE_FRICTION, allowlist, consentTime),
            consentTime,
        )

        var fourthLaunchAt = 0L
        repeat(4) { index ->
            shell("am force-stop $FIXTURE_PACKAGE")
            shell("am start -W -n $FIXTURE_PACKAGE/$FIXTURE_ACTIVITY")
            if (index == 3) fourthLaunchAt = SystemClock.elapsedRealtime()
            SystemClock.sleep(1_200)
            shell("am force-stop $FIXTURE_PACKAGE")
            shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME")
            SystemClock.sleep(400)
        }

        // This is the app-visible poll point: no claim of interception while ATLAS is backgrounded.
        shell("am start -n ${context.packageName}/dev.atlas.coach.app.MainActivity")
        val result = observer.poll(System.currentTimeMillis())
        val detectionLatency = SystemClock.elapsedRealtime() - fourthLaunchAt
        Log.i(TAG, "fixture_open_to_visible_poll_latency_ms=$detectionLatency")
        assertEquals(UsagePollStatus.PROCESSED, result.status)
        assertEquals(4, result.acceptedEvents.count { it.packageName == FIXTURE_PACKAGE })
        assertEquals(1, result.interventions.size)
        assertEquals(1, presented)

        val realPresenter = AndroidFrictionPresenter(context)
        assertEquals(
            FrictionPresentationResult.PERMISSION_REQUIRED,
            realPresenter.present(result.interventions.single()),
        )

        // Settings is deliberately excluded even though it produced a real UsageStats foreground event.
        shell("am start -a android.settings.SETTINGS")
        SystemClock.sleep(500)
        shell("am start -n ${context.packageName}/dev.atlas.coach.app.MainActivity")
        val protectedPoll = observerAfterFirstPoll(context, source, stores, rules, allowlist)
            .poll(System.currentTimeMillis())
        assertTrue(protectedPoll.acceptedEvents.none { it.packageName == "com.android.settings" })
        assertEquals(4, stores.allRecords().size)

        observer.revokeConsent()
        assertEquals(ObservationConsent.REVOKED, stores.currentConsent())
        assertTrue(stores.allRecords().isEmpty())
        assertNull(stores.currentCursor())
    }

    private fun observerAfterFirstPoll(
        context: Context,
        source: AndroidUsageStatsEventSource,
        stores: SharedPreferencesUsageStores,
        rules: RuleEvaluator,
        allowlist: Set<String>,
    ) = UsageObserver(
        eventSource = source,
        accessStatus = AndroidUsageAccessStatus(context),
        consentStore = stores,
        cursorStore = stores,
        stateStore = stores,
        recordStore = stores,
        ruleEvaluator = rules,
        policyProvider = { LocalPolicy(openThreshold = 4, allowlistedPackageNames = allowlist) },
        frictionPresenter = { FrictionPresentationResult.POSTED },
        platformProtectedPackages = {
            currentHomePackage(context)?.let(::setOf).orEmpty() + context.packageName
        },
    )

    private fun isFixtureInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getApplicationInfo(FIXTURE_PACKAGE, 0)
    }.isSuccess

    private fun currentHomePackage(context: Context): String? = context.packageManager.resolveActivity(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
        PackageManager.MATCH_DEFAULT_ONLY,
    )?.activityInfo?.packageName

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
