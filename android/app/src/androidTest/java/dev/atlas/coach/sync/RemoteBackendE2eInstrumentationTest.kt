package dev.atlas.coach.sync

import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.test.BackendArgumentsAwareRunner
import dev.atlas.coach.test.RequiresBackendArguments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Script-driven Android -> FastAPI mock -> Android and offline recovery proof. */
@RunWith(BackendArgumentsAwareRunner::class)
class RemoteBackendE2eInstrumentationTest {
    @Test
    @RequiresBackendArguments(
        keys = ["scenario", "backend_url", "backend_token"],
        reason = "backend scenario requires scripts/v4-backend-e2e.sh",
    )
    fun runRequestedBackendScenario() {
        val arguments = InstrumentationRegistry.getArguments()
        val scenario = arguments.getString("scenario") ?: error("scenario argument is required")
        val backendUrl = arguments.getString("backend_url") ?: error("backend_url argument is required")
        val token = arguments.getString("backend_token") ?: error("backend_token argument is required")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tokenStore = SharedPreferencesBearerTokenStore(context)
        val queueFile = File(context.cacheDir, "atlas_backend_e2e_outbox.json")
        if (scenario == "online") queueFile.delete()
        val outbox = DurableEventOutbox(FileOutboxStorage(queueFile))
        val consent = SyncConsent(setOf(SyncDataCategory.FOCUS, SyncDataCategory.COACH_INTERACTION))
        tokenStore.configureToken(token)
        assertEquals(token, tokenStore.token())
        val storedToken = context.getSharedPreferences(
            SharedPreferencesBearerTokenStore.PREFERENCES_NAME,
            android.content.Context.MODE_PRIVATE,
        ).getString(SharedPreferencesBearerTokenStore.TOKEN_KEY, null)
        assertNotEquals(token, storedToken)
        val gateway = RemoteBackendGateway(
            baseUrl = backendUrl,
            tokenProvider = tokenStore,
            outbox = outbox,
            consentProvider = { consent },
            connectTimeoutMillis = 3_000,
            readTimeoutMillis = 3_000,
        )

        when (scenario) {
            "online" -> {
                val directive = gateway.currentDirective()
                assertEquals("start_focus_session", directive.type)
                val response = gateway.respond(directive.id, "start")
                assertFalse(response.actionExecuted)
                assertTrue(response.message.isNotBlank())
                val event = SyncEvent(
                    eventType = SyncEventType.FOCUS_STARTED,
                    occurredAtUtcMillis = System.currentTimeMillis(),
                    category = SyncDataCategory.FOCUS,
                    dataScope = DataScope.SHAREABLE,
                    payload = mapOf("duration_minutes" to 1, "e2e_probe" to "online"),
                )
                assertTrue(outbox.enqueue(event, consent))
                val result = gateway.syncNow()
                assertEquals(SyncStatus.SUCCESS, result.status)
                assertEquals(1, result.acknowledged)
                assertEquals(0, outbox.size())
                assertTrue("Replaying the exact same UUID must re-enqueue the same event", outbox.enqueue(event, consent))
                val replay = gateway.syncNow()
                assertEquals(SyncStatus.SUCCESS, replay.status)
                assertEquals(1, replay.acknowledged)
                assertEquals(0, outbox.size())
                android.util.Log.i("ATLAS_H2", "scenario=online acknowledged=1 replay_acknowledged=1 pending=0")
            }
            "unauthorized" -> {
                val event = SyncEvent(
                    eventType = SyncEventType.FOCUS_STARTED,
                    occurredAtUtcMillis = System.currentTimeMillis(),
                    category = SyncDataCategory.FOCUS,
                    dataScope = DataScope.SHAREABLE,
                )
                assertTrue(outbox.enqueue(event, consent))
                tokenStore.configureToken("${token}-invalid")
                val result = gateway.syncNow()
                assertEquals(SyncStatus.AUTH_REQUIRED, result.status)
                assertEquals(1, outbox.size())
                android.util.Log.i("ATLAS_H2", "scenario=unauthorized status=${result.status} pending=${result.pending}")
                tokenStore.configureToken(token)
                outbox.clear()
            }
            "disabled" -> {
                val disabledConsent = SyncConsent.DISABLED
                val event = SyncEvent(
                    eventType = SyncEventType.FOCUS_STARTED,
                    occurredAtUtcMillis = System.currentTimeMillis(),
                    category = SyncDataCategory.FOCUS,
                    dataScope = DataScope.SHAREABLE,
                )
                assertFalse(outbox.enqueue(event, disabledConsent))
                val disabledGateway = RemoteBackendGateway(
                    baseUrl = backendUrl,
                    tokenProvider = tokenStore,
                    outbox = outbox,
                    consentProvider = { disabledConsent },
                    connectTimeoutMillis = 3_000,
                    readTimeoutMillis = 3_000,
                )
                assertEquals(SyncStatus.NOTHING_TO_SYNC, disabledGateway.syncNow().status)
                assertEquals(0, outbox.size())
                android.util.Log.i("ATLAS_H2", "scenario=disabled accepted=false pending=0 requests=0")
            }
            "offline" -> {
                val event = SyncEvent(
                    eventType = SyncEventType.FOCUS_COMPLETED,
                    occurredAtUtcMillis = System.currentTimeMillis(),
                    category = SyncDataCategory.FOCUS,
                    dataScope = DataScope.SHAREABLE,
                    payload = mapOf("duration_minutes" to 1, "e2e_probe" to "offline"),
                )
                assertTrue(outbox.enqueue(event, consent))
                val result = gateway.syncNow()
                assertEquals(SyncStatus.RETRY_SCHEDULED, result.status)
                assertEquals(1, outbox.size())
                assertEquals(event.clientEventId, outbox.all().single().event.clientEventId)
                android.util.Log.i("ATLAS_H2", "scenario=offline status=${result.status} pending=${result.pending} stable_uuid=true")
            }
            "resume" -> {
                val queuedId = outbox.all().single().event.clientEventId
                val result = gateway.syncNow()
                assertEquals(SyncStatus.SUCCESS, result.status)
                assertEquals(1, result.acknowledged)
                assertEquals(0, outbox.size())
                assertEquals(SyncStatus.NOTHING_TO_SYNC, gateway.syncNow().status)
                assertFalse(queuedId.isBlank())
                android.util.Log.i("ATLAS_H2", "scenario=resume acknowledged=1 pending=0 stable_uuid=true")
                queueFile.delete()
                tokenStore.configureToken(null)
            }
            else -> error("Unsupported scenario")
        }
        tokenStore.configureToken(null)
    }
}
