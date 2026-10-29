package dev.atlas.coach.sync.agent.inbox.v4

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.math.BigDecimal

object InboxV4ProposalParser {
    private val proposalFields = setOf(
        "contract_version", "schema_version", "proposal_id", "capability", "idempotency_key",
        "proposal_hash", "created_at", "expires_at", "state", "plan_id", "base_version",
        "origin", "justification", "changes", "diff", "conflicts", "confirmation_mode",
        "auto_application",
    )

    fun parse(raw: String): InboxV4ProposalParseResult {
        val root = try {
            JSONObject(raw)
        } catch (error: JSONException) {
            return InboxV4ProposalParseResult.Rejected(raw, "Malformed JSON")
        }
        val schema = root.intOrNull("schema_version")
            ?: return InboxV4ProposalParseResult.Rejected(raw, "schema_version must be an integer")
        if (schema != SCHEMA_VERSION) {
            return InboxV4ProposalParseResult.Quarantined(raw, "Unsupported schema_version: $schema")
        }
        val contract = root.intOrNull("contract_version")
            ?: return InboxV4ProposalParseResult.Rejected(raw, "contract_version must be an integer")
        if (contract != CONTRACT_VERSION) {
            return InboxV4ProposalParseResult.Quarantined(raw, "Unsupported contract_version: $contract")
        }
        return try {
            root.requireAllowedFields(proposalFields)
            root.requireFields(proposalFields)

            val state = enumValue<InboxV4ProposalState>(root.requiredString("state"), "state")
            val confirmation = enumValue<InboxV4ConfirmationMode>(
                root.requiredString("confirmation_mode"), "confirmation_mode",
            )
            val createdAt = root.requiredInstant("created_at")
            val expiresAt = root.requiredInstant("expires_at")
            if (!expiresAt.isAfter(createdAt)) throw InboxV4FormatException("expires_at must be after created_at")

            InboxV4ProposalParseResult.Parsed(InboxV4Proposal(
                contractVersion = contract,
                schemaVersion = schema,
                proposalId = root.requiredUuid("proposal_id"),
                capability = root.requiredString("capability").also {
                    if (it != PROPOSAL_CAPABILITY) throw InboxV4FormatException("Unsupported capability")
                },
                idempotencyKey = root.requiredUuid("idempotency_key"),
                proposalHash = root.requiredString("proposal_hash").also {
                    if (!SHA256_PATTERN.matches(it)) throw InboxV4FormatException("Invalid proposal_hash")
                },
                createdAt = createdAt,
                expiresAt = expiresAt,
                state = state,
                planId = root.requiredUuid("plan_id"),
                baseVersion = root.requiredLong("base_version").also {
                    if (it < 1L) throw InboxV4FormatException("base_version must be positive")
                },
                origin = root.requiredString("origin").also {
                    if (it != PROPOSAL_ORIGIN) throw InboxV4FormatException("Unsupported origin")
                },
                justification = root.requiredString("justification").also {
                    if (it.isBlank() || it.length > 280) throw InboxV4FormatException("Invalid justification")
                },
                changes = root.requiredArray("changes").let(::parseChanges),
                diff = root.requiredArray("diff").let(::parseDiff),
                conflicts = root.requiredArray("conflicts").let(::parseConflicts),
                confirmationMode = confirmation,
                autoApplication = root.requiredString("auto_application").also {
                    if (it != NO_CORE_AUTO_APPLICATION) throw InboxV4FormatException("Unsupported auto_application")
                },
            ))
        } catch (error: InboxV4UnknownFieldException) {
            InboxV4ProposalParseResult.Quarantined(raw, error.message ?: "Unknown field")
        } catch (error: Exception) {
            InboxV4ProposalParseResult.Rejected(raw, error.message ?: "Invalid proposal")
        }
    }
}

object InboxV4AckParser {
    private val requiredFields = setOf(
        "contract_version", "schema_version", "ack_id", "capability", "proposal_id", "sequence",
        "state", "result_code", "occurred_at", "consent_version",
    )
    private val allowedFields = requiredFields + setOf(
        "plan_id", "applied_plan_version", "revision_id", "scope_hash", "effect_ids",
    )
    private val proofFields = setOf("plan_id", "applied_plan_version", "revision_id", "scope_hash", "effect_ids")

    fun parse(raw: String): InboxV4Ack {
        val root = try {
            JSONObject(raw)
        } catch (error: JSONException) {
            throw InboxV4FormatException("Malformed ACK JSON", error)
        }
        val schema = root.requiredLong("schema_version")
        if (schema != SCHEMA_VERSION.toLong()) throw InboxV4FormatException("Unsupported ACK schema_version: $schema")
        val contract = root.requiredLong("contract_version")
        if (contract != CONTRACT_VERSION.toLong()) throw InboxV4FormatException("Unsupported ACK contract_version: $contract")
        root.requireAllowedFields(allowedFields)
        root.requireFields(requiredFields)
        val proofPresence = proofFields.map(root::has)
        if (proofPresence.any { it } && !proofPresence.all { it }) {
            throw InboxV4FormatException("Application proof fields must be present together")
        }
        val proof = if (proofPresence.all { it }) {
            val effects = root.requiredArray("effect_ids").let { array ->
                if (array.length() !in 1..MAX_EFFECT_IDS) throw InboxV4FormatException("Invalid effect_ids size")
                (0 until array.length()).map { index ->
                    val value = array.get(index) as? String
                        ?: throw InboxV4FormatException("effect_ids entries must be strings")
                    if (!EFFECT_ID_PATTERN.matches(value)) {
                        throw InboxV4FormatException("Invalid effect_ids entry")
                    }
                    value
                }.also { if (it.distinct().size != it.size) throw InboxV4FormatException("effect_ids must be unique") }
            }
            InboxV4ApplicationProof(
                planId = root.requiredUuid("plan_id"),
                appliedPlanVersion = root.requiredLong("applied_plan_version"),
                revisionId = root.requiredUuid("revision_id"),
                scopeHash = root.requiredString("scope_hash"),
                effectIds = effects,
            )
        } else null

        return try {
            InboxV4Ack(
                contractVersion = contract.toInt(),
                schemaVersion = schema.toInt(),
                ackId = root.requiredUuid("ack_id"),
                capability = root.requiredString("capability").also {
                    if (it != ACK_CAPABILITY) throw InboxV4FormatException("Unsupported ACK capability")
                },
                proposalId = root.requiredUuid("proposal_id"),
                sequence = root.requiredLong("sequence"),
                state = enumValue(root.requiredString("state"), "state"),
                resultCode = enumValue(root.requiredString("result_code"), "result_code"),
                occurredAt = root.requiredInstant("occurred_at"),
                consentVersion = root.requiredLong("consent_version"),
                proof = proof,
            )
        } catch (error: InboxV4FormatException) {
            throw error
        } catch (error: Exception) {
            throw InboxV4FormatException(error.message ?: "Invalid ACK", error)
        }
    }
}

private fun parseChanges(array: JSONArray): List<Map<String, Any?>> {
    if (array.length() !in 1..MAX_CHANGES) throw InboxV4FormatException("changes must contain 1..$MAX_CHANGES items")
    return (0 until array.length()).map { index ->
        val item = array.getJSONObject(index)
        when (item.requiredString("op")) {
            "reschedule" -> {
                item.requireAllowedFields(setOf("op", "activity_id", "target_start_minute", "duration_minutes"))
                item.requireFields(setOf("op", "activity_id", "target_start_minute"))
                item.requiredUuid("activity_id")
                item.requiredInt("target_start_minute").also { requireRange(it, 0, 1439, "target_start_minute") }
                if (item.has("duration_minutes")) {
                    requireRange(item.requiredInt("duration_minutes"), 1, 1440, "duration_minutes")
                }
            }
            "create_activity" -> {
                val allowed = setOf(
                    "op", "client_ref", "kind", "title", "start_minute", "duration_minutes", "priority", "goal_id",
                )
                item.requireAllowedFields(allowed)
                item.requireFields(allowed - "goal_id")
                requireTextPattern(item.requiredString("client_ref"), CLIENT_REF_PATTERN, 40, "client_ref")
                requireEnum(item.requiredString("kind"), setOf("FOCUS", "ADMIN", "MEAL", "REST", "BUFFER", "OTHER"), "kind")
                requireNonBlank(item.requiredString("title"), 80, "title")
                requireRange(item.requiredInt("start_minute"), 0, 1439, "start_minute")
                requireRange(item.requiredInt("duration_minutes"), 1, 1440, "duration_minutes")
                requireEnum(item.requiredString("priority"), setOf("REQUIRED", "IMPORTANT", "OPTIONAL"), "priority")
                if (item.has("goal_id") && item.get("goal_id") != JSONObject.NULL) item.requiredUuid("goal_id")
            }
            "edit_activity" -> {
                val allowed = setOf("op", "activity_id", "title", "priority", "estimate_minutes")
                item.requireAllowedFields(allowed)
                item.requireFields(setOf("op", "activity_id"))
                item.requiredUuid("activity_id")
                val editFields = allowed - setOf("op", "activity_id")
                if (editFields.none(item::has)) throw InboxV4FormatException("edit_activity has no changes")
                if (item.has("title")) requireNonBlank(item.requiredString("title"), 80, "title")
                if (item.has("priority")) {
                    requireEnum(item.requiredString("priority"), setOf("REQUIRED", "IMPORTANT", "OPTIONAL"), "priority")
                }
                if (item.has("estimate_minutes")) {
                    requireRange(item.requiredInt("estimate_minutes"), 1, 480, "estimate_minutes")
                }
            }
            else -> throw InboxV4FormatException("Unknown changes op at index $index")
        }
        item.toKotlinMap()
    }
}

private fun parseDiff(array: JSONArray): List<Map<String, Any?>> {
    if (array.length() > MAX_CHANGES) throw InboxV4FormatException("diff exceeds $MAX_CHANGES items")
    return (0 until array.length()).map { index ->
        val item = array.getJSONObject(index)
        item.requireAllowedFields(setOf("op", "activity_id", "client_ref", "before", "after"))
        item.requireFields(setOf("op", "activity_id", "client_ref", "before", "after"))
        requireEnum(item.requiredString("op"), setOf("reschedule", "create_activity", "edit_activity"), "diff op")
        nullableUuid(item, "activity_id")
        nullableText(item, "client_ref", 40)
        val beforeValue = item.get("before")
        if (beforeValue != JSONObject.NULL) validateDiffValues(beforeValue as? JSONObject
            ?: throw InboxV4FormatException("diff.before must be an object or null"), "before")
        validateDiffValues(item.getJSONObject("after"), "after")
        item.toKotlinMap()
    }
}

private fun validateDiffValues(values: JSONObject, field: String) {
    val allowed = setOf("start_minute", "duration_minutes", "title", "priority")
    values.requireAllowedFields(allowed)
    if (values.has("start_minute")) requireRange(values.requiredInt("start_minute"), 0, 1439, "$field.start_minute")
    if (values.has("duration_minutes")) requireRange(values.requiredInt("duration_minutes"), 1, 1440, "$field.duration_minutes")
    if (values.has("title")) requireNonBlank(values.requiredString("title"), 80, "$field.title")
    if (values.has("priority")) {
        requireEnum(values.requiredString("priority"), setOf("REQUIRED", "IMPORTANT", "OPTIONAL"), "$field.priority")
    }
}

private fun parseConflicts(array: JSONArray): List<String> {
    if (array.length() > MAX_CONFLICTS) throw InboxV4FormatException("conflicts exceeds $MAX_CONFLICTS items")
    return (0 until array.length()).map { index ->
        val value = array.get(index) as? String ?: throw InboxV4FormatException("conflicts[$index] must be a string")
        if (value.isBlank() || value.length > 120) throw InboxV4FormatException("Invalid conflicts[$index]")
        value
    }
}

private fun JSONObject.requireAllowedFields(allowed: Set<String>) {
    val unknown = keys().asSequence().filterNot(allowed::contains).toList()
    if (unknown.isNotEmpty()) throw InboxV4UnknownFieldException("Unknown field(s): ${unknown.sorted().joinToString()}")
}

private fun JSONObject.requireFields(required: Set<String>) {
    val missing = required.filterNot(::has)
    if (missing.isNotEmpty()) throw InboxV4FormatException("Missing field(s): ${missing.sorted().joinToString()}")
}

private fun JSONObject.requiredString(key: String): String = get(key) as? String
    ?: throw InboxV4FormatException("$key must be a string")

private fun JSONObject.requiredUuid(key: String): String = requiredString(key).also {
    if (!isCanonicalUuid(it)) throw InboxV4FormatException("$key must be a canonical UUID")
}

private fun JSONObject.requiredInstant(key: String): Instant {
    val value = requiredString(key)
    if (value.length !in 20..40 || !value.endsWith('Z')) throw InboxV4FormatException("$key must be a UTC timestamp")
    return try {
        Instant.parse(value)
    } catch (error: Exception) {
        throw InboxV4FormatException("$key is not a valid timestamp", error)
    }
}

private fun JSONObject.requiredArray(key: String): JSONArray = get(key) as? JSONArray
    ?: throw InboxV4FormatException("$key must be an array")

private fun JSONObject.requiredLong(key: String): Long = requireIntegralNumber(get(key), key)

private fun JSONObject.requiredInt(key: String): Int {
    val value = requiredLong(key)
    if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) throw InboxV4FormatException("$key is outside integer range")
    return value.toInt()
}

private fun JSONObject.intOrNull(key: String): Int? = if (!has(key)) null else runCatching {
    val number = requiredLong(key)
    if (number !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) null else number.toInt()
}.getOrNull()

private fun JSONObject.toKotlinMap(): Map<String, Any?> = keys().asSequence().associateWith { key ->
    when (val value = get(key)) {
        JSONObject.NULL -> null
        is JSONObject -> value.toKotlinMap()
        is JSONArray -> (0 until value.length()).map { index ->
            when (val nested = value.get(index)) {
                JSONObject.NULL -> null
                is JSONObject -> nested.toKotlinMap()
                else -> nested
            }
        }
        else -> value
    }
}

private fun nullableUuid(json: JSONObject, key: String) {
    if (!json.has(key)) throw InboxV4FormatException("Missing field: $key")
    if (json.get(key) != JSONObject.NULL) json.requiredUuid(key)
}

private fun nullableText(json: JSONObject, key: String, maxLength: Int) {
    if (!json.has(key)) throw InboxV4FormatException("Missing field: $key")
    if (json.get(key) != JSONObject.NULL) {
        val value = json.requiredString(key)
        if (value.isBlank() || value.length > maxLength) throw InboxV4FormatException("Invalid $key")
    }
}

private fun requireIntegralNumber(value: Any?, field: String): Long {
    val number = value as? Number ?: throw InboxV4FormatException("$field must be an integer")
    return try {
        when (number) {
            is Byte, is Short, is Int, is Long -> number.toLong()
            else -> BigDecimal(number.toString()).longValueExact()
        }
    } catch (error: Exception) {
        throw InboxV4FormatException("$field must be an integer", error)
    }
}

private fun requireRange(value: Int, min: Int, max: Int, field: String) {
    if (value !in min..max) throw InboxV4FormatException("$field must be between $min and $max")
}

private fun requireNonBlank(value: String, maxLength: Int, field: String) {
    if (value.isBlank() || value.length > maxLength) throw InboxV4FormatException("Invalid $field")
}

private fun requireTextPattern(value: String, pattern: Regex, maxLength: Int, field: String) {
    if (value.length !in 1..maxLength || !pattern.matches(value)) throw InboxV4FormatException("Invalid $field")
}

private fun requireEnum(value: String, values: Set<String>, field: String) {
    if (value !in values) throw InboxV4FormatException("Unknown $field")
}

private inline fun <reified T : Enum<T>> enumValue(value: String, field: String): T =
    enumValues<T>().firstOrNull { it.name == value } ?: throw InboxV4FormatException("Unknown $field")

private fun JSONArray.getJSONObject(index: Int): JSONObject = get(index) as? JSONObject
    ?: throw InboxV4FormatException("Array item $index must be an object")

private val CLIENT_REF_PATTERN = Regex("^[A-Za-z0-9_-]+$")
