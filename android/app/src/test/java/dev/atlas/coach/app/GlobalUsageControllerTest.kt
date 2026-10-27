package dev.atlas.coach.app

import dev.atlas.coach.usage.FakeGlobalUsageConsentStore
import dev.atlas.coach.usage.FakeGlobalUsageCursorStore
import dev.atlas.coach.usage.FakeUsageQuotaStore
import dev.atlas.coach.usage.FakeUsageSummaryStore
import dev.atlas.coach.usage.GlobalUsageConsent
import dev.atlas.coach.usage.GlobalUsageEvent
import dev.atlas.coach.usage.GlobalUsageEventBatch
import dev.atlas.coach.usage.GlobalUsageEventSource
import dev.atlas.coach.usage.GlobalUsageEventType
import dev.atlas.coach.usage.GlobalUsageObservationStatus
import dev.atlas.coach.usage.GlobalUsageObserver
import dev.atlas.coach.usage.QuotaStatus
import dev.atlas.coach.usage.StableUsageAppRefProvider
import dev.atlas.coach.usage.UsageAccessStatus
import dev.atlas.coach.usage.UsageQuota
import dev.atlas.coach.usage.UsageQuotaWindow
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalUsageControllerTest {
    @Test
    fun manualRefreshShowsSummaryQuotaAndRevocationPurgesStoredData() {
        val harness = harness()
        assertFalse(harness.controller.state().hasGlobalConsent)
        assertEquals(GlobalUsageObservationStatus.DISABLED, harness.controller.refresh().observationStatus)
        assertEquals(0, harness.source.queryCount)

        harness.controller.grantConsent(includePackageNames = false)
        val granted = harness.controller.state()
        assertTrue(granted.hasGlobalConsent)
        assertFalse(granted.packageNamesConsented)
        assertTrue(granted.usageAccessGranted)

        harness.clock.now = START + DAY
        val refreshed = harness.controller.refresh()
        assertEquals(GlobalUsageObservationStatus.PROCESSED, refreshed.observationStatus)
        assertEquals(30L * MINUTE, refreshed.summary?.totalForegroundMs)
        assertNull(refreshed.summary?.apps?.single()?.displayName)
        assertEquals(QuotaStatus.WARNING, refreshed.quotas.single().status)
        assertEquals(1, harness.source.queryCount)

        harness.clock.now += MINUTE
        assertEquals(GlobalUsageObservationStatus.RATE_LIMITED, harness.controller.refresh().observationStatus)
        assertEquals(1, harness.source.queryCount)

        harness.controller.revokeConsent()
        val revoked = harness.controller.state()
        assertFalse(revoked.hasGlobalConsent)
        assertNull(revoked.summary)
        assertNull(harness.cursors.currentCursor())
        assertNull(harness.summaries.currentSummary())
        assertEquals(1, harness.source.queryCount)
    }

    @Test
    fun packageNamesRequireSeparateCategoryConsent() {
        val clock = FakeClock(START)
        val source = SyntheticSource(
            listOf(
                event("resume", START + MINUTE, GlobalUsageEventType.ACTIVITY_RESUMED),
                event("pause", START + 2L * MINUTE, GlobalUsageEventType.ACTIVITY_PAUSED),
            ),
        )
        val consentStore = FakeGlobalUsageConsentStore()
        val cursorStore = FakeGlobalUsageCursorStore()
        val summaryStore = FakeUsageSummaryStore()
        val access = MutableAccess(true)
        val observer = GlobalUsageObserver(
            eventSource = source,
            accessStatus = access,
            consentStore = consentStore,
            cursorStore = cursorStore,
            summaryStore = summaryStore,
            appRefProvider = StableUsageAppRefProvider(ByteArray(32) { 4 }),
            displayNameProvider = { "Fixture Application" },
        )
        val controller = GlobalUsageController(
            observer = observer,
            accessStatus = access,
            consentStore = consentStore,
            summaryStore = summaryStore,
            clockUtc = { clock.now },
            zone = { ZoneId.of("UTC") },
        )

        controller.grantConsent(includePackageNames = false)
        assertFalse(controller.state().packageNamesConsented)
        controller.grantConsent(includePackageNames = true)
        assertTrue(controller.state().packageNamesConsented)
        clock.now = START + 10L * MINUTE

        val summary = controller.refresh().summary
        assertEquals("Fixture Application", summary?.apps?.single()?.displayName)
    }

    @Test
    fun dailyQuotaIsPersistedAndExposedWithoutAnotherDataSource() {
        val store = FakeUsageQuotaStore()
        val harness = harness(quotaStore = store)
        assertNull(harness.controller.state().dailyQuota)

        harness.controller.saveDailyQuota(limitMinutes = 90, enabled = true)
        val saved = harness.controller.state().dailyQuota
        assertEquals("quota-jour", saved?.quotaId)
        assertEquals(UsageQuotaWindow.ROLLING_24H, saved?.window)
        assertEquals(90L * MINUTE, saved?.limitMs)
        assertTrue(saved?.enabled == true)

        harness.controller.saveDailyQuota(limitMinutes = 45, enabled = false)
        assertEquals(45L * MINUTE, harness.controller.state().dailyQuota?.limitMs)
        assertFalse(harness.controller.state().dailyQuota?.enabled == true)
        assertEquals(1, store.currentQuotas().size)
    }

    @Test
    fun permissionDeniedIsReflectedWithoutQueryingTheSyntheticSource() {
        val harness = harness(allowed = false)
        harness.controller.grantConsent(includePackageNames = false)

        val state = harness.controller.refresh()

        assertFalse(state.usageAccessGranted)
        assertEquals(GlobalUsageObservationStatus.PERMISSION_REQUIRED, state.observationStatus)
        assertEquals(0, harness.source.queryCount)
    }

    private fun harness(allowed: Boolean = true, quotaStore: FakeUsageQuotaStore? = null): Harness {
        val source = SyntheticSource(
            listOf(
                event("resume", START + 10L * MINUTE, GlobalUsageEventType.ACTIVITY_RESUMED),
                event("pause", START + 40L * MINUTE, GlobalUsageEventType.ACTIVITY_PAUSED),
            ),
        )
        val consents = FakeGlobalUsageConsentStore()
        val cursors = FakeGlobalUsageCursorStore()
        val summaries = FakeUsageSummaryStore()
        val access = MutableAccess(allowed)
        val observer = GlobalUsageObserver(
            eventSource = source,
            accessStatus = access,
            consentStore = consents,
            cursorStore = cursors,
            summaryStore = summaries,
            appRefProvider = StableUsageAppRefProvider(ByteArray(32) { 2 }),
            displayNameProvider = { "Fixture Application" },
        )
        val clock = FakeClock(START)
        return Harness(
            controller = GlobalUsageController(
                observer = observer,
                accessStatus = access,
                consentStore = consents,
                summaryStore = summaries,
                quotas = {
                    quotaStore?.currentQuotas() ?: listOf(
                        UsageQuota(
                            quotaId = "daily",
                            window = UsageQuotaWindow.ROLLING_24H,
                            limitMs = 60L * MINUTE,
                            warningRatio = 0.4,
                        ),
                    )
                },
                quotaStore = quotaStore,
                clockUtc = { clock.now },
                zone = { ZoneId.of("UTC") },
            ),
            source = source,
            cursors = cursors,
            summaries = summaries,
            clock = clock,
        )
    }

    private fun event(id: String, timestamp: Long, type: GlobalUsageEventType) =
        GlobalUsageEvent(id, PACKAGE, timestamp, type)

    private class SyntheticSource(private val events: List<GlobalUsageEvent>) : GlobalUsageEventSource {
        var queryCount = 0
            private set

        override fun query(fromUtc: Long, throughUtc: Long, maxEvents: Int): GlobalUsageEventBatch {
            queryCount++
            return GlobalUsageEventBatch(events.filter { it.timestampUtc in fromUtc..throughUtc }.take(maxEvents), false)
        }
    }

    private class MutableAccess(var allowed: Boolean) : UsageAccessStatus {
        override fun isAllowed(): Boolean = allowed
    }

    private data class FakeClock(var now: Long)

    private data class Harness(
        val controller: GlobalUsageController,
        val source: SyntheticSource,
        val cursors: FakeGlobalUsageCursorStore,
        val summaries: FakeUsageSummaryStore,
        val clock: FakeClock,
    )

    private companion object {
        const val PACKAGE = "dev.atlas.synthetic.controller"
        const val START = 1_800_000_000_000L
        const val MINUTE = 60_000L
        const val HOUR = 60L * MINUTE
        const val DAY = 24L * HOUR
    }
}
