package dev.atlas.coach.memory

import java.time.Instant
import java.util.LinkedHashMap

interface MemoryStore {
    fun add(fact: MemoryFact): MemoryFact
    fun get(factId: String): MemoryFact?
    fun list(includeRevoked: Boolean = false): List<MemoryFact>
    fun confirm(factId: String, targetCategory: MemoryCategory? = null): MemoryFact
    fun correct(factId: String, statement: String): MemoryFact
    fun delete(factId: String): Boolean
    fun revoke(factId: String): MemoryFact
    fun purgeExpired(now: Instant? = null): Int
    fun markUsed(factId: String): MemoryFact
}

/** Thread-safe local implementation; a supplied backend is updated before each mutation is visible. */
class InMemoryMemoryStore(
    private val clock: MemoryClock = SystemMemoryClock,
    private val retentionPolicy: MemoryRetentionPolicy = MemoryRetentionPolicy(),
    private val backend: MemoryBackend? = null,
    private val codec: MemoryJsonCodec = MemoryJsonCodec(),
) : MemoryStore {
    private val lock = Any()
    private var facts: LinkedHashMap<String, MemoryFact> = loadInitialFacts()

    override fun add(fact: MemoryFact): MemoryFact = synchronized(lock) {
        require(!facts.containsKey(fact.factId)) { "Duplicate fact id: ${fact.factId}" }
        val storedFact = applyRetentionDefaults(fact)
        val updated = LinkedHashMap(facts)
        updated[storedFact.factId] = storedFact
        commit(updated)
        storedFact
    }

    override fun get(factId: String): MemoryFact? = synchronized(lock) { facts[factId] }

    override fun list(includeRevoked: Boolean): List<MemoryFact> = synchronized(lock) {
        facts.values
            .asSequence()
            .filter { includeRevoked || !it.revoked }
            .sortedWith(compareBy<MemoryFact> { it.createdAt }.thenBy { it.factId })
            .toList()
    }

    override fun confirm(factId: String, targetCategory: MemoryCategory?): MemoryFact = synchronized(lock) {
        val current = requireActiveFact(factId)
        if (current.category == MemoryCategory.OBSERVATION || current.category == MemoryCategory.HYPOTHESIS) {
            require(targetCategory != null) {
                "Confirming an observation or hypothesis requires an explicit target category"
            }
        }
        val category = targetCategory ?: current.category
        require(category != MemoryCategory.OBSERVATION && category != MemoryCategory.HYPOTHESIS) {
            "A confirmed fact cannot remain an observation or hypothesis"
        }
        val promoted = current.copy(
            category = category,
            provenance = current.provenance.copy(userConfirmed = true),
            expiresAt = retentionPolicy.expiryFor(category, clock.now()),
        )
        replace(promoted)
    }

    override fun correct(factId: String, statement: String): MemoryFact = synchronized(lock) {
        val current = requireActiveFact(factId)
        val corrected = current.copy(statement = statement)
        replace(corrected)
    }

    override fun delete(factId: String): Boolean = synchronized(lock) {
        if (!facts.containsKey(factId)) return@synchronized false
        val updated = LinkedHashMap(facts)
        updated.remove(factId)
        commit(updated)
        true
    }

    override fun revoke(factId: String): MemoryFact = synchronized(lock) {
        val current = facts[factId] ?: throw NoSuchElementException("Unknown fact id: $factId")
        if (current.revoked) return@synchronized current
        replace(current.copy(revoked = true))
    }

    override fun purgeExpired(now: Instant?): Int = synchronized(lock) {
        val cutoff = now ?: clock.now()
        val updated = LinkedHashMap(facts)
        val expiredIds = updated.values
            .filter { fact -> fact.expiresAt?.let { !it.isAfter(cutoff) } == true }
            .map(MemoryFact::factId)
        expiredIds.forEach(updated::remove)
        if (expiredIds.isNotEmpty()) commit(updated)
        expiredIds.size
    }

    override fun markUsed(factId: String): MemoryFact = synchronized(lock) {
        val current = requireActiveFact(factId)
        replace(current.copy(lastUsedAt = clock.now()))
    }

    private fun replace(fact: MemoryFact): MemoryFact {
        val updated = LinkedHashMap(facts)
        updated[fact.factId] = fact
        commit(updated)
        return fact
    }

    private fun requireActiveFact(factId: String): MemoryFact {
        val fact = facts[factId] ?: throw NoSuchElementException("Unknown fact id: $factId")
        check(!fact.revoked) { "Fact has been revoked: $factId" }
        check(fact.expiresAt == null || fact.expiresAt.isAfter(clock.now())) {
            "Fact has expired: $factId"
        }
        return fact
    }

    private fun commit(updated: LinkedHashMap<String, MemoryFact>) {
        backend?.write(codec.encode(updated.values.sortedBy(MemoryFact::factId)))
        facts = updated
    }

    private fun loadInitialFacts(): LinkedHashMap<String, MemoryFact> {
        val encoded = backend?.read() ?: return LinkedHashMap()
        val decoded = codec.decode(encoded)
        return LinkedHashMap<String, MemoryFact>().apply {
            decoded.forEach { fact ->
                val normalized = applyRetentionDefaults(fact)
                put(normalized.factId, normalized)
            }
        }
    }

    private fun applyRetentionDefaults(fact: MemoryFact): MemoryFact = fact.copy(
        expiresAt = if (fact.category in PERSISTENT_CATEGORIES) {
            null
        } else {
            fact.expiresAt ?: retentionPolicy.expiryFor(fact.category, fact.createdAt)
        },
    )

    private companion object {
        val PERSISTENT_CATEGORIES = setOf(
            MemoryCategory.GOAL,
            MemoryCategory.PREFERENCE,
            MemoryCategory.CONFIRMED_HABIT,
        )
    }
}
