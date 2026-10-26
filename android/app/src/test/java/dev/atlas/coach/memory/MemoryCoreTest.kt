package dev.atlas.coach.memory

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryCoreTest {
    @Test
    fun addAndGetFact() {
        val store = store()
        val fact = fact(ID_1)

        val added = store.add(fact)

        assertEquals(fact, added)
        assertEquals(fact, store.get(ID_1))
    }

    @Test
    fun rejectStatementOver280() {
        assertThrows(IllegalArgumentException::class.java) {
            fact(ID_1, statement = "x".repeat(281))
        }
    }

    @Test
    fun duplicateFactIdRejected() {
        val store = store()
        store.add(fact(ID_1))

        assertThrows(IllegalArgumentException::class.java) { store.add(fact(ID_1)) }
    }

    @Test
    fun singleObservationIsNotPromoted() {
        val store = store()
        val observation = fact(ID_1, category = MemoryCategory.OBSERVATION)

        val added = store.add(observation)

        assertEquals(MemoryCategory.OBSERVATION, added.category)
        assertFalse(added.provenance.userConfirmed)
        assertThrows(IllegalArgumentException::class.java) {
            fact(ID_2, category = MemoryCategory.PREFERENCE, userConfirmed = false)
        }
    }

    @Test
    fun explicitConfirmationPromotesWithProvenance() {
        val store = store()
        val observation = fact(
            ID_1,
            category = MemoryCategory.OBSERVATION,
            source = "explicit-feedback",
            sourceId = "feedback-7",
            observedAt = BASE.minusSeconds(120),
            confidence = 0.73,
        )
        store.add(observation)

        val confirmed = store.confirm(ID_1, MemoryCategory.CONFIRMED_HABIT)

        assertEquals(MemoryCategory.CONFIRMED_HABIT, confirmed.category)
        assertTrue(confirmed.provenance.userConfirmed)
        assertEquals(observation.provenance.source, confirmed.provenance.source)
        assertEquals(observation.provenance.sourceId, confirmed.provenance.sourceId)
        assertEquals(observation.provenance.observedAt, confirmed.provenance.observedAt)
        assertEquals(observation.provenance.confidence, confirmed.provenance.confidence, 0.0)
        assertNull(confirmed.expiresAt)
    }

    @Test
    fun correctionKeepsIdentityAndUpdatesStatement() {
        val store = store()
        store.add(fact(ID_1))

        val corrected = store.correct(ID_1, "Plan one short session")

        assertEquals(ID_1, corrected.factId)
        assertEquals("Plan one short session", corrected.statement)
        assertEquals(ID_1, store.get(ID_1)?.factId)
    }

    @Test
    fun revokedFactIsExcludedImmediately() {
        val store = store()
        store.add(fact(ID_1))
        assertTrue(MemoryContextSelector.select(store.list(), emptySet(), BASE, limit = 5).isNotEmpty())

        store.revoke(ID_1)
        val nextContext = MemoryContextSelector.select(store.list(), emptySet(), BASE, limit = 5)

        assertEquals("", nextContext)
        assertTrue(store.list(includeRevoked = true).single().revoked)
    }

    @Test
    fun expiredHypothesisIsPurged() {
        val store = store()
        store.add(fact(ID_1, category = MemoryCategory.HYPOTHESIS, createdAt = BASE.minus(Duration.ofDays(8))))

        assertEquals(1, store.purgeExpired(BASE))
        assertNull(store.get(ID_1))
    }

    @Test
    fun retentionPolicyPurgesOnlyEligibleCategories() {
        val policy = MemoryRetentionPolicy()
        val store = store(policy = policy)
        val old = BASE.minus(Duration.ofDays(400))
        val expiredOverride = BASE.minusSeconds(1)
        store.add(fact(ID_1, MemoryCategory.GOAL, createdAt = old, expiresAt = expiredOverride))
        store.add(fact(ID_2, MemoryCategory.PREFERENCE, createdAt = old, expiresAt = expiredOverride))
        store.add(fact(ID_3, MemoryCategory.CONFIRMED_HABIT, createdAt = old, expiresAt = expiredOverride))
        store.add(fact(ID_4, MemoryCategory.HYPOTHESIS, createdAt = old))
        store.add(fact(ID_5, MemoryCategory.OBSERVATION, createdAt = old))
        store.add(fact(ID_6, MemoryCategory.AGGREGATE, createdAt = old))

        assertEquals(3, store.purgeExpired(BASE))
        assertEquals(setOf(ID_1, ID_2, ID_3), store.list().map(MemoryFact::factId).toSet())
        assertEquals(Duration.ofDays(90), policy.planAndResultRetention)
        assertEquals(Duration.ofDays(30), policy.detailedUsageRetention)
    }

    @Test
    fun sensitiveFactRequiresCategoryConsent() {
        val sensitive = fact(ID_1, sensitivity = MemorySensitivity.SENSITIVE)

        val withoutConsent = MemoryContextSelector.select(listOf(sensitive), emptySet(), BASE, limit = 1)
        val withConsent = MemoryContextSelector.select(
            listOf(sensitive),
            setOf(MemoryCategory.PREFERENCE),
            BASE,
            limit = 1,
        )

        assertEquals("", withoutConsent)
        assertTrue(withConsent.contains(sensitive.statement))
    }

    @Test
    fun contextIsBoundedAndDeterministic() {
        val facts = (1..20).map { index ->
            fact(
                id = uuid(index),
                statement = "Preference ${index.toString().padStart(2, '0')}: ${"brief ".repeat(35)}",
                source = "test-source",
            )
        }

        val first = MemoryContextSelector.select(facts, emptySet(), BASE, limit = 3)
        val second = MemoryContextSelector.select(facts.reversed(), emptySet(), BASE, limit = 3)

        assertEquals(first, second)
        assertTrue(first.lines().size <= 3)
        assertTrue(first.length <= MemoryContextSelector.MAX_CONTEXT_CHARS)
        assertTrue(first.contains("source: test-source"))
        assertTrue(first.contains("observed:"))
        assertTrue(first.contains("confidence:"))
        assertFalse(first.contains(ID_1))
    }

    @Test
    fun jsonRoundTripPreservesFacts() {
        val codec = MemoryJsonCodec()
        val expected = listOf(
            fact(
                ID_1,
                category = MemoryCategory.OBSERVATION,
                statement = "Keep a \"short\" note\nwith a second line",
                sensitivity = MemorySensitivity.SENSITIVE,
                expiresAt = BASE.plusSeconds(600),
                lastUsedAt = BASE.minusSeconds(60),
            ),
            fact(ID_2, category = MemoryCategory.HYPOTHESIS, createdAt = BASE.minusSeconds(30)),
        )

        val encoded = codec.encode(expected)
        assertEquals(expected, codec.decode(encoded))

        val backend = FakeMemoryBackend()
        val persistentStore = store(backend = backend)
        persistentStore.add(fact(ID_3))
        assertEquals(fact(ID_3), store(backend = backend).get(ID_3))
    }

    @Test
    fun unknownSchemaVersionRejected() {
        val codec = MemoryJsonCodec()
        val encoded = codec.encode(listOf(fact(ID_1))).replace("\"schema_version\":1", "\"schema_version\":2")

        assertThrows(IllegalArgumentException::class.java) { codec.decode(encoded) }
    }

    @Test
    fun deleteRemovesFact() {
        val store = store()
        store.add(fact(ID_1))

        assertTrue(store.delete(ID_1))
        assertNull(store.get(ID_1))
        assertFalse(store.delete(ID_1))
    }

    @Test
    fun markUsedUpdatesLastUsedAt() {
        val clock = MutableMemoryClock(BASE)
        val store = InMemoryMemoryStore(clock = clock)
        store.add(fact(ID_1))
        val usedAt = BASE.plusSeconds(90)
        clock.instant = usedAt

        assertEquals(usedAt, store.markUsed(ID_1).lastUsedAt)
    }

    private fun store(
        policy: MemoryRetentionPolicy = MemoryRetentionPolicy(),
        backend: MemoryBackend? = null,
    ) = InMemoryMemoryStore(
        clock = MutableMemoryClock(BASE),
        retentionPolicy = policy,
        backend = backend,
    )

    private fun fact(
        id: String,
        category: MemoryCategory = MemoryCategory.PREFERENCE,
        statement: String = "Use a short planning block",
        source: String = "unit-test",
        sourceId: String? = "fixture-1",
        observedAt: Instant = BASE,
        confidence: Double = 0.8,
        userConfirmed: Boolean? = null,
        sensitivity: MemorySensitivity = MemorySensitivity.NORMAL,
        createdAt: Instant = BASE,
        expiresAt: Instant? = null,
        lastUsedAt: Instant? = null,
        revoked: Boolean = false,
    ) = MemoryFact(
        factId = id,
        category = category,
        statement = statement,
        provenance = MemoryProvenance(
            source = source,
            sourceId = sourceId,
            observedAt = observedAt,
            confidence = confidence,
            userConfirmed = userConfirmed ?: category in setOf(
                MemoryCategory.PREFERENCE,
                MemoryCategory.CONFIRMED_HABIT,
            ),
        ),
        sensitivity = sensitivity,
        createdAt = createdAt,
        expiresAt = expiresAt ?: MemoryRetentionPolicy().expiryFor(category, createdAt),
        lastUsedAt = lastUsedAt,
        revoked = revoked,
    )

    private fun uuid(number: Int): String =
        "00000000-0000-0000-0000-${number.toString().padStart(12, '0')}"

    private class MutableMemoryClock(var instant: Instant) : MemoryClock {
        override fun now(): Instant = instant
    }

    private class FakeMemoryBackend(initialValue: String? = null) : MemoryBackend {
        private var value = initialValue

        override fun read(): String? = value

        override fun write(value: String) {
            this.value = value
        }
    }

    companion object {
        private val BASE: Instant = Instant.parse("2026-01-02T03:04:05Z")
        private const val ID_1 = "00000000-0000-0000-0000-000000000001"
        private const val ID_2 = "00000000-0000-0000-0000-000000000002"
        private const val ID_3 = "00000000-0000-0000-0000-000000000003"
        private const val ID_4 = "00000000-0000-0000-0000-000000000004"
        private const val ID_5 = "00000000-0000-0000-0000-000000000005"
        private const val ID_6 = "00000000-0000-0000-0000-000000000006"
    }
}
