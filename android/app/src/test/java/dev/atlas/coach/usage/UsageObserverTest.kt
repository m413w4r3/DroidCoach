package dev.atlas.coach.usage

import dev.atlas.coach.focus.FakeClock
import dev.atlas.coach.rules.AppEventSimulator
import dev.atlas.coach.rules.FrictionPresentationResult
import dev.atlas.coach.rules.Intervention
import dev.atlas.coach.rules.LocalPolicy
import dev.atlas.coach.rules.RuleEvaluator
import dev.atlas.coach.rules.SafetyDenylist
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageObserverTest {
    @Test
    fun fourConsentAllowedLaunchesCreateOneInterventionAndVisibleProposal() {
        val harness = harness(allowlist = setOf(FIXTURE))
        harness.observer.acceptConsent(consent(FIXTURE), START)
        addLaunchesAndHomeTransitions(harness.source, FIXTURE, count = 4, first = START + MINUTE)

        val result = harness.observer.poll(START + 10 * MINUTE)
        assertEquals(UsagePollStatus.PROCESSED, result.status)
        assertEquals(4, result.acceptedEvents.size)
        assertEquals(1, result.interventions.size)
        assertEquals(1, harness.presented.size)
        assertEquals(4, harness.records.allRecords().size)
        assertEquals(listOf(FIXTURE), harness.records.allRecords().map { it.packageName }.distinct())
    }

    @Test
    fun repeatedActivityResumesAndFastNotificationShadeReturnAreDeduplicated() {
        val harness = harness(allowlist = setOf(FIXTURE))
        harness.observer.acceptConsent(consent(FIXTURE), START)
        harness.source.simulateLaunches(FIXTURE, 1, 0, START + 1_000)
        harness.source.simulateLaunches(FIXTURE, 1, 0, START + 2_000)
        harness.source.simulateLaunches("com.android.systemui", 1, 0, START + 3_000)
        harness.source.simulateLaunches(FIXTURE, 1, 0, START + 4_000)

        val result = harness.observer.poll(START + 10_000)
        assertEquals(1, result.acceptedEvents.size)
        assertEquals(1, harness.records.allRecords().size)
    }

    @Test
    fun outOfAllowlistExcludedAndProtectedPackagesAreNotRecordedOrEvaluated() {
        val harness = harness(allowlist = setOf(FIXTURE), policy = LocalPolicy(
            openThreshold = 1,
            excludedPackageNames = setOf("dev.example.exception"),
        ))
        harness.observer.acceptConsent(consent(FIXTURE), START)
        harness.source.simulateLaunches("dev.example.other", 1, 0, START + 1_000)
        harness.source.simulateLaunches("dev.example.exception", 1, 0, START + 2_000)
        harness.source.simulateLaunches("com.android.settings", 1, 0, START + 3_000)
        harness.source.simulateLaunches(FIXTURE, 1, 0, START + 4_000)

        val result = harness.observer.poll(START + 10_000)
        assertEquals(listOf(FIXTURE), result.acceptedEvents.map { it.packageName })
        assertEquals(1, result.interventions.size)
        assertEquals(listOf(FIXTURE), harness.records.allRecords().map { it.packageName })
        assertTrue(SafetyDenylist.isProtected("com.android.settings"))
    }

    @Test
    fun permissionDeniedOrLostNeverQueriesTheSource() {
        val harness = harness(allowlist = setOf(FIXTURE), hasAccess = false)
        harness.observer.acceptConsent(consent(FIXTURE), START)
        assertEquals(UsagePollStatus.PERMISSION_REQUIRED, harness.observer.poll(START + 1_000).status)
        assertEquals(0, harness.source.queryCount)
        assertNull(harness.cursors.currentCursor())

        harness.access.allowed = true
        assertEquals(UsagePollStatus.BASELINE_ESTABLISHED, harness.observer.poll(START + 2_000).status)
        assertEquals(0, harness.source.queryCount)
    }

    @Test
    fun permissionRevokedDuringUsageStatsQueryStopsWithoutCrashingOrKeepingCursor() {
        val harness = harness(allowlist = setOf(FIXTURE))
        harness.observer.acceptConsent(consent(FIXTURE), START)
        harness.source.throwSecurityException = true

        assertEquals(UsagePollStatus.PERMISSION_REQUIRED, harness.observer.poll(START + 1_000).status)
        assertNull(harness.cursors.currentCursor())
        assertTrue(harness.records.allRecords().isEmpty())
        assertTrue(harness.rules.snapshot().recentEventsByRuleAndPackage.isEmpty())
    }

    @Test
    fun revokedConsentStopsQueriesAndPurgesCursorCountersAndHistoryImmediately() {
        val harness = harness(allowlist = setOf(FIXTURE))
        harness.observer.acceptConsent(consent(FIXTURE), START)
        addLaunchesAndHomeTransitions(harness.source, FIXTURE, count = 3, first = START + MINUTE)
        harness.observer.poll(START + 10 * MINUTE)
        assertEquals(3, harness.records.allRecords().size)
        assertTrue(harness.rules.snapshot().recentEventsByRuleAndPackage.isNotEmpty())

        harness.observer.revokeConsent()
        val queryCount = harness.source.queryCount
        assertEquals(UsagePollStatus.DISABLED, harness.observer.poll(START + 11 * MINUTE).status)
        assertEquals(queryCount, harness.source.queryCount)
        assertEquals(ObservationConsent.REVOKED, harness.consents.currentConsent())
        assertNull(harness.cursors.currentCursor())
        assertTrue(harness.records.allRecords().isEmpty())
        assertTrue(harness.rules.snapshot().recentEventsByRuleAndPackage.isEmpty())
    }

    @Test
    fun consentRequiresExplicitPurposeAndCannotIncludeAProtectedPackage() {
        val harness = harness(allowlist = setOf(FIXTURE))
        assertThrows(IllegalArgumentException::class.java) {
            harness.observer.acceptConsent(ObservationConsent(enabled = true), START)
        }
        assertThrows(IllegalArgumentException::class.java) {
            harness.observer.acceptConsent(consent("com.android.settings"), START)
        }
    }

    @Test
    fun pollingIsThrottledAndCsvExportContainsOnlyPackageAndTime() {
        val harness = harness(allowlist = setOf(FIXTURE))
        harness.observer.acceptConsent(consent(FIXTURE), START)
        addLaunchesAndHomeTransitions(harness.source, FIXTURE, count = 1, first = START + 1_000)
        assertEquals(UsagePollStatus.PROCESSED, harness.observer.poll(START + 2_000).status)
        assertEquals(UsagePollStatus.THROTTLED, harness.observer.poll(START + 3_000).status)
        harness.observer.poll(START + 12_000)

        val csv = harness.observer.exportHistoryCsv()
        assertTrue(csv.startsWith("event_id,package_name,timestamp_utc"))
        assertTrue(csv.contains(FIXTURE))
        assertFalse(csv.contains("notification"))
        assertFalse(csv.contains("https://"))
        harness.observer.deleteHistory()
        assertEquals("event_id,package_name,timestamp_utc\n", harness.observer.exportHistoryCsv())
    }

    @Test
    fun sourceRetentionIsBoundedByAgeAndCount() {
        val records = InMemoryObservationRecordStore()
        repeat(UsageRetention.MAX_STORED_EVENTS + 5) { index ->
            records.appendRecord(ObservationRecord("$index", FIXTURE, index.toLong()))
        }
        assertEquals(UsageRetention.MAX_STORED_EVENTS, records.allRecords().size)
        records.pruneBefore(490L)
        assertTrue(records.allRecords().all { it.timestampUtc >= 490L })
    }

    private fun addLaunchesAndHomeTransitions(
        source: CountingSimulatedSource,
        packageName: String,
        count: Int,
        first: Long,
    ) {
        repeat(count) { index ->
            val launchTime = first + index * 2 * MINUTE
            source.simulateLaunches(packageName, 1, 0, launchTime)
            source.simulateLaunches("dev.example.home", 1, 0, launchTime + MINUTE)
        }
    }

    private fun consent(vararg packages: String) = ObservationConsent.grant(
        ObservationPurpose.GENTLE_FRICTION,
        packages.toSet(),
        START,
    )

    private fun harness(
        allowlist: Set<String>,
        hasAccess: Boolean = true,
        policy: LocalPolicy = LocalPolicy(openThreshold = 4, allowlistedPackageNames = allowlist),
    ): Harness {
        val clock = FakeClock(startUtcMillis = START)
        var id = 0
        val source = CountingSimulatedSource(SimulatedUsageEventSource(AppEventSimulator(clock) { "sim-${id++}" }))
        val access = MutableAccess(hasAccess)
        val consents = InMemoryObservationConsentStore()
        val cursors = InMemoryUsageCursorStore()
        val state = InMemoryUsageObserverStateStore()
        val records = InMemoryObservationRecordStore()
        val rules = RuleEvaluator(ZoneId.of("UTC"))
        val presented = mutableListOf<Intervention>()
        val observer = UsageObserver(
            eventSource = source,
            accessStatus = access,
            consentStore = consents,
            cursorStore = cursors,
            stateStore = state,
            recordStore = records,
            ruleEvaluator = rules,
            policyProvider = { policy },
            frictionPresenter = { intervention ->
                presented += intervention
                FrictionPresentationResult.POSTED
            },
            platformProtectedPackages = { setOf("dev.example.home") },
        )
        return Harness(observer, source, access, consents, cursors, records, rules, presented)
    }

    private data class Harness(
        val observer: UsageObserver,
        val source: CountingSimulatedSource,
        val access: MutableAccess,
        val consents: InMemoryObservationConsentStore,
        val cursors: InMemoryUsageCursorStore,
        val records: InMemoryObservationRecordStore,
        val rules: RuleEvaluator,
        val presented: MutableList<Intervention>,
    )

    private class CountingSimulatedSource(private val delegate: SimulatedUsageEventSource) : UsageEventSource {
        var queryCount = 0
            private set
        var throwSecurityException = false
        fun simulateLaunches(packageName: String, count: Int, intervalSeconds: Long, time: Long) =
            delegate.simulateLaunches(packageName, count, intervalSeconds, time)
        override fun query(cursor: UsageCursor, throughUtc: Long, maxRawEvents: Int): UsageEventBatch {
            queryCount++
            if (throwSecurityException) throw SecurityException("Usage access revoked")
            return delegate.query(cursor, throughUtc, maxRawEvents)
        }
    }

    private class MutableAccess(var allowed: Boolean) : UsageAccessStatus {
        override fun isAllowed() = allowed
    }

    companion object {
        private const val FIXTURE = "dev.atlas.fixture.distraction"
        private const val START = 1_800_000_000_000L
        private const val MINUTE = 60_000L
    }
}
