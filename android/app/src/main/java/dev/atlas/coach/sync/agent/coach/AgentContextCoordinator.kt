package dev.atlas.coach.sync.agent.coach

import dev.atlas.coach.sync.agent.AgentMandate
import dev.atlas.coach.sync.agent.context.AgentContextAssembler
import dev.atlas.coach.sync.agent.context.AgentContextCategory
import dev.atlas.coach.sync.agent.context.AgentContextDomainConsent
import dev.atlas.coach.sync.agent.context.AgentContextDomainViews
import dev.atlas.coach.sync.agent.context.AgentContextPreview
import dev.atlas.coach.sync.agent.context.AgentContextSnapshot

/** Owns persisted consent timestamps and assembles immutable request snapshots from live domains. */
class AgentContextCoordinator(
    initialConsentAtUtcMillis: Map<String, Long>?,
    legacyConsentedCategories: Set<String>,
    private val mandateProvider: () -> AgentMandate,
    private val domainsProvider: () -> AgentContextDomainViews,
    private val nowUtcMillis: () -> Long,
    private val persistConsentDates: (Map<String, Long>) -> Unit,
    private val additionalConsentDates: () -> Map<String, Long> = { emptyMap() },
    private val assembler: AgentContextAssembler = AgentContextAssembler(),
    private val agentAccessAllowed: () -> Boolean = { true },
    private val showConsentedContext: () -> Boolean = agentAccessAllowed,
) {
    private val consentAt = linkedMapOf<String, Long>().apply {
        initialConsentAtUtcMillis.orEmpty()
            .filterKeys { it in AgentContextCategory.wireValueSet }
            .filterValues { it > 0L }
            .toSortedMap()
            .forEach { (category, time) -> put(category, time) }
        if (initialConsentAtUtcMillis == null) {
            val migrated = AgentContextConsentMigrationCompat.migrate(legacyConsentedCategories, nowUtcMillis().coerceAtLeast(1L))
            putAll(migrated)
        }
    }

    @Synchronized
    fun consentDates(): Map<String, Long> = consentAt.toMap()

    @Synchronized
    fun setCategoryConsent(category: String, enabled: Boolean) {
        require(category in AgentContextCategory.wireValueSet) { "Unknown context category" }
        val previous = consentAt[category]
        if (enabled) consentAt[category] = nowUtcMillis().coerceAtLeast(1L) else consentAt.remove(category)
        try {
            persistConsentDates(consentAt.toMap())
        } catch (error: Exception) {
            if (previous == null) consentAt.remove(category) else consentAt[category] = previous
            throw error
        }
    }

    @Synchronized
    fun resetCategoryConsents(categories: Set<String>) {
        require(categories.all { it in AgentContextCategory.wireValueSet }) { "Unknown context category" }
        val previous = consentAt.toMap()
        val now = nowUtcMillis().coerceAtLeast(1L)
        AgentContextCategory.wireValues.forEach { category ->
            if (category in categories) consentAt[category] = now else consentAt.remove(category)
        }
        try {
            persistConsentDates(consentAt.toMap())
        } catch (error: Exception) {
            consentAt.clear()
            consentAt.putAll(previous)
            throw error
        }
    }

    fun preview(): AgentContextPreview = assembleCurrent().preview()

    fun assembleCurrent(): AgentContextSnapshot {
        val now = nowUtcMillis().coerceAtLeast(1L)
        val currentMandate = mandateProvider()
        val mandate = if (showConsentedContext()) currentMandate else currentMandate.copy(sharedDataCategories = emptySet())
        val dates = currentConsentDates()
        val categoriesWithDates = mandate.sharedDataCategories.filterTo(linkedSetOf()) { category ->
            dates[category]?.let { it in 1L..now } == true
        }
        val contextMandate = mandate.copy(sharedDataCategories = categoriesWithDates)
        val domains = domainsProvider()
        return assembler.assemble(contextMandate, dates, domains, now)
    }

    /** Called by AgentApiClient after its background queue drains and immediately before write. */
    @Suppress("UNUSED_PARAMETER")
    fun requestContext(snapshot: AgentContextSnapshot): AgentRequestContext? {
        if (!agentAccessAllowed()) return null
        val now = nowUtcMillis().coerceAtLeast(1L)
        val currentMandate = mandateProvider()
        if (currentMandate.revoked || currentMandate.suspended || now >= currentMandate.expiresAtUtcMillis) return null
        val dates = currentConsentDates()
        val domains = domainsProvider()
        val consentBoundMandate = currentMandate.copy(
            sharedDataCategories = currentMandate.sharedDataCategories.filterTo(linkedSetOf()) { category ->
                dates[category]?.let { it in 1L..now } == true
            },
        )
        // The user's request may have waited in the sync queue while local records or consent
        // changed. Rebuild from current domain views here; the API client minimizes this immutable
        // snapshot again against the same latest authorization immediately before writing bytes.
        val latestSnapshot = assembler.assemble(consentBoundMandate, dates, domains, now)
        return AgentRequestContext(
            mandate = consentBoundMandate,
            snapshot = latestSnapshot,
            consentAtUtcMillis = dates,
            domainConsent = AgentContextDomainConsent(
                usageConsent = domains.usageConsent,
                locationSummarySharingEnabled = domains.locationSummarySharingEnabled,
            ),
            nowUtcMillis = now,
        )
    }

    private object AgentContextConsentMigrationCompat {
        fun migrate(categories: Set<String>, nowUtcMillis: Long): Map<String, Long> =
            dev.atlas.coach.sync.agent.context.AgentContextConsentMigration
                .migrateLegacyConsentDatesToNow(categories, nowUtcMillis)
    }

    private fun currentConsentDates(): Map<String, Long> = consentDates().toMutableMap().apply {
        additionalConsentDates().forEach { (category, timestamp) ->
            if (category in AgentContextCategory.wireValueSet && timestamp > 0L) {
                this[category] = maxOf(this[category] ?: 0L, timestamp)
            }
        }
    }
}
