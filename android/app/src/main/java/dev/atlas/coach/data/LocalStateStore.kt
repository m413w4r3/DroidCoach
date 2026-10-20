package dev.atlas.coach.data

import org.json.JSONArray
import org.json.JSONObject

/** Minimal boundary so persistence can be exercised without Android framework classes. */
interface StateBackend {
    fun read(): String?
    fun write(value: String)

    /** Checkpoints may use the backend's asynchronous path; ordinary transitions stay durable. */
    fun writeCheckpoint(value: String) = write(value)

    /** Must preserve the raw value under a distinct key before the active state is replaced. */
    fun quarantine(rawValue: String, reason: String): Boolean = false
}

sealed interface StateReadResult {
    data object Missing : StateReadResult
    data class Loaded(val state: Map<String, Any?>, val rawJson: String) : StateReadResult
    data class Corrupt(val rawJson: String?, val reason: String) : StateReadResult
}

/** Stores one versioned JSON document in a private SharedPreferences value. */
class LocalStateStore(private val backend: StateBackend) {
    fun readDocument(): StateReadResult {
        val json = try {
            backend.read()
        } catch (error: Exception) {
            return StateReadResult.Corrupt(null, "impossible de lire le stockage local")
        } ?: return StateReadResult.Missing
        return try {
            val root = JSONObject(json)
            val values = root.toKotlinMap()
            val version = (values["schemaVersion"] as? Number)?.toInt()
            if (version != CURRENT_SCHEMA_VERSION) {
                StateReadResult.Corrupt(json, "version de schéma inconnue${version?.let { " ($it)" } ?: ""}")
            } else {
                StateReadResult.Loaded(values, json)
            }
        } catch (_: Exception) {
            StateReadResult.Corrupt(json, "JSON local invalide")
        }
    }

    /** Compatibility helper for simple round-trip callers; production uses [readDocument]. */
    fun read(): Map<String, Any?> = (readDocument() as? StateReadResult.Loaded)?.state.orEmpty()

    fun quarantine(rawValue: String?, reason: String): Boolean =
        rawValue?.let { backend.quarantine(it, reason) } ?: false

    fun write(state: Map<String, Any?>, durable: Boolean = true) {
        val value = try {
            val version = if (state.containsKey("schemaVersion")) {
                (state["schemaVersion"] as? Number)?.toInt()
                    ?: throw IllegalArgumentException("schemaVersion must be a number")
            } else {
                CURRENT_SCHEMA_VERSION
            }
            require(version == CURRENT_SCHEMA_VERSION) { "Cannot write unsupported state schema $version" }
            JSONObject((state + ("schemaVersion" to version)).toJsonMap()).toString()
        } catch (_: IllegalArgumentException) {
            throw PersistWriteException(PersistFailureKind.INVALID_STATE)
        } catch (_: Exception) {
            throw PersistWriteException(PersistFailureKind.SERIALIZATION)
        }
        try {
            if (durable) backend.write(value) else backend.writeCheckpoint(value)
        } catch (_: Exception) {
            throw PersistWriteException(PersistFailureKind.STORAGE_IO)
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

private fun Map<*, *>.toJsonMap(): Map<String, Any?> = entries.associate { (key, value) ->
    require(key is String) { "JSON object keys must be strings" }
    key to value.toJsonValue()
}

private fun Any?.toJsonValue(): Any = when (this) {
    null -> JSONObject.NULL
    JSONObject.NULL -> JSONObject.NULL
    is String, is Boolean, is Int, is Long, is Double, is Float -> this
    is Byte, is Short -> toInt()
    is Number -> toDouble()
    is Enum<*> -> name
    is Map<*, *> -> JSONObject(toJsonMap())
    is Iterable<*> -> JSONArray().also { array -> forEach { array.put(it.toJsonValue()) } }
    is Array<*> -> JSONArray().also { array -> forEach { array.put(it.toJsonValue()) } }
    else -> toString()
}

private fun JSONObject.toKotlinMap(): Map<String, Any?> = buildMap {
    val keys = keys()
    while (keys.hasNext()) {
        val key = keys.next()
        put(key, this@toKotlinMap.get(key).toKotlinValue())
    }
}

private fun Any?.toKotlinValue(): Any? = when (this) {
    null, JSONObject.NULL -> null
    is JSONObject -> toKotlinMap()
    is JSONArray -> (0 until length()).map { get(it).toKotlinValue() }
    else -> this
}
