package dev.atlas.coach.memory

import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class MemoryCategory {
    GOAL,
    PREFERENCE,
    CONFIRMED_HABIT,
    HYPOTHESIS,
    OBSERVATION,
    AGGREGATE,
}

enum class MemorySensitivity {
    NORMAL,
    SENSITIVE,
}

data class MemoryProvenance(
    val source: String,
    val sourceId: String? = null,
    val observedAt: Instant,
    val confidence: Double,
    val userConfirmed: Boolean,
) {
    init {
        require(source.isNotBlank()) { "Provenance source must not be blank" }
        require(confidence.isFinite() && confidence in 0.0..1.0) {
            "Confidence must be between 0 and 1"
        }
    }
}

data class MemoryFact(
    val factId: String,
    val category: MemoryCategory,
    val statement: String,
    val provenance: MemoryProvenance,
    val sensitivity: MemorySensitivity,
    val createdAt: Instant,
    val expiresAt: Instant? = null,
    val lastUsedAt: Instant? = null,
    val revoked: Boolean = false,
) {
    init {
        val parsedId = runCatching { UUID.fromString(factId) }.getOrNull()
        require(parsedId != null && parsedId.toString() == factId) {
            "factId must be a canonical lowercase UUID"
        }
        require(statement.isNotBlank()) { "Statement must not be blank" }
        require(statement.length <= MAX_STATEMENT_LENGTH) {
            "Statement must be at most $MAX_STATEMENT_LENGTH characters"
        }
        require(category != MemoryCategory.HYPOTHESIS || !provenance.userConfirmed) {
            "A hypothesis must remain unconfirmed"
        }
        require(
            category != MemoryCategory.PREFERENCE && category != MemoryCategory.CONFIRMED_HABIT ||
                provenance.userConfirmed,
        ) {
            "A preference or confirmed habit requires explicit user confirmation"
        }
    }

    companion object {
        const val MAX_STATEMENT_LENGTH = 280
    }
}

/** Clock boundary keeps expiry and last-use behavior deterministic in JVM tests. */
fun interface MemoryClock {
    fun now(): Instant
}

object SystemMemoryClock : MemoryClock {
    override fun now(): Instant = Instant.now()
}

/**
 * Local retention defaults for the fact categories represented by this core.
 * Objectives, preferences and confirmed habits are persistent until deletion.
 * The 90-day plan/result and 30-day detailed-usage durations are exposed for
 * adjacent local records; those record types are not MemoryFact categories.
 */
data class MemoryRetentionPolicy(
    val hypothesisRetention: Duration = Duration.ofDays(7),
    val observationRetention: Duration = Duration.ofHours(24),
    val aggregateRetention: Duration = Duration.ofDays(365),
    val planAndResultRetention: Duration = Duration.ofDays(90),
    val detailedUsageRetention: Duration = Duration.ofDays(30),
) {
    init {
        require(!hypothesisRetention.isNegative) { "Hypothesis retention must not be negative" }
        require(!observationRetention.isNegative) { "Observation retention must not be negative" }
        require(!aggregateRetention.isNegative) { "Aggregate retention must not be negative" }
        require(!planAndResultRetention.isNegative) { "Plan/result retention must not be negative" }
        require(!detailedUsageRetention.isNegative) { "Detailed-usage retention must not be negative" }
    }

    fun expiryFor(category: MemoryCategory, createdAt: Instant): Instant? = when (category) {
        MemoryCategory.GOAL, MemoryCategory.PREFERENCE, MemoryCategory.CONFIRMED_HABIT -> null

        MemoryCategory.HYPOTHESIS -> createdAt.plus(hypothesisRetention)
        MemoryCategory.OBSERVATION -> createdAt.plus(observationRetention)
        MemoryCategory.AGGREGATE -> createdAt.plus(aggregateRetention)
    }
}
