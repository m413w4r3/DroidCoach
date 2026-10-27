package dev.atlas.coach.usage

import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalUsageObserverTest {
    @Test
    fun consentAndUsageAccessGateEverySourceRead() {
        val harness = harness(allowed = false)
        val window = window()

        assertEquals(GlobalUsageObservationStatus.DISABLED, harness.observer.poll(window, START + HOUR).status)
        harness.observer.acceptConsent(consent())
        assertNull(harness.cursors.currentCursor())
        assertEquals(GlobalUsageObservationStatus.PERMISSION_REQUIRED, harness.observer.poll(window, START + HOUR).status)
        assertEquals(0, harness.source.queryCount)
        assertNull(harness.summaries.currentSummary())

        harness.access.allowed = true
        assertEquals(GlobalUsageObservationStatus.BASELINE_ESTABLISHED, harness.observer.poll(window, START + HOUR).status)
        assertEquals(0, harness.source.queryCount)
        assertEquals(GlobalUsageObservationStatus.PROCESSED, harness.observer.poll(window, START + HOUR + MINUTE).status)
        assertEquals(1, harness.source.queryCount)
    }

    @Test
    fun syntheticEventsAggregateAndKeepNamesPseudonymousWithoutCategoryConsent() {
        var displayNameLookups = 0
        val harness = harness(
            events = listOf(
                event("resume", START + MINUTE, GlobalUsageEventType.ACTIVITY_RESUMED),
                event("pause", START + 31L * MINUTE, GlobalUsageEventType.ACTIVITY_PAUSED),
            ),
            displayName = { displayNameLookups++; "Fixture App" },
        )
        harness.observer.acceptConsent(consent())

        val result = harness.observer.poll(window(), START + HOUR)
        val summary = result.summary

        assertEquals(GlobalUsageObservationStatus.PROCESSED, result.status)
        assertNotNull(summary)
        assertEquals(30L * MINUTE, summary!!.totalForegroundMs)
        assertEquals(2, summary.coverage.eventsSeen)
        assertTrue("app:[0-9a-f]{24}".toRegex().matches(summary.apps.single().appRef))
        assertNull(summary.apps.single().displayName)
        assertEquals(0, displayNameLookups)
        assertEquals(1, harness.source.queryCount)
    }

    @Test
    fun packageNamesAppearOnlyAfterExplicitCategoryConsent() {
        val harness = harness(
            events = listOf(
                event("resume", START + MINUTE, GlobalUsageEventType.ACTIVITY_RESUMED),
                event("pause", START + 2L * MINUTE, GlobalUsageEventType.ACTIVITY_PAUSED),
            ),
            displayName = { "Fixture App" },
        )
        harness.observer.acceptConsent(
            GlobalUsageConsent.grant(
                grantedAtUtc = START,
                purpose = "Local global app-usage summary",
                consentedCategories = setOf(GlobalUsageConsent.PACKAGE_NAMES_CATEGORY),
            ),
        )

        val result = harness.observer.poll(window(), START + HOUR)

        assertEquals("Fixture App", result.summary?.apps?.single()?.displayName)
    }

    @Test
    fun quotasReportOkWarningExceededAndUnknown() {
        val limit = 120L * MINUTE
        val quota = UsageQuota("daily", window = UsageQuotaWindow.ROLLING_24H, limitMs = limit, warningRatio = 0.8)

        assertEquals(QuotaStatus.UNKNOWN, UsageQuotaEvaluator.evaluate(quota, null).status)
        assertEquals(QuotaStatus.OK, UsageQuotaEvaluator.evaluate(quota, manualSummary(60L * MINUTE)).status)
        assertEquals(
            QuotaStatus.UNKNOWN,
            UsageQuotaEvaluator.evaluate(quota, manualSummary(60L * MINUTE, observedMs = HOUR / 2L)).status,
        )
        assertEquals(QuotaStatus.WARNING, UsageQuotaEvaluator.evaluate(quota, manualSummary(100L * MINUTE)).status)
        assertEquals(QuotaStatus.EXCEEDED, UsageQuotaEvaluator.evaluate(quota, manualSummary(limit)).status)
        assertEquals(
            QuotaStatus.UNKNOWN,
            UsageQuotaEvaluator.evaluate(quota, manualSummary(60L * MINUTE, truncated = true)).status,
        )
    }

    @Test
    fun revocationPurgesCursorAndSummaryAndClosesReadGate() {
        val harness = harness()
        harness.observer.acceptConsent(consent())
        assertEquals(GlobalUsageObservationStatus.PROCESSED, harness.observer.poll(window(), START + HOUR).status)
        assertNotNull(harness.cursors.currentCursor())
        assertNotNull(harness.summaries.currentSummary())

        harness.observer.revokeConsent(START + HOUR + 1L)

        assertFalse(harness.consents.currentConsent().granted)
        assertNull(harness.cursors.currentCursor())
        assertNull(harness.summaries.currentSummary())
        assertEquals(GlobalUsageObservationStatus.DISABLED, harness.observer.poll(window(), START + HOUR + 2L).status)
        assertEquals(1, harness.source.queryCount)
    }

    @Test
    fun rateLimitIsPersistentAndConcurrentPollsNeverOverlap() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val queries = AtomicInteger()
        val source = GlobalUsageEventSource { _, _, _ ->
            val inFlight = active.incrementAndGet()
            maxActive.updateAndGet { maxOf(it, inFlight) }
            queries.incrementAndGet()
            entered.countDown()
            try {
                check(release.await(5, TimeUnit.SECONDS))
                GlobalUsageEventBatch(emptyList(), truncated = false)
            } finally {
                active.decrementAndGet()
            }
        }
        val harness = harness(source = source)
        harness.observer.acceptConsent(consent())
        val firstError = AtomicReference<Throwable?>()
        val secondResult = AtomicReference<GlobalUsageObservationResult?>()
        val first = Thread {
            runCatching { harness.observer.poll(window(), START + MINUTE) }
                .onFailure(firstError::set)
        }
        val second = Thread {
            runCatching { harness.observer.poll(window(), START + 2L * MINUTE) }
                .onSuccess(secondResult::set)
                .onFailure(firstError::set)
        }

        first.start()
        assertTrue("first source call did not start", entered.await(5, TimeUnit.SECONDS))
        second.start()
        release.countDown()
        first.join(5_000L)
        second.join(5_000L)

        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        assertNull(firstError.get())
        assertEquals(GlobalUsageObservationStatus.RATE_LIMITED, secondResult.get()?.status)
        assertEquals(1, queries.get())
        assertEquals(1, maxActive.get())
        assertEquals(START + MINUTE, harness.cursors.currentCursor()?.lastCollectionAttemptUtc)
    }

    @Test
    fun minimumIntervalAllowsCollectionAgainAtBoundary() {
        val harness = harness()
        harness.observer.acceptConsent(consent())
        assertEquals(GlobalUsageObservationStatus.PROCESSED, harness.observer.poll(window(), START + MINUTE).status)
        assertEquals(
            GlobalUsageObservationStatus.RATE_LIMITED,
            harness.observer.poll(window(), START + MINUTE + UsageSummaryLimits.MIN_COLLECTION_INTERVAL_MILLIS - 1L).status,
        )
        assertEquals(
            GlobalUsageObservationStatus.PROCESSED,
            harness.observer.poll(window(), START + MINUTE + UsageSummaryLimits.MIN_COLLECTION_INTERVAL_MILLIS).status,
        )
        assertEquals(2, harness.source.queryCount)
    }

    private fun harness(
        allowed: Boolean = true,
        events: List<GlobalUsageEvent> = emptyList(),
        source: GlobalUsageEventSource = SyntheticSource(events),
        displayName: UsageDisplayNameProvider = UsageDisplayNameProvider { null },
    ): Harness {
        val consentStore = FakeGlobalUsageConsentStore()
        val cursorStore = FakeGlobalUsageCursorStore()
        val summaryStore = FakeUsageSummaryStore()
        val access = MutableAccess(allowed)
        val observer = GlobalUsageObserver(
            eventSource = source,
            accessStatus = access,
            consentStore = consentStore,
            cursorStore = cursorStore,
            summaryStore = summaryStore,
            appRefProvider = StableUsageAppRefProvider(ByteArray(32) { 9 }),
            displayNameProvider = displayName,
        )
        return Harness(observer, source as? SyntheticSource ?: SyntheticSource(emptyList()).also {
            it.delegateForCounting = source
        }, access, consentStore, cursorStore, summaryStore)
    }

    private fun manualSummary(
        foregroundMs: Long,
        truncated: Boolean = false,
        observedMs: Long = HOUR,
    ) = UsageSummary(
        windowStart = START,
        windowEnd = START + HOUR,
        timezone = "UTC",
        totalForegroundMs = foregroundMs,
        apps = listOf(
            UsageAppSummary(
                appRef = appRefs.appRef(PACKAGE),
                foregroundMs = foregroundMs,
                launchCount = 1,
                reliability = UsageReliability.HIGH,
            ),
        ),
        coverage = UsageSummaryCoverage(observedMs, 0L, 2, truncated),
        generatedAt = START + HOUR,
        consentVersion = 1,
    )

    private fun consent() = GlobalUsageConsent.grant(START, "Local global app-usage summary")

    private fun window() = UsageWindow(START, START + HOUR, ZoneId.of("UTC"))

    private fun event(id: String, timestamp: Long, type: GlobalUsageEventType) =
        GlobalUsageEvent(id, PACKAGE, timestamp, type)

    private class SyntheticSource(private val events: List<GlobalUsageEvent>) : GlobalUsageEventSource {
        var delegateForCounting: GlobalUsageEventSource? = null
        var queryCount = 0
            private set

        override fun query(fromUtc: Long, throughUtc: Long, maxEvents: Int): GlobalUsageEventBatch {
            queryCount++
            return delegateForCounting?.query(fromUtc, throughUtc, maxEvents)
                ?: GlobalUsageEventBatch(events.filter { it.timestampUtc in fromUtc..throughUtc }.take(maxEvents), false)
        }
    }

    private class MutableAccess(var allowed: Boolean) : UsageAccessStatus {
        override fun isAllowed(): Boolean = allowed
    }

    private data class Harness(
        val observer: GlobalUsageObserver,
        val source: SyntheticSource,
        val access: MutableAccess,
        val consents: FakeGlobalUsageConsentStore,
        val cursors: FakeGlobalUsageCursorStore,
        val summaries: FakeUsageSummaryStore,
    )

    private companion object {
        const val PACKAGE = "dev.atlas.synthetic.usage"
        const val START = 1_800_000_000_000L
        const val MINUTE = 60_000L
        const val HOUR = 60L * MINUTE
        val appRefs = StableUsageAppRefProvider(ByteArray(32) { 11 })
    }
}
