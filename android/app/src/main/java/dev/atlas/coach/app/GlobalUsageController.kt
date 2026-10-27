package dev.atlas.coach.app

import dev.atlas.coach.usage.GlobalUsageConsent
import dev.atlas.coach.usage.GlobalUsageConsentStore
import dev.atlas.coach.usage.GlobalUsageObservationStatus
import dev.atlas.coach.usage.GlobalUsageObserver
import dev.atlas.coach.usage.QuotaEvaluation
import dev.atlas.coach.usage.QuotaStatus
import dev.atlas.coach.usage.UsageAccessStatus
import dev.atlas.coach.usage.UsageQuota
import dev.atlas.coach.usage.UsageQuotaEvaluator
import dev.atlas.coach.usage.UsageQuotaWindow
import dev.atlas.coach.usage.UsageReliability
import dev.atlas.coach.usage.UsageSummary
import dev.atlas.coach.usage.UsageSummaryStore
import dev.atlas.coach.usage.UsageWindowFactory
import dev.atlas.coach.usage.android.AndroidGlobalUsageEventSource
import dev.atlas.coach.usage.android.AndroidUsageAccessStatus
import dev.atlas.coach.usage.android.AndroidUsageAppRefProvider
import dev.atlas.coach.usage.android.AndroidUsageDisplayNameProvider
import dev.atlas.coach.usage.android.SharedPreferencesGlobalUsageStores
import android.app.usage.UsageStatsManager
import android.content.Context
import java.time.ZoneId

data class GlobalUsageScreenState(
    val hasGlobalConsent: Boolean,
    val packageNamesConsented: Boolean,
    val usageAccessGranted: Boolean,
    val summary: UsageSummary?,
    val quotas: List<QuotaEvaluation>,
    val observationStatus: GlobalUsageObservationStatus?,
)

/** User-triggered coordinator for the independent global summary path. */
class GlobalUsageController(
    private val observer: GlobalUsageObserver,
    private val accessStatus: UsageAccessStatus,
    private val consentStore: GlobalUsageConsentStore,
    private val summaryStore: UsageSummaryStore,
    private val quotas: () -> List<UsageQuota> = { emptyList() },
    private val clockUtc: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private var lastObservationStatus: GlobalUsageObservationStatus? = null

    @Synchronized
    fun state(): GlobalUsageScreenState {
        val consent = consentStore.currentConsent()
        val hasConsent = consent.isExplicit
        val storedSummary = if (hasConsent) {
            summaryStore.currentSummary()?.takeIf { it.consentVersion == consent.consentVersion }
        } else {
            null
        }
        val visibleSummary = storedSummary?.let { summary ->
            if (consent.allowsPackageNames) summary else {
                summary.copy(apps = summary.apps.map { it.copy(displayName = null) })
            }
        }
        val quotaRows = quotas().map { quota ->
            val summaryMatchesQuotaWindow = visibleSummary?.let { summary ->
                val expected = UsageWindowFactory.forQuota(quota.window, summary.generatedAt, ZoneId.of(summary.timezone))
                summary.windowStart == expected.startUtc && summary.windowEnd == expected.endUtc
            } ?: false
            if (summaryMatchesQuotaWindow) {
                UsageQuotaEvaluator.evaluate(quota, visibleSummary)
            } else {
                unknownQuota(quota)
            }
        }
        return GlobalUsageScreenState(
            hasGlobalConsent = hasConsent,
            packageNamesConsented = consent.allowsPackageNames,
            usageAccessGranted = accessStatus.isAllowed(),
            summary = visibleSummary,
            quotas = quotaRows,
            observationStatus = lastObservationStatus,
        )
    }

    @Synchronized
    fun grantConsent(includePackageNames: Boolean) {
        val categories = if (includePackageNames) {
            setOf(GlobalUsageConsent.PACKAGE_NAMES_CATEGORY)
        } else {
            emptySet()
        }
        observer.acceptConsent(
            GlobalUsageConsent.grant(
                grantedAtUtc = clockUtc(),
                purpose = CONSENT_PURPOSE,
                consentedCategories = categories,
            ),
        )
        lastObservationStatus = null
    }

    @Synchronized
    fun revokeConsent() {
        observer.revokeConsent(clockUtc())
        lastObservationStatus = GlobalUsageObservationStatus.DISABLED
    }

    /** One explicit refresh; this class does not schedule work or observe composition lifecycle. */
    @Synchronized
    fun refresh(): GlobalUsageScreenState {
        val now = clockUtc()
        val timezone = zone()
        val configuredWindows = quotas().filter(UsageQuota::enabled).map(UsageQuota::window).distinct()
        val selectedWindow = configuredWindows.singleOrNull() ?: UsageQuotaWindow.ROLLING_24H
        val result = observer.poll(
            window = UsageWindowFactory.forQuota(selectedWindow, now, timezone),
            nowUtc = now,
        )
        lastObservationStatus = result.status
        return state()
    }

    companion object {
        fun fromContext(context: Context): GlobalUsageController {
            val appContext = context.applicationContext
            val stores = SharedPreferencesGlobalUsageStores(appContext)
            val accessStatus = AndroidUsageAccessStatus(appContext)
            val usageStats = appContext.getSystemService(UsageStatsManager::class.java)
                ?: error("UsageStatsManager is unavailable")
            val observer = GlobalUsageObserver(
                eventSource = AndroidGlobalUsageEventSource(usageStats),
                accessStatus = accessStatus,
                consentStore = stores,
                cursorStore = stores,
                summaryStore = stores,
                appRefProvider = AndroidUsageAppRefProvider(appContext),
                displayNameProvider = AndroidUsageDisplayNameProvider(appContext),
            )
            return GlobalUsageController(observer, accessStatus, stores, stores)
        }

        const val CONSENT_PURPOSE = "Local global app-usage summary and quota display"

        fun unknownQuota(quota: UsageQuota) = QuotaEvaluation(
            quotaId = quota.quotaId,
            status = QuotaStatus.UNKNOWN,
            observedMinutes = null,
            limitMinutes = quota.limitMs / MILLIS_PER_MINUTE,
            reliability = UsageReliability.LOW,
        )

        const val MILLIS_PER_MINUTE = 60_000L
    }
}
