package dev.atlas.coach.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageQuotaEvaluatorTest {
    @Test
    fun disabledQuotaIsDistinctFromAnIncompleteEnabledQuota() {
        val disabled = UsageQuotaEvaluator.evaluate(quota(enabled = false), summary = null)
        val incomplete = UsageQuotaEvaluator.evaluate(quota(enabled = true), summary = incompleteSummary())

        assertEquals(QuotaStatus.DISABLED, disabled.status)
        assertNull(disabled.observedMinutes)
        assertNull(disabled.reliability)
        assertEquals(QuotaStatus.UNKNOWN, incomplete.status)
    }

    private fun quota(enabled: Boolean) = UsageQuota(
        quotaId = "quota-test",
        window = UsageQuotaWindow.ROLLING_24H,
        limitMs = 60L * MILLIS_PER_MINUTE,
        enabled = enabled,
    )

    private fun incompleteSummary() = UsageSummary(
        windowStart = WINDOW_START,
        windowEnd = WINDOW_END,
        timezone = "UTC",
        totalForegroundMs = 0L,
        apps = emptyList(),
        coverage = UsageSummaryCoverage(
            observedMs = WINDOW_END - WINDOW_START - 1L,
            eventsSeen = 0,
            truncated = false,
        ),
        generatedAt = WINDOW_END,
        consentVersion = 1,
    )

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
        const val WINDOW_START = 1_800_000_000_000L
        const val WINDOW_END = WINDOW_START + 60L * MILLIS_PER_MINUTE
    }
}
