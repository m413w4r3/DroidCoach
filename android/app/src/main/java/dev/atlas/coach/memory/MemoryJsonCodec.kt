package dev.atlas.coach.memory

import java.time.Instant

/** String-only boundary for a future atomic local persistence adapter. */
interface MemoryBackend {
    fun read(): String?
    fun write(value: String)
}

/** Versioned JSON representation for local memory facts. */
class MemoryJsonCodec {
    fun encode(facts: Collection<MemoryFact>): String {
        val ids = HashSet<String>()
        facts.forEach { fact ->
            require(ids.add(fact.factId)) { "Duplicate fact id: ${fact.factId}" }
            require(fact.category != MemoryCategory.HYPOTHESIS || fact.expiresAt != null) {
                "A stored hypothesis must expire"
            }
        }

        return buildString {
            append("{\"schema_version\":$SCHEMA_VERSION,\"facts\":[")
            facts.forEachIndexed { index, fact ->
                if (index > 0) append(',')
                appendFact(fact)
            }
            append("]}")
        }
    }

    fun decode(json: String): List<MemoryFact> {
        val root = JsonReader(json).readDocument().asObject("root")
        val version = root.requiredLong("schema_version")
        require(version == SCHEMA_VERSION.toLong()) {
            "Unsupported memory schema version: $version"
        }
        val encodedFacts = root["facts"] as? List<*>
            ?: throw IllegalArgumentException("facts must be an array")
        val ids = HashSet<String>()
        return encodedFacts.mapIndexed { index, value ->
            val fact = value.asObject("facts[$index]").toFact()
            require(ids.add(fact.factId)) { "Duplicate fact id: ${fact.factId}" }
            require(fact.category != MemoryCategory.HYPOTHESIS || fact.expiresAt != null) {
                "A stored hypothesis must expire"
            }
            fact
        }
    }

    private fun StringBuilder.appendFact(fact: MemoryFact) {
        append('{')
        append("\"fact_id\":").appendQuoted(fact.factId).append(',')
        append("\"category\":").appendQuoted(fact.category.name).append(',')
        append("\"statement\":").appendQuoted(fact.statement).append(',')
        append("\"provenance\":{")
        append("\"source\":").appendQuoted(fact.provenance.source).append(',')
        append("\"source_id\":")
        if (fact.provenance.sourceId == null) append("null")
        else appendQuoted(fact.provenance.sourceId)
        append(',')
        append("\"observed_at\":").appendQuoted(fact.provenance.observedAt.toString()).append(',')
        append("\"confidence\":").append(fact.provenance.confidence.toString()).append(',')
        append("\"user_confirmed\":").append(fact.provenance.userConfirmed).append("},")
        append("\"sensitivity\":").appendQuoted(fact.sensitivity.name).append(',')
        append("\"created_at\":").appendQuoted(fact.createdAt.toString()).append(',')
        append("\"expires_at\":")
        if (fact.expiresAt == null) append("null") else appendQuoted(fact.expiresAt.toString())
        append(',')
        append("\"last_used_at\":")
        if (fact.lastUsedAt == null) append("null") else appendQuoted(fact.lastUsedAt.toString())
        append(',')
        append("\"revoked\":").append(fact.revoked).append('}')
    }

    private fun StringBuilder.appendQuoted(value: String): StringBuilder {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char < ' ') {
                    append("\\u").append(char.code.toString(16).padStart(4, '0'))
                } else {
                    append(char)
                }
            }
        }
        return append('"')
    }

    private fun Map<String, Any?>.toFact(): MemoryFact {
        val encodedProvenance = this["provenance"].asObject("provenance")
        return MemoryFact(
            factId = requiredString("fact_id"),
            category = enumValue("category", MemoryCategory.values()),
            statement = requiredString("statement"),
            provenance = MemoryProvenance(
                source = encodedProvenance.requiredString("source"),
                sourceId = encodedProvenance.optionalString("source_id"),
                observedAt = encodedProvenance.requiredInstant("observed_at"),
                confidence = encodedProvenance.requiredDouble("confidence"),
                userConfirmed = encodedProvenance.requiredBoolean("user_confirmed"),
            ),
            sensitivity = enumValue("sensitivity", MemorySensitivity.values()),
            createdAt = requiredInstant("created_at"),
            expiresAt = optionalInstant("expires_at"),
            lastUsedAt = optionalInstant("last_used_at"),
            revoked = requiredBoolean("revoked"),
        )
    }

    private inline fun <reified T : Enum<T>> Map<String, Any?>.enumValue(
        field: String,
        values: Array<T>,
    ): T {
        val encoded = requiredString(field)
        return values.firstOrNull { it.name == encoded }
            ?: throw IllegalArgumentException("Unknown $field: $encoded")
    }

    private fun Map<String, Any?>.requiredString(field: String): String =
        this[field] as? String ?: throw IllegalArgumentException("$field must be a string")

    private fun Map<String, Any?>.optionalString(field: String): String? = when (val value = this[field]) {
        null -> null
        is String -> value
        else -> throw IllegalArgumentException("$field must be a string or null")
    }

    private fun Map<String, Any?>.requiredBoolean(field: String): Boolean =
        this[field] as? Boolean ?: throw IllegalArgumentException("$field must be a boolean")

    private fun Map<String, Any?>.requiredLong(field: String): Long =
        (this[field] as? Long) ?: throw IllegalArgumentException("$field must be an integer")

    private fun Map<String, Any?>.requiredDouble(field: String): Double =
        (this[field] as? Number)?.toDouble()
            ?: throw IllegalArgumentException("$field must be a number")

    private fun Map<String, Any?>.requiredInstant(field: String): Instant =
        Instant.parse(requiredString(field))

    private fun Map<String, Any?>.optionalInstant(field: String): Instant? =
        optionalString(field)?.let(Instant::parse)

    private fun Any?.asObject(label: String): Map<String, Any?> {
        val raw = this as? Map<*, *> ?: throw IllegalArgumentException("$label must be an object")
        require(raw.keys.all { it is String }) { "$label contains a non-string key" }
        @Suppress("UNCHECKED_CAST")
        return raw as Map<String, Any?>
    }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/** Small strict JSON reader; keeps this domain module independent of Android and JSON libraries. */
private class JsonReader(private val input: String) {
    private var position = 0

    fun readDocument(): Any? {
        skipWhitespace()
        val value = readValue()
        skipWhitespace()
        require(position == input.length) { "Unexpected data after JSON document" }
        return value
    }

    private fun readValue(): Any? {
        skipWhitespace()
        require(position < input.length) { "Unexpected end of JSON" }
        return when (input[position]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> readString()
            't' -> readLiteral("true", true)
            'f' -> readLiteral("false", false)
            'n' -> readLiteral("null", null)
            '-', in '0'..'9' -> readNumber()
            else -> throw IllegalArgumentException("Invalid JSON at position $position")
        }
    }

    private fun readObject(): Map<String, Any?> {
        expect('{')
        skipWhitespace()
        val result = LinkedHashMap<String, Any?>()
        if (consume('}')) return result
        while (true) {
            skipWhitespace()
            require(position < input.length && input[position] == '"') {
                "Expected object key at position $position"
            }
            val key = readString()
            require(!result.containsKey(key)) { "Duplicate JSON object key: $key" }
            skipWhitespace()
            expect(':')
            result[key] = readValue()
            skipWhitespace()
            if (consume('}')) return result
            expect(',')
        }
    }

    private fun readArray(): List<Any?> {
        expect('[')
        skipWhitespace()
        val result = ArrayList<Any?>()
        if (consume(']')) return result
        while (true) {
            result += readValue()
            skipWhitespace()
            if (consume(']')) return result
            expect(',')
        }
    }

    private fun readString(): String {
        expect('"')
        val result = StringBuilder()
        while (position < input.length) {
            val char = input[position++]
            when {
                char == '"' -> return result.toString()
                char == '\\' -> {
                    require(position < input.length) { "Incomplete JSON escape" }
                    when (val escape = input[position++]) {
                        '"' -> result.append('"')
                        '\\' -> result.append('\\')
                        '/' -> result.append('/')
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000C')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> result.append(readUnicodeEscape())
                        else -> throw IllegalArgumentException("Invalid JSON escape: $escape")
                    }
                }

                char < ' ' -> throw IllegalArgumentException("Unescaped control character in JSON string")
                else -> result.append(char)
            }
        }
        throw IllegalArgumentException("Unterminated JSON string")
    }

    private fun readUnicodeEscape(): Char {
        require(position + 4 <= input.length) { "Incomplete unicode escape" }
        val digits = input.substring(position, position + 4)
        require(digits.all { it.digitToIntOrNull(16) != null }) { "Invalid unicode escape" }
        position += 4
        return digits.toInt(16).toChar()
    }

    private fun readNumber(): Number {
        val start = position
        consume('-')
        if (consume('0')) {
            require(position >= input.length || input[position] !in '0'..'9') {
                "Leading zero in JSON number"
            }
        } else {
            require(consumeDigits()) { "Invalid JSON number" }
        }
        var hasFractionOrExponent = false
        if (consume('.')) {
            hasFractionOrExponent = true
            require(consumeDigits()) { "Invalid JSON fraction" }
        }
        if (position < input.length && input[position] in "eE") {
            hasFractionOrExponent = true
            position++
            if (position < input.length && input[position] in "+-") position++
            require(consumeDigits()) { "Invalid JSON exponent" }
        }
        val encoded = input.substring(start, position)
        return if (hasFractionOrExponent) {
            encoded.toDoubleOrNull()?.takeIf(Double::isFinite)
                ?: throw IllegalArgumentException("Invalid JSON number")
        } else {
            encoded.toLongOrNull() ?: throw IllegalArgumentException("Integer is out of range")
        }
    }

    private fun consumeDigits(): Boolean {
        val start = position
        while (position < input.length && input[position] in '0'..'9') position++
        return position > start
    }

    private fun readLiteral(encoded: String, value: Any?): Any? {
        require(input.regionMatches(position, encoded, 0, encoded.length)) {
            "Invalid JSON literal at position $position"
        }
        position += encoded.length
        return value
    }

    private fun skipWhitespace() {
        while (position < input.length && input[position] in charArrayOf(' ', '\n', '\r', '\t')) {
            position++
        }
    }

    private fun expect(expected: Char) {
        require(position < input.length && input[position] == expected) {
            "Expected '$expected' at position $position"
        }
        position++
    }

    private fun consume(char: Char): Boolean {
        if (position < input.length && input[position] == char) {
            position++
            return true
        }
        return false
    }
}
