package dev.atlas.coach.usage

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalUsageSummaryTest {
    @Test
    fun pairingComputesExactDuration() {
        val result = calculate(
            listOf(
                event("resume", FIXTURE, 1_000L, GlobalUsageEventType.ACTIVITY_RESUMED),
                event("pause", FIXTURE, 76_000L, GlobalUsageEventType.ACTIVITY_PAUSED),
            ),
            start = 0L,
            end = 100_000L,
            observedThrough = 100_000L,
        )

        assertEquals(75_000L, result.intervals.single().durationMs)
        assertEquals(UsageReliability.HIGH, result.intervals.single().reliability)
    }

    @Test
    fun consecutiveResumesDoNotDoubleCount() {
        val result = calculate(
            listOf(
                event("resume-1", FIXTURE, 1_000L, GlobalUsageEventType.ACTIVITY_RESUMED),
                event("resume-2", FIXTURE, 2_000L, GlobalUsageEventType.ACTIVITY_RESUMED),
                event("pause", FIXTURE, 6_000L, GlobalUsageEventType.ACTIVITY_PAUSED),
            ),
            start = 0L,
            end = 10_000L,
            observedThrough = 10_000L,
        )

        assertEquals(1, result.intervals.size)
        assertEquals(1, result.launchCountsByAppRef.values.single())
        assertEquals(5_000L, result.intervals.single().durationMs)
    }

    @Test
    fun missingPauseIsCappedAndMarkedLowReliability() {
        val result = calculate(
            listOf(event("resume", FIXTURE, 2_000L, GlobalUsageEventType.ACTIVITY_RESUMED)),
            start = 1_000L,
            end = 20_000L,
            observedThrough = 5_000L,
        )

        assertEquals(3_000L, result.intervals.single().durationMs)
        assertEquals(UsageReliability.LOW, result.intervals.single().reliability)
        assertEquals(3_000L, result.blindMs)
    }

    @Test
    fun eventsOutsideWindowAreTrimmed() {
        val result = calculate(
            listOf(
                event("resume-before", FIXTURE, 500L, GlobalUsageEventType.ACTIVITY_RESUMED),
                event("pause-after", FIXTURE, 6_000L, GlobalUsageEventType.ACTIVITY_PAUSED),
            ),
            start = 1_000L,
            end = 5_000L,
            observedThrough = 5_000L,
        )

        assertEquals(1_000L, result.intervals.single().startUtc)
        assertEquals(5_000L, result.intervals.single().endUtc)
        assertEquals(4_000L, result.intervals.single().durationMs)
        assertEquals(UsageReliability.LOW, result.intervals.single().reliability)
    }

    @Test
    fun midnightWindowSplitsCorrectly() {
        val zone = ZoneId.of("UTC")
        val firstDay = Instant.parse("2026-04-01T00:00:00Z").toEpochMilli()
        val midnight = firstDay + DAY
        val events = listOf(
            event("resume", FIXTURE, midnight - MINUTE, GlobalUsageEventType.ACTIVITY_RESUMED),
            event("pause", FIXTURE, midnight + MINUTE, GlobalUsageEventType.ACTIVITY_PAUSED),
        )
        val dayOne = UsageWindowFactory.calendarDay(firstDay + 12 * HOUR, zone)
        val dayTwo = UsageWindowFactory.calendarDay(midnight + 12 * HOUR, zone)
        val first = calculate(events, dayOne.startUtc, dayOne.endUtc, dayOne.endUtc)
        val second = calculate(events, dayTwo.startUtc, dayTwo.endUtc, dayTwo.endUtc)

        assertEquals(60_000L, first.intervals.single().durationMs)
        assertEquals(60_000L, second.intervals.single().durationMs)
        assertEquals(midnight, dayOne.endUtc)
        assertEquals(midnight, dayTwo.startUtc)
    }

    @Test
    fun dstSpringAndFallProduceNonNegativeDurations() {
        val zone = ZoneId.of("America/New_York")
        val spring = UsageWindowFactory.calendarDay(Instant.parse("2026-03-08T16:00:00Z").toEpochMilli(), zone)
        val fall = UsageWindowFactory.calendarDay(Instant.parse("2026-11-01T16:00:00Z").toEpochMilli(), zone)
        val springDuration = spring.endUtc - spring.startUtc
        val fallDuration = fall.endUtc - fall.startUtc
        assertEquals(23L, springDuration / HOUR)
        assertEquals(25L, fallDuration / HOUR)

        for (window in listOf(spring, fall)) {
            val rows = calculate(
                listOf(
                    event("resume-${window.startUtc}", FIXTURE, window.startUtc + 1_000L, GlobalUsageEventType.ACTIVITY_RESUMED),
                    event("pause-${window.startUtc}", FIXTURE, window.startUtc + 61_000L, GlobalUsageEventType.ACTIVITY_PAUSED),
                ),
                window.startUtc,
                window.endUtc,
                window.endUtc,
            )
            assertTrue(rows.intervals.all { it.durationMs >= 0L })
        }
    }

    @Test
    fun emptyWindowProducesZeroSummary() {
        val window = UsageWindow(START, START + HOUR, ZoneId.of("UTC"))
        val summary = summarize(emptyList(), window, START, window.endUtc)

        assertEquals(0L, summary.totalForegroundMs)
        assertTrue(summary.apps.isEmpty())
        assertEquals(HOUR, summary.coverage.observedMs)
        assertFalse(summary.coverage.truncated)
    }

    @Test
    fun largeWindowIsBoundedAndTruncated() {
        val window = UsageWindow(START, START + 40L * DAY, ZoneId.of("UTC"))
        val summary = summarize(
            events = emptyList(),
            window = window,
            observedFrom = window.startUtc,
            observedThrough = window.endUtc,
            maxWindowMillis = UsageSummaryLimits.MAX_AGGREGATE_WINDOW_MILLIS,
        )

        assertEquals(window.endUtc - UsageSummaryLimits.MAX_AGGREGATE_WINDOW_MILLIS, summary.windowStart)
        assertEquals(UsageSummaryLimits.MAX_AGGREGATE_WINDOW_MILLIS, summary.windowEnd - summary.windowStart)
        assertTrue(summary.coverage.truncated)
    }

    @Test
    fun pseudonymousAppRefIsStable() {
        val first = StableUsageAppRefProvider(ByteArray(32) { 7 })
        val sameInstallation = StableUsageAppRefProvider(ByteArray(32) { 7 })
        val anotherInstallation = StableUsageAppRefProvider(ByteArray(32) { 8 })

        val appRef = first.appRef(FIXTURE)
        assertEquals(appRef, sameInstallation.appRef(FIXTURE))
        assertFalse(appRef == anotherInstallation.appRef(FIXTURE))
        assertTrue(Regex("app:[0-9a-f]{24}").matches(appRef))
    }

    @Test
    fun packageNamesOmittedWithoutConsent() {
        var lookups = 0
        val window = UsageWindow(START, START + HOUR, ZoneId.of("UTC"))
        val summary = UsageSummaryCalculator.summarize(
            events = listOf(
                event("resume", FIXTURE, START + 1_000L, GlobalUsageEventType.ACTIVITY_RESUMED),
                event("pause", FIXTURE, START + 2_000L, GlobalUsageEventType.ACTIVITY_PAUSED),
            ),
            window = window,
            observedFromUtc = START,
            observedThroughUtc = window.endUtc,
            generatedAtUtc = window.endUtc,
            consent = consent(),
            appRefProvider = appRefs,
            displayNameProvider = UsageDisplayNameProvider { lookups++; FIXTURE },
        )

        assertEquals(0, lookups)
        assertNull(summary.apps.single().displayName)
        assertFalse(UsageSummaryJson.serialize(summary, consent()).contains(FIXTURE))
        val namesConsent = GlobalUsageConsent.grant(
            grantedAtUtc = START,
            purpose = "Local usage summary",
            consentedCategories = setOf(GlobalUsageConsent.PACKAGE_NAMES_CATEGORY),
        )
        val namedSummary = summary.copy(apps = listOf(summary.apps.single().copy(displayName = "Fixture app")))
        assertTrue(UsageSummaryJson.serialize(namedSummary, namesConsent).contains("Fixture app"))
        assertThrows(IllegalArgumentException::class.java) {
            UsageSummaryJson.serialize(
                summary.copy(apps = listOf(summary.apps.single().copy(displayName = "Private label"))),
                consent(),
            )
        }
    }

    @Test
    fun revocationClearsSummaryAndCursorImmediately() {
        val harness = observerHarness()
        harness.observer.acceptConsent(consent())
        val window = UsageWindow(START, START + HOUR, ZoneId.of("UTC"))
        assertEquals(GlobalUsageObservationStatus.PROCESSED, harness.observer.poll(window, window.endUtc).status)
        assertNotNull(harness.cursors.currentCursor())
        assertNotNull(harness.summaries.currentSummary())

        harness.observer.revokeConsent(window.endUtc + 1L)

        assertFalse(harness.consents.currentConsent().granted)
        assertEquals(window.endUtc + 1L, harness.consents.currentConsent().revokedAtUtc)
        assertNull(harness.cursors.currentCursor())
        assertNull(harness.summaries.currentSummary())
        assertEquals(GlobalUsageObservationStatus.DISABLED, harness.observer.poll(window, window.endUtc + 2L).status)
        assertEquals(1, harness.source.queryCount)
    }

    @Test
    fun permissionDeniedStopsObservationAndDropsCursor() {
        val harness = observerHarness(hasAccess = false)
        harness.observer.acceptConsent(consent())
        assertNull(harness.cursors.currentCursor())
        val window = UsageWindow(START, START + HOUR, ZoneId.of("UTC"))

        assertEquals(GlobalUsageObservationStatus.PERMISSION_REQUIRED, harness.observer.poll(window, window.endUtc).status)
        assertEquals(0, harness.source.queryCount)
        assertNull(harness.cursors.currentCursor())
        assertNull(harness.summaries.currentSummary())

        harness.access.allowed = true
        assertEquals(GlobalUsageObservationStatus.BASELINE_ESTABLISHED, harness.observer.poll(window, window.endUtc + 1L).status)
        assertEquals(0, harness.source.queryCount)
    }

    @Test
    fun consentBaselineDoesNotBackfill() {
        val baseline = START + 5_000L
        val source = FakeGlobalEventSource(
            listOf(
                event("old-resume", FIXTURE, START + 1_000L, GlobalUsageEventType.ACTIVITY_RESUMED),
                event("old-pause", FIXTURE, START + 2_000L, GlobalUsageEventType.ACTIVITY_PAUSED),
                event("new-resume", FIXTURE, START + 10_000L, GlobalUsageEventType.ACTIVITY_RESUMED),
                event("new-pause", FIXTURE, START + 20_000L, GlobalUsageEventType.ACTIVITY_PAUSED),
            ),
        )
        val consents = FakeGlobalUsageConsentStore()
        val cursors = FakeGlobalUsageCursorStore()
        val summaries = FakeUsageSummaryStore()
        val observer = GlobalUsageObserver(source, MutableAccess(true), consents, cursors, summaries, appRefs)
        observer.acceptConsent(consent(baseline))

        val result = observer.poll(UsageWindow(START, START + HOUR, ZoneId.of("UTC")), START + 30_000L)

        assertEquals(baseline, source.lastFromUtc)
        assertEquals(10_000L, result.summary?.totalForegroundMs)
        assertEquals(2, result.summary?.coverage?.eventsSeen)
    }

    @Test
    fun quotaWarningAndExceededThresholds() {
        val summary = manualSummary(listOf(app("app:a", 50L * MINUTE)))
        val quota = quota("daily", appRef = "app:a", limit = 100L * MINUTE)

        assertEquals(QuotaStatus.WARNING, UsageQuotaEvaluator.evaluate(quota, summary).status)
        assertEquals(50L, UsageQuotaEvaluator.evaluate(quota, summary).observedMinutes)
        assertEquals(
            QuotaStatus.EXCEEDED,
            UsageQuotaEvaluator.evaluate(quota, manualSummary(listOf(app("app:a", 100L * MINUTE)))).status,
        )
    }

    @Test
    fun globalQuotaAggregatesAcrossApps() {
        val summary = manualSummary(
            listOf(
                app("app:111111111111111111111111", 30L * MINUTE),
                app("app:222222222222222222222222", 30L * MINUTE),
            ),
        )
        val quota = quota("global", appRef = null, limit = 50L * MINUTE)

        val evaluation = UsageQuotaEvaluator.evaluate(quota, summary)
        assertEquals(QuotaStatus.EXCEEDED, evaluation.status)
        assertEquals(60L, evaluation.observedMinutes)
    }

    @Test
    fun summaryJsonContainsNoContentOrPrivateData() {
        val summary = UsageSummaryCalculator.summarize(
            events = listOf(
                event("resume-private", FIXTURE, START + 1_000L, GlobalUsageEventType.ACTIVITY_RESUMED),
                event("pause-private", FIXTURE, START + 2_000L, GlobalUsageEventType.ACTIVITY_PAUSED),
            ),
            window = UsageWindow(START, START + HOUR, ZoneId.of("UTC")),
            observedFromUtc = START,
            observedThroughUtc = START + HOUR,
            generatedAtUtc = START + HOUR,
            consent = consent(),
            appRefProvider = appRefs,
        )

        val json = UsageSummaryJson.serialize(summary, consent())
        assertTrue(json.contains("\"window_start\""))
        assertTrue(json.contains("\"coverage\""))
        assertFalse(json.contains(FIXTURE))
        assertFalse(json.contains("package_name"))
        assertFalse(json.contains("notification"))
        assertFalse(json.contains("screen_content"))
        assertFalse(json.contains("https://"))
    }

    @Test
    fun disabledObserverDoesNotReadOrKeepCursor() {
        val harness = observerHarness(initialConsent = GlobalUsageConsent.REVOKED)
        harness.cursors.saveCursor(GlobalUsageCursor(START))
        val result = harness.observer.poll(UsageWindow(START, START + HOUR, ZoneId.of("UTC")), START + HOUR)

        assertEquals(GlobalUsageObservationStatus.DISABLED, result.status)
        assertEquals(0, harness.source.queryCount)
        assertNull(harness.cursors.currentCursor())
    }

    private fun calculate(
        events: List<GlobalUsageEvent>,
        start: Long,
        end: Long,
        observedThrough: Long,
    ): UsageIntervalCalculation = UsageIntervalCalculator.calculate(
        events = events,
        window = UsageWindow(start, end, ZoneId.of("UTC")),
        observedThroughUtc = observedThrough,
        appRefForPackage = appRefs::appRef,
    )

    private fun summarize(
        events: List<GlobalUsageEvent>,
        window: UsageWindow,
        observedFrom: Long,
        observedThrough: Long,
        maxWindowMillis: Long = UsageSummaryLimits.MAX_DEFAULT_WINDOW_MILLIS,
    ): UsageSummary = UsageSummaryCalculator.summarize(
        events = events,
        window = window,
        observedFromUtc = observedFrom,
        observedThroughUtc = observedThrough,
        generatedAtUtc = observedThrough,
        consent = consent(),
        appRefProvider = appRefs,
        maxWindowMillis = maxWindowMillis,
    )

    private fun observerHarness(
        hasAccess: Boolean = true,
        initialConsent: GlobalUsageConsent = GlobalUsageConsent.REVOKED,
    ): ObserverHarness {
        val source = FakeGlobalEventSource(emptyList())
        val consents = FakeGlobalUsageConsentStore(initialConsent)
        val cursors = FakeGlobalUsageCursorStore()
        val summaries = FakeUsageSummaryStore()
        val access = MutableAccess(hasAccess)
        val observer = GlobalUsageObserver(source, access, consents, cursors, summaries, appRefs)
        return ObserverHarness(observer, source, access, consents, cursors, summaries)
    }

    private fun manualSummary(apps: List<UsageAppSummary>) = UsageSummary(
        windowStart = START,
        windowEnd = START + DAY,
        timezone = "UTC",
        totalForegroundMs = apps.sumOf(UsageAppSummary::foregroundMs),
        apps = apps,
        coverage = UsageSummaryCoverage(observedMs = DAY, blindMs = 0L, eventsSeen = 0, truncated = false),
        generatedAt = START + DAY,
        consentVersion = 1,
    )

    private fun app(appRef: String, foregroundMs: Long) = UsageAppSummary(
        appRef = appRef,
        foregroundMs = foregroundMs,
        launchCount = 1,
        reliability = UsageReliability.HIGH,
    )

    private fun quota(id: String, appRef: String?, limit: Long) = UsageQuota(
        quotaId = id,
        appRef = appRef,
        window = UsageQuotaWindow.CALENDAR_DAY,
        limitMs = limit,
        warningRatio = 0.5,
    )

    private fun consent(atUtc: Long = START) = GlobalUsageConsent.grant(
        grantedAtUtc = atUtc,
        purpose = "Local usage summary",
    )

    private fun event(id: String, packageName: String, timestamp: Long, type: GlobalUsageEventType) =
        GlobalUsageEvent(id, packageName, timestamp, type)

    private data class ObserverHarness(
        val observer: GlobalUsageObserver,
        val source: FakeGlobalEventSource,
        val access: MutableAccess,
        val consents: FakeGlobalUsageConsentStore,
        val cursors: FakeGlobalUsageCursorStore,
        val summaries: FakeUsageSummaryStore,
    )

    private class FakeGlobalEventSource(private val allEvents: List<GlobalUsageEvent>) : GlobalUsageEventSource {
        var queryCount = 0
            private set
        var lastFromUtc: Long? = null
            private set

        override fun query(fromUtc: Long, throughUtc: Long, maxEvents: Int): GlobalUsageEventBatch {
            queryCount++
            lastFromUtc = fromUtc
            val matching = allEvents.filter { it.timestampUtc in fromUtc..throughUtc }
            return GlobalUsageEventBatch(matching.take(maxEvents), matching.size > maxEvents)
        }
    }

    private class MutableAccess(var allowed: Boolean) : UsageAccessStatus {
        override fun isAllowed(): Boolean = allowed
    }

    private companion object {
        const val FIXTURE = "dev.atlas.synthetic.distraction"
        const val START = 1_800_000_000_000L
        const val MINUTE = 60_000L
        const val HOUR = 60L * MINUTE
        const val DAY = 24L * HOUR

        val appRefs = StableUsageAppRefProvider(ByteArray(32) { 3 })
    }
}
