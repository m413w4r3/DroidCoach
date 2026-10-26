package dev.atlas.coach.memory

import java.time.Instant
import java.util.Locale

/** Filters and formats a small deterministic context without exposing internal fact IDs. */
object MemoryContextSelector {
    const val MAX_FACTS = 12
    const val MAX_CONTEXT_CHARS = 4_000

    fun select(
        facts: Iterable<MemoryFact>,
        consent: Set<MemoryCategory>,
        now: Instant,
        limit: Int,
    ): String {
        val maxFacts = limit.coerceIn(0, MAX_FACTS)
        if (maxFacts == 0) return ""

        val candidates = facts.asSequence()
            .filterNot(MemoryFact::revoked)
            .filter { fact -> fact.category != MemoryCategory.HYPOTHESIS || fact.expiresAt != null }
            .filter { fact -> fact.expiresAt == null || fact.expiresAt.isAfter(now) }
            .filter { fact -> fact.sensitivity != MemorySensitivity.SENSITIVE || fact.category in consent }
            .distinctBy(MemoryFact::factId)
            .sortedWith(
                compareBy<MemoryFact> { it.category.ordinal }
                    .thenBy { it.statement }
                    .thenBy { it.provenance.source }
                    .thenBy { it.createdAt }
                    .thenBy { it.factId },
            )

        val output = StringBuilder()
        var included = 0
        for (fact in candidates) {
            if (included >= maxFacts) break
            val line = format(fact)
            val addedLength = line.length + if (output.isEmpty()) 0 else 1
            if (output.length + addedLength > MAX_CONTEXT_CHARS) break
            if (output.isNotEmpty()) output.append('\n')
            output.append(line)
            included++
        }
        return output.toString()
    }

    private fun format(fact: MemoryFact): String {
        val statement = normalize(fact.statement)
        val normalizedSource = normalize(fact.provenance.source)
        val source = if (normalizedSource.length <= MAX_SOURCE_CHARS) {
            normalizedSource
        } else {
            normalizedSource.take(MAX_SOURCE_CHARS - 3) + "..."
        }
        val confidence = String.format(Locale.ROOT, "%.2f", fact.provenance.confidence)
        val confirmed = fact.provenance.userConfirmed
        val observedAt = fact.provenance.observedAt.toString()
        return "- ${fact.category.name}: $statement " +
            "(source: $source; observed: $observedAt; confirmed: $confirmed; confidence: $confidence)"
    }

    private fun normalize(value: String): String = value.trim().replace(WHITESPACE, " ")

    private val WHITESPACE = Regex("\\s+")

    private const val MAX_SOURCE_CHARS = 80
}
