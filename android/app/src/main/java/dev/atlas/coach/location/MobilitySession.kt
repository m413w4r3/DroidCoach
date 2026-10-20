package dev.atlas.coach.location

/** In-memory policy for one explicitly started foreground mobility session. */
class MobilitySession private constructor(
    val startedAtElapsedRealtimeMs: Long,
    val endsAtElapsedRealtimeMs: Long,
    private val lastMeasurementAtElapsedRealtimeMs: Long?,
    private val lastZone: LocationSummary?,
) {
    fun isFinished(elapsedRealtimeMs: Long): Boolean = elapsedRealtimeMs >= endsAtElapsedRealtimeMs

    fun canBeginMeasurement(elapsedRealtimeMs: Long): Boolean =
        elapsedRealtimeMs >= startedAtElapsedRealtimeMs &&
            elapsedRealtimeMs < endsAtElapsedRealtimeMs &&
            (lastMeasurementAtElapsedRealtimeMs == null ||
                elapsedRealtimeMs - lastMeasurementAtElapsedRealtimeMs >= MIN_SAMPLE_INTERVAL_MS)

    /** Marks the beginning of a measurement so slow providers cannot shorten the 5 minute spacing. */
    fun beginMeasurement(elapsedRealtimeMs: Long): MobilitySession? =
        if (!canBeginMeasurement(elapsedRealtimeMs)) null
        else copy(lastMeasurementAtElapsedRealtimeMs = elapsedRealtimeMs)

    fun recordZone(summary: LocationSummary): ZoneRecording {
        val changed = lastZone?.sameApproximateZone(summary) != true
        return ZoneRecording(copy(lastZone = summary), changed)
    }

    fun delayUntilNextMeasurement(elapsedRealtimeMs: Long): Long {
        if (isFinished(elapsedRealtimeMs)) return 0L
        val next = lastMeasurementAtElapsedRealtimeMs?.plus(MIN_SAMPLE_INTERVAL_MS) ?: elapsedRealtimeMs
        return (minOf(next, endsAtElapsedRealtimeMs) - elapsedRealtimeMs).coerceAtLeast(0L)
    }

    fun remainingMs(elapsedRealtimeMs: Long): Long =
        (endsAtElapsedRealtimeMs - elapsedRealtimeMs).coerceAtLeast(0L)

    private fun copy(
        lastMeasurementAtElapsedRealtimeMs: Long? = this.lastMeasurementAtElapsedRealtimeMs,
        lastZone: LocationSummary? = this.lastZone,
    ) = MobilitySession(
        startedAtElapsedRealtimeMs = startedAtElapsedRealtimeMs,
        endsAtElapsedRealtimeMs = endsAtElapsedRealtimeMs,
        lastMeasurementAtElapsedRealtimeMs = lastMeasurementAtElapsedRealtimeMs,
        lastZone = lastZone,
    )

    data class ZoneRecording(
        val session: MobilitySession,
        val zoneChanged: Boolean,
    )

    companion object {
        const val MAX_DURATION_MS = 60L * 60L * 1_000L
        const val MIN_SAMPLE_INTERVAL_MS = 5L * 60L * 1_000L

        fun start(elapsedRealtimeMs: Long): MobilitySession {
            require(elapsedRealtimeMs >= 0L)
            return MobilitySession(
                startedAtElapsedRealtimeMs = elapsedRealtimeMs,
                endsAtElapsedRealtimeMs = elapsedRealtimeMs + MAX_DURATION_MS,
                lastMeasurementAtElapsedRealtimeMs = null,
                lastZone = null,
            )
        }
    }
}
