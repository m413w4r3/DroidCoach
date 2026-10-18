package dev.atlas.coach.sync

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

private val VALID_COACH_CHOICES = setOf("start", "discuss", "alternative", "postpone", "pause")

/** Local deterministic coach. It does not make network requests or execute Android actions. */
class LocalMockCoachGateway(
    private val clock: UtcClock = UtcClock(System::currentTimeMillis),
) : CoachGateway {
    private val responses = mutableMapOf<Pair<String, String>, CoachResponse>()

    override fun currentDirective(): CoachDirective {
        val now = clock.nowMillis()
        return CoachDirective(
            id = "local-demo-focus-v1",
            type = "start_focus_session",
            title = "Avancer 10 minutes sur une tâche",
            reason = "Une courte session est une prochaine étape simple.",
            choices = listOf("start", "discuss", "alternative", "postpone", "pause"),
            createdAtUtcMillis = now,
            expiresAtUtcMillis = now + 24 * 60 * 60 * 1_000L,
        )
    }

    @Synchronized
    override fun respond(directiveId: String, choice: String): CoachResponse {
        require(choice in VALID_COACH_CHOICES) { "Choix de directive inconnu" }
        return responses.getOrPut(directiveId to choice) {
            CoachResponse(
                directiveId = directiveId,
                choice = choice,
                message = when (choice) {
                    "start" -> "Demande de focus prête à être prise en charge localement."
                    "discuss" -> "La directive est ouverte à la discussion."
                    "alternative" -> "Une alternative courte est proposée."
                    "postpone" -> "La demande est reportée localement."
                    else -> "Le coach est en pause. Vous pouvez le réactiver quand vous le souhaitez."
                },
                actionExecuted = false,
            )
        }
    }
}

class LocalMockSyncGateway : SyncGateway {
    override fun syncNow(): SyncResult = SyncResult(
        status = SyncStatus.LOCAL_ONLY,
        message = "Le mode LocalMock ne contacte aucun serveur.",
    )
}

class RemoteBackendGateway(
    baseUrl: String,
    private val tokenProvider: BearerTokenProvider,
    private val outbox: EventOutbox,
    private val consentProvider: () -> SyncConsent,
    private val clock: UtcClock = UtcClock(System::currentTimeMillis),
    private val backoff: ExponentialBackoff = ExponentialBackoff(),
    private val connectTimeoutMillis: Int = 4_000,
    private val readTimeoutMillis: Int = 4_000,
) : CoachGateway, SyncGateway {
    private val base = baseUrl.trimEnd('/')

    init {
        val protocol = URL(base).protocol
        require(protocol == "https" || protocol == "http") { "Backend URL must use HTTP or HTTPS" }
        require(connectTimeoutMillis > 0 && readTimeoutMillis > 0)
    }

    override fun currentDirective(): CoachDirective {
        val response = request("GET", "/v1/directives/current")
        require(response.optInt("schema_version") == ATLAS_CONTRACT_VERSION) {
            "Unsupported directive schema version"
        }
        val choicesJson = response.getJSONArray("choices")
        val created = Instant.parse(response.getString("created_at")).toEpochMilli()
        val expires = Instant.parse(response.getString("expires_at")).toEpochMilli()
        require(expires > created) { "Backend returned an invalid directive time range" }
        return CoachDirective(
            id = response.getString("directive_id"),
            type = response.getString("type"),
            title = response.getString("title"),
            reason = response.getString("reason"),
            choices = (0 until choicesJson.length()).map { choicesJson.getString(it) },
            createdAtUtcMillis = created,
            expiresAtUtcMillis = expires,
        )
    }

    override fun respond(directiveId: String, choice: String): CoachResponse {
        require(choice in VALID_COACH_CHOICES) { "Choix de directive inconnu" }
        if (!consentProvider().enabledCategories.contains(SyncDataCategory.COACH_INTERACTION)) {
            throw SyncConsentRequiredException("Le partage des réponses du coach est désactivé.")
        }
        val result = request(
            method = "POST",
            path = "/v1/directives/${encodePathSegment(directiveId)}/respond",
            body = JSONObject().put("choice", choice),
        )
        require(result.getString("directive_id") == directiveId && result.getString("choice") == choice) {
            "Backend acknowledgement does not match the coach response"
        }
        return CoachResponse(
            directiveId = directiveId,
            choice = choice,
            message = result.getString("message"),
            actionExecuted = result.optBoolean("action_executed", true),
        ).also { require(!it.actionExecuted) { "Backend attempted to report an executed action" } }
    }

    override fun syncNow(): SyncResult {
        val consent = consentProvider()
        outbox.discardDisallowed(consent)
        val queued = outbox.pending(clock.nowMillis()).take(MAX_BATCH_SIZE)
        if (queued.isEmpty()) return SyncResult(SyncStatus.NOTHING_TO_SYNC, pending = outbox.size())

        val token = tokenProvider.token()?.trim()?.takeIf { it.isNotEmpty() }
        if (token == null) {
            return scheduleRetry(queued, SyncStatus.AUTH_REQUIRED, "Jeton Bearer non configuré.")
        }

        return try {
            val request = JSONObject()
                .put("contract_version", ATLAS_CONTRACT_VERSION)
                .put("events", JSONArray().also { events -> queued.forEach { events.put(it.event.toWireJson()) } })
            val response = request("POST", "/v1/sync", body = request, bearerToken = token)
            require(response.optInt("contract_version") == ATLAS_CONTRACT_VERSION) {
                "Unsupported sync contract version"
            }
            require(response.optString("status") == "ok") { "Backend did not acknowledge sync" }
            val results = response.optJSONArray("results") ?: error("Missing sync results")
            require(results.length() == queued.size) { "Sync acknowledgement count mismatch" }
            val sentIds = queued.map { it.event.clientEventId }.toSet()
            val acknowledged = buildSet {
                for (index in 0 until results.length()) {
                    val item = results.getJSONObject(index)
                    require(item.optString("status") == "accepted") { "Event was not accepted" }
                    add(item.getString("event_id"))
                }
            }
            require(acknowledged == sentIds) { "Sync acknowledgement IDs do not match the outbox" }
            outbox.acknowledge(acknowledged)
            SyncResult(
                status = SyncStatus.SUCCESS,
                acknowledged = acknowledged.size,
                pending = outbox.size(),
            )
        } catch (error: BackendHttpFailure) {
            when {
                error.statusCode == 401 -> scheduleRetry(queued, SyncStatus.AUTH_REQUIRED, "Backend refused Bearer authentication.")
                error.statusCode >= 500 || error.statusCode == 408 || error.statusCode == 429 ->
                    scheduleRetry(queued, SyncStatus.RETRY_SCHEDULED, "Backend temporarily unavailable.")
                error.statusCode in 400..499 -> SyncResult(
                    SyncStatus.CONTRACT_ERROR,
                    pending = outbox.size(),
                    message = "Backend rejected sync (HTTP ${error.statusCode}, ${error.errorCode ?: "request_rejected"}).",
                )
                else -> SyncResult(SyncStatus.PROTOCOL_ERROR, pending = outbox.size(), message = "Unexpected backend response.")
            }
        } catch (_: IOException) {
            scheduleRetry(queued, SyncStatus.RETRY_SCHEDULED, "Backend unavailable; events remain in the local outbox.")
        } catch (_: Exception) {
            SyncResult(SyncStatus.PROTOCOL_ERROR, pending = outbox.size(), message = "Backend response did not match contract v1.")
        }
    }

    private fun scheduleRetry(events: List<QueuedEvent>, status: SyncStatus, message: String): SyncResult {
        val now = clock.nowMillis()
        var earliest: Long? = null
        events.forEach { queued ->
            val next = now + backoff.delayMillis(queued.attempts + 1)
            outbox.defer(queued.event.clientEventId, next)
            if (earliest == null || next < earliest!!) earliest = next
        }
        return SyncResult(status, pending = outbox.size(), retryAtUtcMillis = earliest, message = message)
    }

    private fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        bearerToken: String? = tokenProvider.token()?.trim()?.takeIf { it.isNotEmpty() },
    ): JSONObject {
        if (bearerToken == null) throw BackendHttpFailure(401, "authentication_required")
        val connection = URL(base + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.setRequestProperty("Authorization", "Bearer $bearerToken")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val error = runCatching { JSONObject(responseText).optJSONObject("error") }.getOrNull()
                throw BackendHttpFailure(status, error?.optString("code"))
            }
            return JSONObject(responseText)
        } finally {
            connection.disconnect()
        }
    }

    private fun SyncEvent.toWireJson(): JSONObject = JSONObject()
        .put("event_id", clientEventId)
        .put("event_type", eventType.wireValue)
        .put("occurred_at", isoUtc(occurredAtUtcMillis))
        .put("payload", payload.toJsonObject())

    private fun encodePathSegment(value: String): String = java.net.URLEncoder
        .encode(value, Charsets.UTF_8.name())
        .replace("+", "%20")

    private class BackendHttpFailure(val statusCode: Int, val errorCode: String?) : IOException()

    companion object {
        private const val MAX_BATCH_SIZE = 100
    }
}

class SyncConsentRequiredException(message: String) : IllegalStateException(message)
