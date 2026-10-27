package dev.atlas.coach.usage

object UsageQuotaEvaluator {
    fun evaluate(quota: UsageQuota, summary: UsageSummary?): QuotaEvaluation {
        if (
            !quota.enabled ||
            summary == null ||
            summary.coverage.truncated ||
            summary.coverage.observedMs != summary.windowEnd - summary.windowStart
        ) {
            return QuotaEvaluation(
                quotaId = quota.quotaId,
                status = QuotaStatus.UNKNOWN,
                observedMinutes = null,
                limitMinutes = quota.limitMs / MILLIS_PER_MINUTE,
                reliability = UsageReliability.LOW,
            )
        }

        val includedApps = if (quota.appRef == null) summary.apps else summary.apps.filter { it.appRef == quota.appRef }
        val observedMs = includedApps.sumOf(UsageAppSummary::foregroundMs)
        val reliability = includedApps.maxOfOrNull { it.reliability.ordinal }
            ?.let { UsageReliability.values()[it] }
            ?: if ((summary.coverage.blindMs ?: 0L) > 0L) UsageReliability.LOW else UsageReliability.HIGH
        val status = when {
            observedMs >= quota.limitMs -> QuotaStatus.EXCEEDED
            observedMs.toDouble() >= quota.limitMs.toDouble() * quota.warningRatio -> QuotaStatus.WARNING
            else -> QuotaStatus.OK
        }
        return QuotaEvaluation(
            quotaId = quota.quotaId,
            status = status,
            observedMinutes = observedMs / MILLIS_PER_MINUTE,
            limitMinutes = quota.limitMs / MILLIS_PER_MINUTE,
            reliability = reliability,
        )
    }

    private const val MILLIS_PER_MINUTE = 60_000L
}
