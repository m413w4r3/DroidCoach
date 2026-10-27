package dev.atlas.coach.usage

/** Consent and Usage Access gate for the additive, global UsageStats observation path. */
class GlobalUsageObserver(
    private val eventSource: GlobalUsageEventSource,
    private val accessStatus: UsageAccessStatus,
    private val consentStore: GlobalUsageConsentStore,
    private val cursorStore: GlobalUsageCursorStore,
    private val summaryStore: UsageSummaryStore,
    private val appRefProvider: UsageAppRefProvider,
    private val displayNameProvider: UsageDisplayNameProvider = UsageDisplayNameProvider { null },
    private val maxRawEvents: Int = UsageSummaryLimits.MAX_EVENTS_PER_QUERY,
    private val maxWindowMillis: Long = UsageSummaryLimits.MAX_DEFAULT_WINDOW_MILLIS,
    private val minCollectionIntervalMillis: Long = UsageSummaryLimits.MIN_COLLECTION_INTERVAL_MILLIS,
) {
    init {
        require(maxRawEvents in 1..UsageSummaryLimits.MAX_EVENTS_PER_QUERY)
        require(maxWindowMillis in 1L..UsageSummaryLimits.MAX_AGGREGATE_WINDOW_MILLIS)
        require(minCollectionIntervalMillis in 1L..UsageSummaryLimits.MAX_AGGREGATE_WINDOW_MILLIS)
    }

    @Synchronized
    fun acceptConsent(consent: GlobalUsageConsent) {
        require(consent.isExplicit) { "Global usage requires explicit purpose and consent time" }
        summaryStore.clearSummary()
        cursorStore.clearCursor()
        consentStore.saveConsent(consent)
        if (accessStatus.isAllowed()) {
            cursorStore.saveCursor(GlobalUsageCursor(consent.grantedAtUtc!!))
        }
    }

    /** Revocation closes the gate first, then synchronously removes the cursor and summary. */
    @Synchronized
    fun revokeConsent(nowUtc: Long) {
        require(nowUtc >= 0L)
        val current = consentStore.currentConsent()
        consentStore.saveConsent(
            current.copy(
                granted = false,
                revokedAtUtc = nowUtc,
                consentedCategories = emptySet(),
            ),
        )
        cursorStore.clearCursor()
        summaryStore.clearSummary()
    }

    @Synchronized
    fun poll(window: UsageWindow, nowUtc: Long): GlobalUsageObservationResult {
        require(nowUtc >= 0L)
        val consent = consentStore.currentConsent()
        if (!consent.isExplicit) {
            cursorStore.clearCursor()
            summaryStore.clearSummary()
            return GlobalUsageObservationResult(GlobalUsageObservationStatus.DISABLED)
        }
        if (!accessStatus.isAllowed()) return permissionRequired()

        val cursor = cursorStore.currentCursor()
        if (cursor == null || nowUtc < cursor.baselineUtc) {
            cursorStore.saveCursor(GlobalUsageCursor(nowUtc))
            summaryStore.clearSummary()
            return GlobalUsageObservationResult(GlobalUsageObservationStatus.BASELINE_ESTABLISHED)
        }

        val lastCollection = cursor.lastCollectionAttemptUtc
        if (lastCollection != null && (nowUtc < lastCollection || nowUtc - lastCollection < minCollectionIntervalMillis)) {
            return GlobalUsageObservationResult(
                status = GlobalUsageObservationStatus.RATE_LIMITED,
                summary = summaryStore.currentSummary(),
            )
        }

        val boundedStart = maxOf(window.startUtc, window.endUtc - maxWindowMillis)
        val queryFrom = maxOf(boundedStart, cursor.baselineUtc).coerceAtMost(window.endUtc)
        val queryThrough = minOf(window.endUtc, nowUtc)
        val batch = if (queryThrough < queryFrom) {
            GlobalUsageEventBatch(emptyList(), truncated = false)
        } else {
            // Persist the attempt first so a slow or failing source cannot be hammered by repeated
            // screen resumes. The synchronized method keeps one in-flight collection per observer.
            cursorStore.saveCursor(cursor.copy(lastCollectionAttemptUtc = nowUtc))
            try {
                eventSource.query(queryFrom, queryThrough, maxRawEvents)
            } catch (_: SecurityException) {
                return permissionRequired()
            }
        }

        val summary = UsageSummaryCalculator.summarize(
            events = batch.events,
            window = window,
            observedFromUtc = queryFrom,
            observedThroughUtc = queryThrough.coerceAtLeast(queryFrom),
            generatedAtUtc = nowUtc,
            consent = consent,
            appRefProvider = appRefProvider,
            displayNameProvider = displayNameProvider,
            maxWindowMillis = maxWindowMillis,
            sourceTruncated = batch.truncated,
        )
        summaryStore.saveSummary(summary)
        return GlobalUsageObservationResult(GlobalUsageObservationStatus.PROCESSED, summary)
    }

    private fun permissionRequired(): GlobalUsageObservationResult {
        cursorStore.clearCursor()
        summaryStore.clearSummary()
        return GlobalUsageObservationResult(GlobalUsageObservationStatus.PERMISSION_REQUIRED)
    }
}
