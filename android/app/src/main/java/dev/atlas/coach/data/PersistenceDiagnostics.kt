package dev.atlas.coach.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class PersistFailureEvent(
    val operation: String,
    val reason: PersistFailureKind,
    val occurredAtUtcMillis: Long,
)

/** Storage seam keeps diagnostics testable without a Context or the main state document. */
interface PersistenceDiagnosticsStore {
    fun read(): String?
    fun write(value: String): Boolean
}

class PersistenceDiagnostics(private val store: PersistenceDiagnosticsStore) {
    private var events: List<PersistFailureEvent> = runCatching { decode(store.read()) }.getOrDefault(emptyList())

    @Synchronized
    fun record(operation: String, reason: PersistFailureKind, occurredAtUtcMillis: Long) {
        val safeOperation = operation.takeIf { it in SAFE_OPERATIONS } ?: "unknown"
        events = (events + PersistFailureEvent(safeOperation, reason, occurredAtUtcMillis.coerceAtLeast(0L)))
            .takeLast(MAX_EVENTS)
        // The in-memory entry remains available if the diagnostic file itself is unwritable.
        runCatching { store.write(encode(events)) }
    }

    @Synchronized
    fun recent(): List<PersistFailureEvent> = events.toList()

    private fun encode(values: List<PersistFailureEvent>): String = JSONArray().also { array ->
        values.forEach { event ->
            array.put(JSONObject()
                .put("operation", event.operation)
                .put("reason", event.reason.name)
                .put("at", event.occurredAtUtcMillis))
        }
    }.toString()

    private fun decode(raw: String?): List<PersistFailureEvent> {
        if (raw == null) return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val operation = item.optString("operation").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val reason = runCatching { PersistFailureKind.valueOf(item.optString("reason")) }.getOrNull()
                ?: return@mapNotNull null
            val at = item.optLong("at", -1L).takeIf { it >= 0L } ?: return@mapNotNull null
            PersistFailureEvent(operation, reason, at)
        }.takeLast(MAX_EVENTS)
    }

    companion object {
        const val MAX_EVENTS = 20
        private val SAFE_OPERATIONS = setOf(
            "state",
            "goals.write",
            "goals.action",
            "agent.consent_dates",
            "agent.shared_context",
            "agent.chat_message",
            "agent.inbox",
            "agent.disable",
            "agent.enable",
            "agent.suspend",
            "agent.resume",
            "agent.revoke",
            "agent.category_consent",
            "agent.location_consent",
            "agent.mandate",
            "agent.focus_window",
            "agent.revocation_barrier",
            "proactive.state",
        )
    }
}

class AndroidPersistenceDiagnosticsStore(context: Context) : PersistenceDiagnosticsStore {
    private val preferences = context.applicationContext
        .getSharedPreferences("atlas_persistence_diagnostics", Context.MODE_PRIVATE)

    override fun read(): String? = preferences.getString(KEY_EVENTS, null)

    override fun write(value: String): Boolean = preferences.edit().putString(KEY_EVENTS, value).commit()

    private companion object {
        const val KEY_EVENTS = "recent_failures_v1"
    }
}
