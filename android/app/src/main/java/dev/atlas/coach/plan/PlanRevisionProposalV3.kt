package dev.atlas.coach.plan

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class PlanRevisionAgentResponseV3(
    val schemaVersion: Int,
    val reply: String,
    val agentStatus: String,
    val proposal: PlanRevisionProposalEnvelopeV3?,
    val overlaps: List<Pair<String, String>>,
)

data class PlanRevisionProposalEnvelopeV3(
    val proposalId: String,
    val idempotencyKey: String,
    val conversationId: String?,
    val mandateId: String,
    val mandateVersion: Int,
    val createdAt: Instant,
    val expiresAt: Instant,
    val parameters: PlanRevisionParametersV3,
)

data class PlanRevisionParametersV3(
    val planId: String,
    val baseVersion: Int,
    val localDate: String,
    val timezone: String,
    val summary: String,
    val activities: List<PlanRevisionActivityV3>,
)

data class PlanRevisionActivityV3(
    val clientRef: String,
    val activityId: String?,
    val kind: ActivityKind,
    val title: String,
    val localDate: String,
    val startMinute: Int,
    val durationMinutes: Int,
    val timezone: String,
    val priority: Priority,
    val goalId: String?,
    val subGoalId: String?,
    val notes: String?,
)

class PlanRevisionProposalParseException(
    val code: String,
    message: String,
) : IllegalArgumentException(message)

/** Strict, bounded parser for the untrusted L3-01a V3 response envelope. */
object PlanRevisionProposalV3 {
    const val MAX_RESPONSE_BYTES = 256 * 1024
    const val MAX_ACTIVITIES = 32
    const val MAX_PLAN_MINUTES = 1440
    val MAX_TTL: Duration = Duration.ofHours(24)

    fun parse(raw: String): PlanRevisionAgentResponseV3 {
        try {
            val root = StrictJsonV3(raw).parse() as? Map<*, *> ?: invalid("response must be an object")
            root.requireKeys("schema_version", "reply", "agent_status", "proposal", "overlaps")
            val schemaVersion = root.requiredInt("schema_version")
            if (schemaVersion != 3) invalid("unsupported response schema")
            val reply = root.requiredString("reply")
            if (reply.length > 4_000) invalid("reply is too long")
            val agentStatus = root.requiredString("agent_status")
            if (agentStatus !in AGENT_STATUSES) invalid("unknown agent status")
            val proposalValue = root["proposal"]
            val proposal = if (proposalValue == null) null else parseEnvelope(
                proposalValue as? Map<*, *> ?: invalid("proposal must be an object or null"),
            )
            val overlaps = root.requiredArray("overlaps").map { pairValue ->
                val pair = pairValue as? List<*> ?: invalid("overlap must be an array")
                if (pair.size != 2) invalid("overlap must contain two references")
                (pair[0] as? String ?: invalid("overlap reference must be a string")) to
                    (pair[1] as? String ?: invalid("overlap reference must be a string"))
            }
            return PlanRevisionAgentResponseV3(schemaVersion, reply, agentStatus, proposal, overlaps)
        } catch (error: PlanRevisionProposalParseException) {
            throw error
        } catch (_: Exception) {
            throw PlanRevisionProposalParseException(INVALID_PROPOSAL, "Invalid V3 plan revision response")
        }
    }

    private fun parseEnvelope(root: Map<*, *>): PlanRevisionProposalEnvelopeV3 {
        root.requireKeys(
            "schema_version", "proposal_id", "idempotency_key", "conversation_id", "mandate_id",
            "mandate_version", "created_at", "expires_at", "required_level", "state", "proposal",
        )
        if (root.requiredInt("schema_version") != 3) invalid("unsupported proposal schema")
        val proposalId = root.requiredString("proposal_id").also { canonicalUuid(it, "proposal_id") }
        val idempotencyKey = root.requiredString("idempotency_key")
        if (idempotencyKey.isEmpty() || idempotencyKey.length > 80) invalid("invalid idempotency key")
        val conversationId = root.nullableString("conversation_id")
        if (conversationId != null && conversationId.length > 80) invalid("invalid conversation id")
        val mandateId = root.requiredString("mandate_id")
        if (mandateId.isEmpty() || mandateId.length > 80) invalid("invalid mandate id")
        val mandateVersion = root.requiredInt("mandate_version")
        if (mandateVersion < 1) invalid("invalid mandate version")
        if (root.requiredString("required_level") != "confirm") invalid("proposal requires confirmation")
        if (root.requiredString("state") != "proposed") invalid("proposal is not proposed")
        val createdAt = parseInstant(root.requiredString("created_at"))
        val expiresAt = parseInstant(root.requiredString("expires_at"))
        val ttl = Duration.between(createdAt, expiresAt)
        if (ttl.isNegative || ttl.isZero || ttl > MAX_TTL) invalid("invalid proposal expiration")
        val candidate = root.requiredObject("proposal")
        candidate.requireKeys("action_type", "reason", "parameters")
        if (candidate.requiredString("action_type") != "plan.revise_day") invalid("unsupported action type")
        val reason = candidate.requiredString("reason")
        requirePlainText(reason, 1, 240, "reason")
        val parameters = parseParameters(candidate.requiredObject("parameters"))
        return PlanRevisionProposalEnvelopeV3(
            proposalId = proposalId,
            idempotencyKey = idempotencyKey,
            conversationId = conversationId,
            mandateId = mandateId,
            mandateVersion = mandateVersion,
            createdAt = createdAt,
            expiresAt = expiresAt,
            parameters = parameters,
        )
    }

    private fun parseParameters(root: Map<*, *>): PlanRevisionParametersV3 {
        root.requireKeys("plan_id", "base_version", "local_date", "timezone", "summary", "activities")
        val planId = root.requiredString("plan_id").also { canonicalUuid(it, "plan_id") }
        val baseVersion = root.requiredInt("base_version")
        if (baseVersion < 1) invalid("invalid base version")
        val localDate = root.requiredString("local_date").also(::canonicalDate)
        val timezone = root.requiredString("timezone").also(::canonicalTimezone)
        val summary = root.requiredString("summary")
        requirePlainText(summary, 1, 240, "summary")
        val rawActivities = root.requiredArray("activities")
        if (rawActivities.size > MAX_ACTIVITIES) invalid("too many activities")
        val activities = rawActivities.map { value ->
            parseActivity(value as? Map<*, *> ?: invalid("activity must be an object"))
        }
        if (activities.map { it.clientRef }.toSet().size != activities.size) invalid("duplicate client_ref")
        val activityIds = activities.mapNotNull { it.activityId }
        if (activityIds.toSet().size != activityIds.size) invalid("duplicate activity_id")
        if (activities.any { it.localDate != localDate || it.timezone != timezone }) {
            invalid("activity date or timezone differs from the plan")
        }
        if (activities.sumOf { it.durationMinutes } > MAX_PLAN_MINUTES) invalid("activity total exceeds one day")
        return PlanRevisionParametersV3(planId, baseVersion, localDate, timezone, summary, activities)
    }

    private fun parseActivity(root: Map<*, *>): PlanRevisionActivityV3 {
        root.requireKeys(
            "client_ref", "activity_id", "kind", "title", "local_date", "start_minute",
            "duration_minutes", "timezone", "priority", "goal_id", "sub_goal_id", "training_ref",
            "focus", "notes",
        )
        val clientRef = root.requiredString("client_ref")
        if (clientRef.length !in 1..40 || !CLIENT_REF.matches(clientRef)) invalid("invalid client_ref")
        val activityId = root.nullableString("activity_id")?.also { canonicalUuid(it, "activity_id") }
        val kind = enumValue<ActivityKind>(root.requiredString("kind"), "kind")
        val title = root.requiredString("title")
        requirePlainText(title, 1, 80, "title")
        val localDate = root.requiredString("local_date").also(::canonicalDate)
        val startMinute = root.requiredInt("start_minute")
        val durationMinutes = root.requiredInt("duration_minutes")
        if (startMinute !in 0..1439 || durationMinutes !in 1..1440 || startMinute + durationMinutes > 1440) {
            invalid("activity is outside the local day")
        }
        val timezone = root.requiredString("timezone").also(::canonicalTimezone)
        val priority = enumValue<Priority>(root.requiredString("priority"), "priority")
        val goalId = root.nullableString("goal_id")?.also { canonicalUuid(it, "goal_id") }
        val subGoalId = root.nullableString("sub_goal_id")?.also { canonicalUuid(it, "sub_goal_id") }
        if (root["training_ref"] != null || root["focus"] != null) {
            throw PlanRevisionProposalParseException(
                UNSUPPORTED_PROPOSAL_FIELD,
                "focus and training_ref are not supported for this plan revision",
            )
        }
        val notes = root.nullableString("notes")?.also { requirePlainText(it, 0, 280, "notes") }
        return PlanRevisionActivityV3(
            clientRef, activityId, kind, title, localDate, startMinute, durationMinutes,
            timezone, priority, goalId, subGoalId, notes,
        )
    }

    private fun parseInstant(value: String): Instant {
        val instant = try {
            Instant.parse(value).also { it.toEpochMilli() }
        } catch (_: Exception) {
            invalid("timestamp must be an ISO-8601 UTC instant")
        }
        if (!value.endsWith("Z")) invalid("timestamp must be UTC")
        return instant
    }

    private fun canonicalDate(value: String) {
        val date = try {
            LocalDate.parse(value)
        } catch (_: Exception) {
            invalid("date must be ISO-8601")
        }
        if (date.toString() != value) invalid("date must be canonical ISO-8601")
    }

    private fun canonicalTimezone(value: String) {
        if (value !in ZoneId.getAvailableZoneIds()) invalid("timezone must be an IANA zone")
        val zone = try {
            ZoneId.of(value)
        } catch (_: Exception) {
            invalid("timezone must be an IANA zone")
        }
        if (zone.id != value) invalid("timezone must be canonical")
    }

    private fun canonicalUuid(value: String, field: String) {
        val canonical = try {
            UUID.fromString(value).toString()
        } catch (_: Exception) {
            invalid("$field must be a canonical UUID")
        }
        if (value != canonical) invalid("$field must be a canonical UUID")
    }

    private fun requirePlainText(value: String, min: Int, max: Int, field: String) {
        if (value.length !in min..max || (min > 0 && value.isBlank()) || value.any { it.code < 0x20 }) {
            invalid("invalid $field")
        }
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String, field: String): T =
        enumValues<T>().firstOrNull { it.name == value } ?: invalid("unknown $field")

    private fun invalid(message: String): Nothing =
        throw PlanRevisionProposalParseException(INVALID_PROPOSAL, message)

    private fun Map<*, *>.requireKeys(vararg required: String) {
        if (keys.any { it !is String } || keys != required.toSet()) invalid("missing or unknown JSON field")
    }

    private fun Map<*, *>.requiredString(key: String): String = this[key] as? String
        ?: invalid("$key must be a string")

    private fun Map<*, *>.nullableString(key: String): String? = when (val value = this[key]) {
        null -> null
        is String -> value
        else -> invalid("$key must be a string or null")
    }

    private fun Map<*, *>.requiredInt(key: String): Int {
        val value = this[key] as? Long ?: invalid("$key must be an integer")
        if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) invalid("$key is out of range")
        return value.toInt()
    }

    private fun Map<*, *>.requiredObject(key: String): Map<*, *> = this[key] as? Map<*, *>
        ?: invalid("$key must be an object")

    private fun Map<*, *>.requiredArray(key: String): List<*> = this[key] as? List<*>
        ?: invalid("$key must be an array")

    private const val INVALID_PROPOSAL = "INVALID_PROPOSAL"
    const val UNSUPPORTED_PROPOSAL_FIELD = "UNSUPPORTED_PROPOSAL_FIELD"
    private val AGENT_STATUSES = setOf("ok", "unavailable", "invalid_output", "quota_limited")
    private val CLIENT_REF = Regex("^[a-zA-Z0-9_.-]+$")
}

/** Minimal RFC 8259 parser that rejects duplicate keys, lenient values, and trailing input. */
private class StrictJsonV3(private val source: String) {
    private var index = 0

    fun parse(): Any? {
        if (source.length > PlanRevisionProposalV3.MAX_RESPONSE_BYTES) fail()
        val value = value(0)
        whitespace()
        if (index != source.length) fail()
        return value
    }

    private fun value(depth: Int): Any? {
        if (depth > MAX_DEPTH) fail()
        whitespace()
        return when (peek()) {
            '{' -> objectValue(depth + 1)
            '[' -> arrayValue(depth + 1)
            '"' -> stringValue()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            '-', in '0'..'9' -> numberValue()
            else -> fail()
        }
    }

    private fun objectValue(depth: Int): Map<String, Any?> {
        consume('{')
        whitespace()
        val result = linkedMapOf<String, Any?>()
        if (take('}')) return result
        while (true) {
            whitespace()
            if (peek() != '"') fail()
            val key = stringValue()
            if (result.containsKey(key)) fail()
            whitespace()
            consume(':')
            result[key] = value(depth)
            whitespace()
            if (take('}')) return result
            consume(',')
        }
    }

    private fun arrayValue(depth: Int): List<Any?> {
        consume('[')
        whitespace()
        val result = mutableListOf<Any?>()
        if (take(']')) return result
        while (true) {
            result += value(depth)
            whitespace()
            if (take(']')) return result
            consume(',')
        }
    }

    private fun stringValue(): String {
        consume('"')
        val result = StringBuilder()
        while (index < source.length) {
            val character = source[index++]
            when {
                character == '"' -> return result.toString().also(::validateSurrogates)
                character == '\\' -> {
                    if (index >= source.length) fail()
                    when (val escaped = source[index++]) {
                        '"', '\\', '/' -> result.append(escaped)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000c')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> result.append(unicodeEscape())
                        else -> fail()
                    }
                }
                character.code < 0x20 -> fail()
                else -> result.append(character)
            }
        }
        fail()
    }

    private fun unicodeEscape(): Char {
        if (index + 4 > source.length) fail()
        val text = source.substring(index, index + 4)
        if (text.any { it.digitToIntOrNull(16) == null }) fail()
        index += 4
        return text.toInt(16).toChar()
    }

    private fun numberValue(): Any {
        val start = index
        take('-')
        when {
            take('0') -> if (peek() in '0'..'9') fail()
            peek() in '1'..'9' -> while (peek() in '0'..'9') index++
            else -> fail()
        }
        var integral = true
        if (take('.')) {
            integral = false
            if (peek() !in '0'..'9') fail()
            while (peek() in '0'..'9') index++
        }
        if (peek() == 'e' || peek() == 'E') {
            integral = false
            index++
            if (peek() == '+' || peek() == '-') index++
            if (peek() !in '0'..'9') fail()
            while (peek() in '0'..'9') index++
        }
        val text = source.substring(start, index)
        if (integral) return text.toLongOrNull() ?: fail()
        val value = text.toDoubleOrNull() ?: fail()
        if (!value.isFinite()) fail()
        return value
    }

    private fun <T> literal(expected: String, result: T): T {
        if (!source.startsWith(expected, index)) fail()
        index += expected.length
        return result
    }

    private fun validateSurrogates(value: String) {
        var cursor = 0
        while (cursor < value.length) {
            val current = value[cursor]
            when {
                current.isHighSurrogate() -> {
                    if (cursor + 1 >= value.length || !value[cursor + 1].isLowSurrogate()) fail()
                    cursor += 2
                }
                current.isLowSurrogate() -> fail()
                else -> cursor++
            }
        }
    }

    private fun whitespace() {
        while (peek() == ' ' || peek() == '\n' || peek() == '\r' || peek() == '\t') index++
    }

    private fun consume(expected: Char) {
        if (!take(expected)) fail()
    }

    private fun take(expected: Char): Boolean {
        if (peek() != expected) return false
        index++
        return true
    }

    private fun peek(): Char = source.getOrNull(index) ?: '\u0000'

    private fun fail(): Nothing = throw IllegalArgumentException("invalid strict JSON")

    companion object { private const val MAX_DEPTH = 32 }
}
