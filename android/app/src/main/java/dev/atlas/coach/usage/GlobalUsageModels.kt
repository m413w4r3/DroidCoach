package dev.atlas.coach.usage

import java.time.ZoneId

enum class UsageReliability {
    HIGH,
    MEDIUM,
    LOW,
}

enum class GlobalUsageEventType {
    ACTIVITY_RESUMED,
    ACTIVITY_PAUSED,
}

data class GlobalUsageEvent(
    val eventId: String,
    val packageName: String,
    val timestampUtc: Long,
    val type: GlobalUsageEventType,
) {
    init {
        require(eventId.isNotBlank())
        require(packageName.isNotBlank())
        require(timestampUtc >= 0L)
    }
}

data class GlobalUsageEventBatch(
    val events: List<GlobalUsageEvent>,
    val truncated: Boolean,
)

fun interface GlobalUsageEventSource {
    fun query(fromUtc: Long, throughUtc: Long, maxEvents: Int): GlobalUsageEventBatch
}

data class GlobalUsageConsent(
    val granted: Boolean = false,
    val grantedAtUtc: Long? = null,
    val revokedAtUtc: Long? = null,
    val consentVersion: Int = 1,
    val purpose: String = "",
    val consentedCategories: Set<String> = emptySet(),
) {
    val isExplicit: Boolean
        get() = granted && grantedAtUtc != null && purpose.isNotBlank() && consentVersion > 0

    val allowsPackageNames: Boolean
        get() = isExplicit && PACKAGE_NAMES_CATEGORY in consentedCategories

    init {
        require(grantedAtUtc == null || grantedAtUtc >= 0L)
        require(revokedAtUtc == null || revokedAtUtc >= 0L)
        require(consentVersion > 0)
        require(consentedCategories.none(String::isBlank))
    }

    companion object {
        const val PACKAGE_NAMES_CATEGORY = "package_names"

        val REVOKED = GlobalUsageConsent()

        fun grant(
            grantedAtUtc: Long,
            purpose: String,
            consentVersion: Int = 1,
            consentedCategories: Set<String> = emptySet(),
        ): GlobalUsageConsent {
            require(grantedAtUtc >= 0L)
            require(purpose.isNotBlank()) { "Global usage consent requires an explicit purpose" }
            require(consentVersion > 0)
            require(consentedCategories.none(String::isBlank))
            return GlobalUsageConsent(
                granted = true,
                grantedAtUtc = grantedAtUtc,
                consentVersion = consentVersion,
                purpose = purpose.trim(),
                consentedCategories = consentedCategories.toSet(),
            )
        }
    }
}

data class GlobalUsageCursor(
    val baselineUtc: Long,
    val lastCollectionAttemptUtc: Long? = null,
) {
    init {
        require(baselineUtc >= 0L)
        require(lastCollectionAttemptUtc == null || lastCollectionAttemptUtc >= 0L)
        require(lastCollectionAttemptUtc == null || lastCollectionAttemptUtc >= baselineUtc)
    }
}

data class UsageInterval(
    val appRef: String,
    val startUtc: Long,
    val endUtc: Long,
    val reliability: UsageReliability,
    val source: String,
) {
    init {
        require(appRef.isNotBlank())
        require(startUtc >= 0L)
        require(endUtc >= startUtc)
        require(source.isNotBlank())
    }

    val durationMs: Long
        get() = endUtc - startUtc
}

data class UsageAppSummary(
    val appRef: String,
    val displayName: String? = null,
    val foregroundMs: Long,
    val launchCount: Int,
    val lastUsedAtUtc: Long? = null,
    val reliability: UsageReliability,
) {
    init {
        require(appRef.isNotBlank())
        require(displayName == null || displayName.isNotBlank())
        require(foregroundMs >= 0L)
        require(launchCount >= 0)
        require(lastUsedAtUtc == null || lastUsedAtUtc >= 0L)
    }
}

data class UsageSummaryCoverage(
    val observedMs: Long,
    val blindMs: Long? = null,
    val eventsSeen: Int,
    val truncated: Boolean,
) {
    init {
        require(observedMs >= 0L)
        require(blindMs == null || blindMs >= 0L)
        require(eventsSeen >= 0)
    }
}

data class UsageSummary(
    val windowStart: Long,
    val windowEnd: Long,
    val timezone: String,
    val totalForegroundMs: Long,
    val apps: List<UsageAppSummary>,
    val coverage: UsageSummaryCoverage,
    val generatedAt: Long,
    val consentVersion: Int,
) {
    init {
        require(windowStart >= 0L && windowEnd > windowStart)
        require(timezone.isNotBlank())
        ZoneId.of(timezone)
        require(totalForegroundMs >= 0L)
        require(apps.size <= UsageSummaryLimits.MAX_APPS)
        require(apps.map(UsageAppSummary::appRef).distinct().size == apps.size)
        require(generatedAt >= 0L)
        require(consentVersion > 0)
    }
}

data class UsageWindow(
    val startUtc: Long,
    val endUtc: Long,
    val timezone: ZoneId,
) {
    init {
        require(startUtc >= 0L && endUtc > startUtc)
    }
}

data class UsageQuota(
    val quotaId: String,
    val appRef: String? = null,
    val window: UsageQuotaWindow,
    val limitMs: Long,
    val warningRatio: Double = 0.8,
    val enabled: Boolean = true,
) {
    init {
        require(quotaId.isNotBlank())
        require(appRef == null || appRef.isNotBlank())
        require(limitMs > 0L)
        require(warningRatio > 0.0 && warningRatio <= 1.0)
    }
}

enum class UsageQuotaWindow {
    CALENDAR_DAY,
    ROLLING_24H,
    WEEK,
}

enum class QuotaStatus {
    OK,
    WARNING,
    EXCEEDED,
    UNKNOWN,
}

data class QuotaEvaluation(
    val quotaId: String,
    val status: QuotaStatus,
    val observedMinutes: Long?,
    val limitMinutes: Long,
    val reliability: UsageReliability?,
)

enum class GlobalUsageObservationStatus {
    PROCESSED,
    DISABLED,
    PERMISSION_REQUIRED,
    BASELINE_ESTABLISHED,
    RATE_LIMITED,
}

data class GlobalUsageObservationResult(
    val status: GlobalUsageObservationStatus,
    val summary: UsageSummary? = null,
)

fun interface UsageAppRefProvider {
    fun appRef(packageName: String): String
}

fun interface UsageDisplayNameProvider {
    fun displayName(packageName: String): String?
}

interface GlobalUsageConsentStore {
    fun currentConsent(): GlobalUsageConsent
    fun saveConsent(consent: GlobalUsageConsent)
}

interface GlobalUsageCursorStore {
    fun currentCursor(): GlobalUsageCursor?
    fun saveCursor(cursor: GlobalUsageCursor)
    fun clearCursor()
}

interface UsageSummaryStore {
    fun currentSummary(): UsageSummary?
    fun saveSummary(summary: UsageSummary)
    fun clearSummary()
}

interface UsageQuotaStore {
    fun currentQuotas(): List<UsageQuota>
    fun saveQuotas(quotas: List<UsageQuota>)
}

class FakeGlobalUsageConsentStore(initial: GlobalUsageConsent = GlobalUsageConsent.REVOKED) : GlobalUsageConsentStore {
    private var value = initial

    @Synchronized
    override fun currentConsent(): GlobalUsageConsent = value

    @Synchronized
    override fun saveConsent(consent: GlobalUsageConsent) {
        value = consent
    }
}

class FakeGlobalUsageCursorStore : GlobalUsageCursorStore {
    private var value: GlobalUsageCursor? = null

    @Synchronized
    override fun currentCursor(): GlobalUsageCursor? = value

    @Synchronized
    override fun saveCursor(cursor: GlobalUsageCursor) {
        value = cursor
    }

    @Synchronized
    override fun clearCursor() {
        value = null
    }
}

class FakeUsageSummaryStore : UsageSummaryStore {
    private var value: UsageSummary? = null

    @Synchronized
    override fun currentSummary(): UsageSummary? = value

    @Synchronized
    override fun saveSummary(summary: UsageSummary) {
        value = summary
    }

    @Synchronized
    override fun clearSummary() {
        value = null
    }
}

class FakeUsageQuotaStore(initial: List<UsageQuota> = emptyList()) : UsageQuotaStore {
    private var value = initial.toList()

    @Synchronized
    override fun currentQuotas(): List<UsageQuota> = value

    @Synchronized
    override fun saveQuotas(quotas: List<UsageQuota>) {
        value = quotas.toList()
    }
}

object UsageSummaryLimits {
    const val MAX_APPS = 100
    const val MAX_EVENTS_PER_QUERY = 20_000
    const val MIN_COLLECTION_INTERVAL_MILLIS = 15L * 60L * 1_000L
    const val MAX_DEFAULT_WINDOW_MILLIS = 24L * 60L * 60L * 1_000L
    const val MAX_SUPPORTED_WINDOW_MILLIS = 7L * 24L * 60L * 60L * 1_000L
    const val MAX_AGGREGATE_WINDOW_MILLIS = 31L * 24L * 60L * 60L * 1_000L
}
