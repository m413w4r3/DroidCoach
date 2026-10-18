package dev.atlas.coach.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Script-driven Android -> FastAPI mock -> Android and offline recovery proof. */
@RunWith(AndroidJUnit4::class)
class RemoteBackendE2eInstrumentationTest {
    @Test
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
            }
            "resume" -> {
                val queuedId = outbox.all().single().event.clientEventId
                val result = gateway.syncNow()
                assertEquals(SyncStatus.SUCCESS, result.status)
                assertEquals(1, result.acknowledged)
                assertEquals(0, outbox.size())
                assertEquals(SyncStatus.NOTHING_TO_SYNC, gateway.syncNow().status)
                assertFalse(queuedId.isBlank())
                queueFile.delete()
                tokenStore.configureToken(null)
            }
            else -> error("Unsupported scenario")
        }
    }
}
