package dev.atlas.coach.e2e

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.atlas.coach.app.AtlasApplication
import dev.atlas.coach.app.PlanController
import dev.atlas.coach.plan.ActivityKind
import dev.atlas.coach.plan.DayPlan
import dev.atlas.coach.plan.PlanState
import dev.atlas.coach.plan.Priority
import dev.atlas.coach.sync.AndroidCoreV4SyncStore
import dev.atlas.coach.sync.AndroidSyncStorage
import dev.atlas.coach.sync.relay.RelayConnectionState
import dev.atlas.coach.sync.v4.CoreV4Category
import dev.atlas.coach.sync.v4.CoreV4ConsentSnapshot
import dev.atlas.coach.sync.v4.CoreV4ConsentState
import dev.atlas.coach.sync.v4.CoreV4Diagnostics
import dev.atlas.coach.sync.v4.CoreV4SyncScheduler
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class V7_06A_VmConsentSyncInstrumentedTest {
    @Test
    fun vm1ConsentSyncOfflineRevocationResumeAndIdempotentPass() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.applicationContext
        val app = context as AtlasApplication
        val relaySettings = app.controller.relaySettings
        val store = AndroidSyncStorage.v4(context)
        val arguments = InstrumentationRegistry.getArguments()
        val relayUrl = arguments.getString(ARG_RELAY_URL).orEmpty()
        val serverId = arguments.getString(ARG_SERVER_ID).orEmpty()
        val serverPublicKey = arguments.getString(ARG_SERVER_PUBLIC_KEY).orEmpty()
        require(relayUrl in setOf(EXPECTED_RELAY_URL, PHYSICAL_RELAY_URL)) {
            "relay_url must target the isolated emulator or adb-reverse host relay"
        }
        require(serverId == EXPECTED_SERVER_ID) { "server_id must target the isolated VM connector" }
        require(serverPublicKey.isNotBlank()) { "server_public_key is required" }

        val pairingFile = File(context.filesDir, PAIRING_FILE_NAME)
        val pairing = JSONObject(pairingFile.readText(Charsets.UTF_8))
        val deviceId = pairing.getString("device_id")
        val deviceToken = pairing.getString("token")
        require(deviceId.isNotBlank() && deviceToken.isNotBlank()) { "private synthetic pairing is incomplete" }

        val initialPlan = ensureSyntheticApprovedPlan(context)
        val repeatedSeed = ensureSyntheticApprovedPlan(context)
        assertEquals("seeding must preserve the plan id", initialPlan.planId, repeatedSeed.planId)
        assertEquals("seeding must preserve the plan version", initialPlan.version, repeatedSeed.version)
        assertEquals("seeding must preserve the synthetic activity", initialPlan.activities, repeatedSeed.activities)

        val initialDayConsent = store.consent(CoreV4Category.DAY_PLAN).snapshot
        val initialStatusConsent = store.consent(CoreV4Category.ACTIVITY_STATUS).snapshot
        assertEquals("VM-1 requires a fresh local V4 consent store", CoreV4ConsentState.UNKNOWN, initialDayConsent.state)
        assertEquals(CoreV4ConsentState.UNKNOWN, initialStatusConsent.state)
        assertEquals(1, initialDayConsent.version)
        assertEquals(1, initialStatusConsent.version)

        val scenario = VmScenario(initialPlan, initialDayConsent, initialStatusConsent)
        var scenarioFailure: Throwable? = null
        CoreV4Diagnostics.clear()
        CoreV4Diagnostics.enableForDiagnostics()
        try {
            val saved = relaySettings.saveConfiguration(
                relayUrl = relayUrl,
                serverId = serverId,
                serverPublicKey = serverPublicKey,
                deviceId = deviceId,
                deviceToken = deviceToken,
            )
            assertTrue("synthetic relay pairing must persist", saved.isSuccess)
            assertTrue("persisted Keystore credential must be available", relaySettings.credentialsConfigured)
            assertTrue("temporary pairing token file must not remain after Keystore import", pairingFile.delete())
            Log.i(LOG_TAG, "step=01 result=PASS pairing_read=true configuration_saved=true")

            instrumentation.runOnMainSync { relaySettings.connect() }
            waitUntil(
                description = "authenticated relay connection",
                timeoutMillis = RELAY_TIMEOUT_MILLIS,
                diagnostic = { "state=${relaySettings.state}; configured=${relaySettings.credentialsConfigured}" },
            ) { relaySettings.state == RelayConnectionState.RELAY_CONNECTED }
            Log.i(LOG_TAG, "step=02 result=PASS relay_connected=true")

            scenario.dayPlanGranted = store.setConsent(CoreV4Category.DAY_PLAN, granted = true).snapshot
            scenario.statusGranted = store.setConsent(CoreV4Category.ACTIVITY_STATUS, granted = true).snapshot
            assertEquals(CoreV4ConsentState.GRANTED, scenario.dayPlanGranted.state)
            assertEquals(CoreV4ConsentState.GRANTED, scenario.statusGranted.state)
            CoreV4SyncScheduler.schedule(context)
            Log.i(LOG_TAG, "step=03 result=PASS consent_day_plan_granted=true")
            Log.i(LOG_TAG, "step=04 result=PASS consent_activity_status_granted=true")

            waitUntil(
                description = "both consent acknowledgements and the plan receipt",
                timeoutMillis = SYNC_TIMEOUT_MILLIS,
                diagnostic = { syncDiagnostic(store, initialPlan, scenario) },
            ) {
                val day = store.consent(CoreV4Category.DAY_PLAN).snapshot
                val status = store.consent(CoreV4Category.ACTIVITY_STATUS).snapshot
                day.state == CoreV4ConsentState.GRANTED && day.syncedVersion == day.version &&
                    status.state == CoreV4ConsentState.GRANTED && status.syncedVersion == status.version &&
                    store.latestPlanReceiptVersion(initialPlan.planId, scenario.dayPlanGranted.version) == initialPlan.version &&
                    store.freshness(CoreV4Category.DAY_PLAN).startsWith(FRESH_PREFIX)
            }
            val initialTrace = CoreV4Diagnostics.snapshot()
            assertTrue("DAY_PLAN grant must have an APPLIED ACK trace", hasAppliedConsentAck(
                initialTrace, CoreV4Category.DAY_PLAN, scenario.dayPlanGranted.version,
            ))
            assertTrue("ACTIVITY_STATUS grant must have an APPLIED ACK trace", hasAppliedConsentAck(
                initialTrace, CoreV4Category.ACTIVITY_STATUS, scenario.statusGranted.version,
            ))
            assertTrue("DayPlan receipt must have an APPLIED trace", hasAppliedPlanAck(
                initialTrace, initialPlan.version, scenario.dayPlanGranted.version,
            ))
            val presentation = store.presentation()
            Log.i(
                LOG_TAG,
                "step=05 result=OBSERVED presentation=${presentation.code} pending=${presentation.pendingCount}",
            )
            assertEquals("Core V4 presentation should be synchronized", SYNCED_CODE, presentation.code)
            Log.i(LOG_TAG, "step=05 result=PASS consent_acks=2 applied=true")
            Log.i(LOG_TAG, "step=06 result=PASS approved_day_plan_receipt=true")
            Log.i(LOG_TAG, "step=07 result=PASS plan_receipt_version=${initialPlan.version} freshness=FRESH")

            assertNoDuplicatePass(context, store, initialPlan, scenario, "08")

            instrumentation.runOnMainSync { relaySettings.disconnect() }
            assertEquals(RelayConnectionState.OFFLINE, relaySettings.state)
            scenario.dayPlanRevoked = store.setConsent(CoreV4Category.DAY_PLAN, granted = false).snapshot
            assertEquals(CoreV4ConsentState.REVOKED, scenario.dayPlanRevoked.state)
            assertEquals(scenario.dayPlanGranted.version + 1, scenario.dayPlanRevoked.version)
            assertTrue("offline revocation must remain pending", scenario.dayPlanRevoked.syncedVersion < scenario.dayPlanRevoked.version)
            CoreV4SyncScheduler.schedule(context)
            waitUntil(
                description = "offline revocation attempt without a fabricated ACK",
                timeoutMillis = OFFLINE_TIMEOUT_MILLIS,
                diagnostic = {
                    "day=${consentDiagnostic(store.consent(CoreV4Category.DAY_PLAN).snapshot)}; " +
                        "offline_trace=${hasOfflineConsentAttempt(CoreV4Diagnostics.snapshot(), scenario.dayPlanRevoked)}"
                },
            ) {
                hasOfflineConsentAttempt(CoreV4Diagnostics.snapshot(), scenario.dayPlanRevoked)
            }
            val offlineDay = store.consent(CoreV4Category.DAY_PLAN).snapshot
            assertEquals(scenario.dayPlanRevoked.version, offlineDay.version)
            assertTrue("offline work must not advance syncedVersion", offlineDay.syncedVersion < offlineDay.version)
            assertFalse("offline revocation must not receive a fictitious APPLIED ACK", hasAppliedConsentAck(
                CoreV4Diagnostics.snapshot(), CoreV4Category.DAY_PLAN, scenario.dayPlanRevoked.version,
            ))
            Log.i(LOG_TAG, "step=09 result=PASS disconnected=true revoke_pending=true")
            Log.i(LOG_TAG, "step=10 result=PASS offline_ack=false synced_version=${offlineDay.syncedVersion}")

            instrumentation.runOnMainSync { relaySettings.connect() }
            waitUntil(
                description = "relay reconnection after offline revocation",
                timeoutMillis = RELAY_TIMEOUT_MILLIS,
                diagnostic = { "state=${relaySettings.state}" },
            ) { relaySettings.state == RelayConnectionState.RELAY_CONNECTED }
            CoreV4SyncScheduler.schedule(context)
            waitUntil(
                description = "offline revocation reconciliation",
                timeoutMillis = SYNC_TIMEOUT_MILLIS,
                diagnostic = {
                    "day=${consentDiagnostic(store.consent(CoreV4Category.DAY_PLAN).snapshot)}; relay=${relaySettings.state}"
                },
            ) {
                val day = store.consent(CoreV4Category.DAY_PLAN).snapshot
                day.state == CoreV4ConsentState.REVOKED && day.version == scenario.dayPlanRevoked.version &&
                    day.syncedVersion == day.version
            }
            assertTrue("reconnected revocation must receive an APPLIED ACK", hasAppliedConsentAck(
                CoreV4Diagnostics.snapshot(), CoreV4Category.DAY_PLAN, scenario.dayPlanRevoked.version,
            ))
            assertEquals("the original approved plan receipt remains associated with grant v2",
                initialPlan.version,
                store.latestPlanReceiptVersion(initialPlan.planId, scenario.dayPlanGranted.version),
            )
            Log.i(LOG_TAG, "step=11 result=PASS relay_reconnected=true")
            Log.i(LOG_TAG, "step=12 result=PASS revocation_reconciled=true")

            val finalDay = store.consent(CoreV4Category.DAY_PLAN).snapshot
            assertEquals(CoreV4ConsentState.REVOKED, finalDay.state)
            assertEquals(scenario.dayPlanRevoked.version, finalDay.version)
            assertEquals(finalDay.version, finalDay.syncedVersion)
            val finalStatus = store.consent(CoreV4Category.ACTIVITY_STATUS).snapshot
            assertEquals(CoreV4ConsentState.GRANTED, finalStatus.state)
            assertEquals(finalStatus.version, finalStatus.syncedVersion)
            Log.i(LOG_TAG, "step=13 result=PASS final_day_plan=REVOKED:${finalDay.syncedVersion}/${finalDay.version}")

            assertNoDuplicatePass(context, store, initialPlan, scenario, "14")
            val finalTrace = CoreV4Diagnostics.snapshot()
            Log.i(
                LOG_TAG,
                "vm1_result=PASS steps=14 plan_version=${initialPlan.version} " +
                    "day_grant=${scenario.dayPlanGranted.version} day_revoke=${finalDay.version} " +
                    "status_grant=${scenario.statusGranted.version} trace_events=${finalTrace.size} token_logged=false",
            )
        } catch (failure: Throwable) {
            scenarioFailure = failure
            Log.e(LOG_TAG, "vm1_result=FAIL first_failure=${failure.javaClass.simpleName}")
            throw failure
        } finally {
            try {
                instrumentation.runOnMainSync { relaySettings.disconnect() }
                cancelCoreV4WorkAndAwaitIdle(context)
                if (pairingFile.exists()) pairingFile.delete()
                CoreV4Diagnostics.disable()
            } catch (cleanupFailure: Throwable) {
                val original = scenarioFailure
                if (original == null) throw cleanupFailure
                original.addSuppressed(cleanupFailure)
            }
        }
    }

    private fun ensureSyntheticApprovedPlan(context: Context): DayPlan {
        val controller = PlanController(File(context.filesDir, PLANS_DIRECTORY))
        controller.refresh()
        if (controller.plan == null) assertTrue("today's synthetic plan should be created", controller.createTodayPlan())
        var plan = controller.plan ?: error("plan missing after refresh/create")
        if (plan.activities.isEmpty()) {
            assertTrue(
                "synthetic activity should be added",
                controller.addActivity(
                    title = SYNTHETIC_ACTIVITY_TITLE,
                    kind = ActivityKind.FOCUS,
                    startMinute = 600,
                    durationMinutes = 30,
                    priority = Priority.IMPORTANT,
                ),
            )
            assertTrue("synthetic activity should be explicitly approved", controller.confirmPreview())
            controller.refresh()
            plan = controller.plan ?: error("approved plan missing after confirmation")
        }
        assertEquals("plan must be for the local current date", controller.today, plan.localDate)
        assertEquals("only the synthetic fixture may be synchronized", 1, plan.activities.size)
        assertEquals(SYNTHETIC_ACTIVITY_TITLE, plan.activities.single().title)
        assertEquals(PlanState.APPROVED, plan.state)
        assertTrue("approved synthetic plan should be version 2 or newer", plan.version >= 2)
        return plan
    }

    private fun assertNoDuplicatePass(
        context: Context,
        store: AndroidCoreV4SyncStore,
        plan: DayPlan,
        scenario: VmScenario,
        step: String,
    ) {
        val beforeEvents = CoreV4Diagnostics.snapshot()
        val beforePass = beforeEvents.maxOfOrNull { it.workerOrdinal } ?: 0L
        val beforeDispatchCount = beforeEvents.count(::isBusinessDispatch)
        val dayBefore = store.consent(CoreV4Category.DAY_PLAN).snapshot
        val statusBefore = store.consent(CoreV4Category.ACTIVITY_STATUS).snapshot
        val receiptBefore = store.latestPlanReceiptVersion(plan.planId, scenario.dayPlanGranted.version)

        CoreV4SyncScheduler.schedule(context)
        waitUntil(
            description = "additional idempotent sync pass",
            timeoutMillis = PASS_TIMEOUT_MILLIS,
            diagnostic = { "baseline_worker=$beforePass; latest_worker=${CoreV4Diagnostics.snapshot().maxOfOrNull { it.workerOrdinal } ?: 0L}" },
        ) {
            CoreV4Diagnostics.snapshot().any { it.workerOrdinal > beforePass && it.stage == CoreV4Diagnostics.Stage.PASS_END }
        }

        val afterEvents = CoreV4Diagnostics.snapshot()
        assertEquals("an additional pass must not dispatch duplicate business requests", beforeDispatchCount,
            afterEvents.count(::isBusinessDispatch))
        assertEquals(dayBefore, store.consent(CoreV4Category.DAY_PLAN).snapshot)
        assertEquals(statusBefore, store.consent(CoreV4Category.ACTIVITY_STATUS).snapshot)
        assertEquals(receiptBefore, store.latestPlanReceiptVersion(plan.planId, scenario.dayPlanGranted.version))
        Log.i(LOG_TAG, "step=$step result=PASS extra_pass=true duplicate_dispatches=0")
    }

    private fun isBusinessDispatch(event: CoreV4Diagnostics.Event): Boolean = event.stage in setOf(
        CoreV4Diagnostics.Stage.CONSENT_DISPATCH,
        CoreV4Diagnostics.Stage.PLAN_DISPATCH,
        CoreV4Diagnostics.Stage.STATUS_DISPATCH,
    )

    private fun hasAppliedConsentAck(
        events: List<CoreV4Diagnostics.Event>,
        category: CoreV4Category,
        version: Int,
    ): Boolean = events.any {
        it.stage == CoreV4Diagnostics.Stage.ACK_RESULT &&
            it.category == category && it.consentVersion == version &&
            it.result == CoreV4Diagnostics.Result.APPLIED &&
            it.ackResult == CoreV4Diagnostics.AckResult.APPLIED
    }

    private fun hasAppliedPlanAck(
        events: List<CoreV4Diagnostics.Event>,
        planVersion: Int,
        consentVersion: Int,
    ): Boolean = events.any {
        it.stage == CoreV4Diagnostics.Stage.PLAN_ACK_RESULT &&
            it.category == CoreV4Category.DAY_PLAN && it.consentVersion == consentVersion &&
            it.result == CoreV4Diagnostics.Result.APPLIED &&
            it.ackResult == CoreV4Diagnostics.AckResult.APPLIED &&
            it.correlationTag != null && planVersion > 0
    }

    private fun hasOfflineConsentAttempt(
        events: List<CoreV4Diagnostics.Event>,
        revoked: CoreV4ConsentSnapshot,
    ): Boolean = events.any {
        it.stage == CoreV4Diagnostics.Stage.OFFLINE &&
            it.category == CoreV4Category.DAY_PLAN && it.consentVersion == revoked.version &&
            it.result == CoreV4Diagnostics.Result.OFFLINE
    }

    private fun syncDiagnostic(store: AndroidCoreV4SyncStore, plan: DayPlan, scenario: VmScenario): String =
        "relay_receipt=${store.latestPlanReceiptVersion(plan.planId, scenario.dayPlanGranted.version)}; " +
            "day=${consentDiagnostic(store.consent(CoreV4Category.DAY_PLAN).snapshot)}; " +
            "status=${consentDiagnostic(store.consent(CoreV4Category.ACTIVITY_STATUS).snapshot)}; " +
            "freshness=${store.freshness(CoreV4Category.DAY_PLAN).substringBefore(' ').take(24)}"

    private fun consentDiagnostic(snapshot: CoreV4ConsentSnapshot): String =
        "${snapshot.state}:${snapshot.syncedVersion}/${snapshot.version}"

    private fun waitUntil(
        description: String,
        timeoutMillis: Long,
        diagnostic: () -> String,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        }
        assertTrue("$description timed out: ${diagnostic().take(MAX_DIAGNOSTIC_CHARS)}", condition())
    }

    private fun cancelCoreV4WorkAndAwaitIdle(context: Context) {
        val manager = WorkManager.getInstance(context.applicationContext)
        val names = listOf(CoreV4SyncScheduler.IMMEDIATE_WORK, CoreV4SyncScheduler.PERIODIC_WORK)
        names.forEach { name ->
            manager.cancelUniqueWork(name).result.get(WORK_OPERATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        }
        val deadline = SystemClock.uptimeMillis() + CLEANUP_TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            val active = names.flatMap { name ->
                manager.getWorkInfosForUniqueWork(name).get(WORK_OPERATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            }.filter { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED }
            if (active.isEmpty()) return
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        }
        val remaining = names.flatMap { name ->
            manager.getWorkInfosForUniqueWork(name).get(WORK_OPERATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        }.count { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED }
        assertEquals("Core V4 work should stop during cleanup", 0, remaining)
    }

    private data class VmScenario(
        val plan: DayPlan,
        val initialDayConsent: CoreV4ConsentSnapshot,
        val initialStatusConsent: CoreV4ConsentSnapshot,
        var dayPlanGranted: CoreV4ConsentSnapshot = initialDayConsent,
        var statusGranted: CoreV4ConsentSnapshot = initialStatusConsent,
        var dayPlanRevoked: CoreV4ConsentSnapshot = initialDayConsent,
    )

    private companion object {
        const val LOG_TAG = "ATLAS_V7_06A_VM1"
        const val ARG_RELAY_URL = "relay_url"
        const val ARG_SERVER_ID = "server_id"
        const val ARG_SERVER_PUBLIC_KEY = "server_public_key"
        const val EXPECTED_RELAY_URL = "ws://10.0.2.2:4412"
        const val PHYSICAL_RELAY_URL = "ws://127.0.0.1:4412"
        const val EXPECTED_SERVER_ID = "atlas-pc-v7-06a-vm"
        const val PAIRING_FILE_NAME = "v7-06a-pairing.json"
        const val PLANS_DIRECTORY = "plans"
        const val SYNTHETIC_ACTIVITY_TITLE = "ATLAS V7-06A synthetic focus"
        const val SYNCED_CODE = "SYNCED"
        const val FRESH_PREFIX = "FRESH"
        const val RELAY_TIMEOUT_MILLIS = 60_000L
        const val SYNC_TIMEOUT_MILLIS = 60_000L
        const val OFFLINE_TIMEOUT_MILLIS = 20_000L
        const val PASS_TIMEOUT_MILLIS = 30_000L
        const val CLEANUP_TIMEOUT_MILLIS = 30_000L
        const val WORK_OPERATION_TIMEOUT_MILLIS = 20_000L
        const val POLL_INTERVAL_MILLIS = 250L
        const val MAX_DIAGNOSTIC_CHARS = 1_200
    }
}
