package dev.atlas.coach.training

import java.net.SocketTimeoutException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportPlanClientTest {
    @Test
    fun sendsBearerWithBoundedTimeoutsAndReturnsPlanAndLastSync() {
        val transport = StubTransport(SportPlanHttpResponse(200, SportPlanFixture.json.toByteArray()))

        val state = SportPlanClient(transport).fetch(
            baseUrl = "http://127.0.0.1:8000/",
            bearerToken = "local-token",
            localDate = "2026-10-26",
            timezone = "Europe/Paris",
        )

        assertEquals("Bearer local-token", transport.request?.authorization)
        assertFalse(transport.request.toString().contains("local-token"))
        assertTrue(transport.request!!.url.contains("/v1/sport/plan?local_date=2026-10-26&timezone=Europe%2FParis"))
        assertEquals(SportPlanClient.DEFAULT_TIMEOUT_MILLIS, transport.request!!.connectTimeoutMillis)
        assertEquals(SportPlanClient.DEFAULT_TIMEOUT_MILLIS, transport.request!!.readTimeoutMillis)
        assertEquals(SportPlanClient.MAX_RESPONSE_BYTES, transport.request!!.maxResponseBytes)
        assertEquals(SyncState.FRESH, state.syncState)
        assertEquals("2026-10-26T15:00:00Z", state.lastSyncAt)
        assertEquals("56", state.plan?.exercises?.single()?.exerciseId)
    }

    @Test
    fun parsesBackendSyncStateAndRejectsDateOrTimezoneMismatch() {
        val staleJson = JSONObject(SportPlanFixture.json).put("sync_state", "STALE").toString()
        val stale = SportPlanClient(StubTransport(SportPlanHttpResponse(200, staleJson.toByteArray())))
            .fetch("https://atlas.example", "token", "2026-10-26", "Europe/Paris")
        val wrongDateJson = JSONObject(SportPlanFixture.json).put("local_date", "2026-10-27").toString()
        val mismatch = SportPlanClient(StubTransport(SportPlanHttpResponse(200, wrongDateJson.toByteArray())))
            .fetch("https://atlas.example", "token", "2026-10-26", "Europe/Paris")

        assertEquals(SyncState.STALE, stale.syncState)
        assertEquals(SyncState.MISMATCH, mismatch.syncState)
        assertNull(mismatch.plan)
    }

    @Test
    fun mapsHttpErrorsTimeoutAndInvalidOrOversizedResponses() {
        val rateLimited = fetchWith(SportPlanHttpResponse(429, ByteArray(0)))
        val forbidden = fetchWith(SportPlanHttpResponse(403, ByteArray(0)))
        val invalid = fetchWith(SportPlanHttpResponse(200, "not json".toByteArray()))
        val oversized = SportPlanClient(
            transport = StubTransport(SportPlanHttpResponse(200, SportPlanFixture.json.toByteArray())),
            maxResponseBytes = 32,
        ).fetch("https://atlas.example", "token", "2026-10-26", "Europe/Paris")
        val timeout = SportPlanClient(object : SportPlanTransport {
            override fun get(request: SportPlanHttpRequest): SportPlanHttpResponse {
                throw SocketTimeoutException()
            }
        }).fetch("https://atlas.example", "token", "2026-10-26", "Europe/Paris")

        assertEquals(SyncState.STALE, rateLimited.syncState)
        assertEquals(SyncState.UNAVAILABLE, forbidden.syncState)
        assertEquals(SyncState.MISMATCH, invalid.syncState)
        assertEquals(SyncState.MISMATCH, oversized.syncState)
        assertEquals(SyncState.STALE, timeout.syncState)
    }

    @Test
    fun invalidRequestConfigurationReturnsUnavailableWithoutTransportCall() {
        val transport = StubTransport(SportPlanHttpResponse(200, SportPlanFixture.json.toByteArray()))

        val state = SportPlanClient(transport).fetch(
            baseUrl = "https://user:password@atlas.example",
            bearerToken = "token",
            localDate = "not-a-date",
            timezone = "Europe/Paris",
        )

        assertEquals(SyncState.UNAVAILABLE, state.syncState)
        assertNull(transport.request)
    }

    private fun fetchWith(response: SportPlanHttpResponse): SportPlanUiState =
        SportPlanClient(StubTransport(response)).fetch(
            "https://atlas.example",
            "token",
            "2026-10-26",
            "Europe/Paris",
        )

    private class StubTransport(
        private val response: SportPlanHttpResponse,
    ) : SportPlanTransport {
        var request: SportPlanHttpRequest? = null

        override fun get(request: SportPlanHttpRequest): SportPlanHttpResponse {
            this.request = request
            return response
        }
    }
}
