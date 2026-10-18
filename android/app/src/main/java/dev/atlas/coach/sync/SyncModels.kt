package dev.atlas.coach.sync

import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

const val ATLAS_CONTRACT_VERSION = 1

enum class SyncDataCategory(val wireValue: String) {
    FOCUS("focus"),
    HABIT("habit"),
    CONTENT("content"),
    COACH_INTERACTION("coach_interaction"),
    APP_USAGE_SUMMARY("app_usage_summary"),
    APP_USAGE_DETAIL("app_usage_detail"),
}

enum class DataScope { LOCAL_ONLY, SHAREABLE }

/** Explicit opt-in. Empty categories and private application details are disabled by default. */
data class SyncConsent(
    val enabledCategories: Set<SyncDataCategory> = emptySet(),
    val sharePrivateAppDetails: Boolean = false,
) {
    fun allows(event: SyncEvent): Boolean =
        event.dataScope == DataScope.SHAREABLE &&
            event.category in enabledCategories &&
            (event.category != SyncDataCategory.APP_USAGE_DETAIL || sharePrivateAppDetails)

    companion object {
        val DISABLED = SyncConsent()
    }
}

enum class SyncEventType(val wireValue: String) {
    FOCUS_STARTED("focus_started"),
    FOCUS_PAUSED("focus_paused"),
    FOCUS_COMPLETED("focus_completed"),
    HABIT_CHECKIN("habit_checkin"),
    DIRECTIVE_RESPONDED("directive_responded"),
    CONTENT_OPENED("content_opened"),
}

/** The UUID is created once and persisted in the outbox before its first network attempt. */
data class SyncEvent(
    val clientEventId: String = UUID.randomUUID().toString(),
    val eventType: SyncEventType,
    val occurredAtUtcMillis: Long,
    val category: SyncDataCategory,
    val dataScope: DataScope = DataScope.LOCAL_ONLY,
    val payload: Map<String, Any?> = emptyMap(),
) {
    init {
        require(UUID.fromString(clientEventId).toString() == clientEventId) {
            "clientEventId must be a UUID"
        }
        require(payload.size <= 20) { "event payload is limited to 20 fields" }
        payload.forEach { (key, value) ->
            require(key.isNotBlank() && key.length <= 80 && key.none { it.code < 32 })
            require(value == null || value is String || value is Boolean || value is Int ||
                value is Long || value is Double || value is Float)
            if (value is String) require(value.length <= 1000)
            if (value is Double) require(value.isFinite())
            if (value is Float) require(value.isFinite())
        }
    }
}

data class CoachDirective(
    val id: String,
    val type: String,
    val title: String,
    val reason: String,
    val choices: List<String>,
    val createdAtUtcMillis: Long,
    val expiresAtUtcMillis: Long,
)

data class CoachResponse(
    val directiveId: String,
    val choice: String,
    val message: String,
    val actionExecuted: Boolean,
)

interface CoachGateway {
    fun currentDirective(): CoachDirective

    /** Remote implementations require COACH_INTERACTION sync consent before sending the choice. */
    fun respond(directiveId: String, choice: String): CoachResponse
}

interface SyncGateway {
    fun syncNow(): SyncResult
}

enum class SyncStatus {
    LOCAL_ONLY,
    NOTHING_TO_SYNC,
    SUCCESS,
    AUTH_REQUIRED,
    RETRY_SCHEDULED,
    CONTRACT_ERROR,
    PROTOCOL_ERROR,
    REJECTED,
}

data class SyncResult(
    val status: SyncStatus,
    val acknowledged: Int = 0,
    val pending: Int = 0,
    val retryAtUtcMillis: Long? = null,
    val message: String? = null,
)

fun interface BearerTokenProvider {
    /** Return a token from user-configured storage. Implementations must never log the value. */
    fun token(): String?
}

fun interface UtcClock {
    fun nowMillis(): Long
}

internal fun SyncEvent.toStoredJson(): JSONObject = JSONObject()
    .put("client_event_id", clientEventId)
    .put("event_type", eventType.wireValue)
    .put("occurred_at_utc_ms", occurredAtUtcMillis)
    .put("category", category.wireValue)
    .put("data_scope", dataScope.name)
    .put("payload", payload.toJsonObject())

internal fun syncEventFromStoredJson(value: JSONObject): SyncEvent = SyncEvent(
    clientEventId = value.getString("client_event_id"),
    eventType = SyncEventType.entries.first { it.wireValue == value.getString("event_type") },
    occurredAtUtcMillis = value.getLong("occurred_at_utc_ms"),
    category = SyncDataCategory.entries.first { it.wireValue == value.getString("category") },
    dataScope = DataScope.valueOf(value.getString("data_scope")),
    payload = value.getJSONObject("payload").toScalarMap(),
)

internal fun Map<String, Any?>.toJsonObject(): JSONObject = JSONObject().also { result ->
    forEach { (key, value) -> result.put(key, value ?: JSONObject.NULL) }
}

internal fun JSONObject.toScalarMap(): Map<String, Any?> = buildMap {
    val names = keys()
    while (names.hasNext()) {
        val name = names.next()
        val value = get(name)
        put(name, if (value == JSONObject.NULL) null else value)
    }
}

internal fun isoUtc(epochMillis: Long): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMillis))
