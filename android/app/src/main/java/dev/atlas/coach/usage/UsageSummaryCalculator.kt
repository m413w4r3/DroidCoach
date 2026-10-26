package dev.atlas.coach.usage

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

object UsageWindowFactory {
    fun calendarDay(atUtc: Long, timezone: ZoneId): UsageWindow {
        val date = Instant.ofEpochMilli(atUtc).atZone(timezone).toLocalDate()
        return UsageWindow(
            startUtc = date.atStartOfDay(timezone).toInstant().toEpochMilli(),
            endUtc = date.plusDays(1).atStartOfDay(timezone).toInstant().toEpochMilli(),
            timezone = timezone,
        )
    }

    fun forQuota(quotaWindow: UsageQuotaWindow, atUtc: Long, timezone: ZoneId): UsageWindow {
        val endUtc = atUtc
        val startUtc = when (quotaWindow) {
            UsageQuotaWindow.CALENDAR_DAY ->
                Instant.ofEpochMilli(atUtc).atZone(timezone).toLocalDate().atStartOfDay(timezone)
                    .toInstant().toEpochMilli()

            UsageQuotaWindow.ROLLING_24H ->
                maxOf(0L, atUtc - UsageSummaryLimits.MAX_DEFAULT_WINDOW_MILLIS)

            UsageQuotaWindow.WEEK -> {
                val date = Instant.ofEpochMilli(atUtc).atZone(timezone).toLocalDate()
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                date.atStartOfDay(timezone).toInstant().toEpochMilli()
            }
        }
        // A quota requested at exactly local midnight still has a representable empty-ish window.
        val safeEnd = if (endUtc <= startUtc) startUtc + 1L else endUtc
        return UsageWindow(startUtc, safeEnd, timezone)
    }
}
object UsageSummaryCalculator {
    fun summarize(
        events: List<GlobalUsageEvent>,
        window: UsageWindow,
        observedFromUtc: Long,
        observedThroughUtc: Long,
        generatedAtUtc: Long,
        consent: GlobalUsageConsent,
        appRefProvider: UsageAppRefProvider,
        displayNameProvider: UsageDisplayNameProvider = UsageDisplayNameProvider { null },
        maxWindowMillis: Long = UsageSummaryLimits.MAX_DEFAULT_WINDOW_MILLIS,
        sourceTruncated: Boolean = false,
    ): UsageSummary {
        require(consent.isExplicit) { "A summary requires explicit global usage consent" }
        require(observedFromUtc >= 0L && observedThroughUtc >= 0L && generatedAtUtc >= 0L)
        require(maxWindowMillis in 1L..UsageSummaryLimits.MAX_AGGREGATE_WINDOW_MILLIS)

        val boundedStart = maxOf(window.startUtc, window.endUtc - maxWindowMillis)
        val effectiveWindow = UsageWindow(boundedStart, window.endUtc, window.timezone)
        val effectiveEnd = observedThroughUtc.coerceIn(boundedStart, window.endUtc)
        val calculation = UsageIntervalCalculator.calculate(
            events = events,
            window = effectiveWindow,
            observedThroughUtc = effectiveEnd,
            appRefForPackage = appRefProvider::appRef,
        )

        val durationByApp = calculation.intervals.groupBy(UsageInterval::appRef)
            .mapValues { (_, rows) -> rows.sumOf(UsageInterval::durationMs) }
        val reliabilityByApp = calculation.intervals.groupBy(UsageInterval::appRef)
            .mapValues { (_, rows) -> UsageReliability.values()[rows.maxOf { it.reliability.ordinal }] }
        val packageByAppRef = events.associate { appRefProvider.appRef(it.packageName) to it.packageName }
        val apps = calculation.appRefs.map { appRef ->
            val reliability = reliabilityByApp[appRef] ?: UsageReliability.LOW
            val displayName = if (consent.allowsPackageNames) {
                packageByAppRef[appRef]?.let { packageName -> runCatching { displayNameProvider.displayName(packageName) }.getOrNull() }
            } else {
                null
            }
            UsageAppSummary(
                appRef = appRef,
                displayName = displayName?.takeIf(String::isNotBlank),
                foregroundMs = durationByApp[appRef] ?: 0L,
                launchCount = calculation.launchCountsByAppRef[appRef] ?: 0,
                lastUsedAtUtc = calculation.lastUsedAtByAppRef[appRef],
                reliability = reliability,
            )
        }.sortedWith(compareByDescending<UsageAppSummary>(UsageAppSummary::foregroundMs).thenBy(UsageAppSummary::appRef))

        val boundedApps = apps.take(UsageSummaryLimits.MAX_APPS)
        val observedStart = maxOf(boundedStart, observedFromUtc).coerceAtMost(window.endUtc)
        val observedEnd = maxOf(observedStart, effectiveEnd).coerceAtMost(window.endUtc)
        val observedMs = observedEnd - observedStart
        val inWindowEvents = events.distinctBy(GlobalUsageEvent::eventId)
            .count { it.timestampUtc in boundedStart..effectiveEnd }
        val wasWindowTrimmed = window.startUtc < boundedStart
        val wereAppsTrimmed = apps.size > UsageSummaryLimits.MAX_APPS

        return UsageSummary(
            windowStart = boundedStart,
            windowEnd = window.endUtc,
            timezone = window.timezone.id,
            totalForegroundMs = apps.sumOf(UsageAppSummary::foregroundMs),
            apps = boundedApps,
            coverage = UsageSummaryCoverage(
                observedMs = observedMs,
                blindMs = calculation.blindMs.coerceAtMost(observedMs),
                eventsSeen = inWindowEvents,
                truncated = sourceTruncated || wasWindowTrimmed || wereAppsTrimmed,
            ),
            generatedAt = generatedAtUtc,
            consentVersion = consent.consentVersion,
        )
    }
}
