package dev.atlas.coach.location

import java.security.SecureRandom
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Adapted from OpenClaw `apps/android/app/src/main/java/ai/openclaw/app/node/LocationCoarsener.kt`.
 * Source: openclaw/openclaw @ e3fe108378f6eca2d4e2a4333fd08bf81bb87105, MIT (OpenClaw LICENSE).
 * The offset-plus-grid approach follows AOSP LocationFudger (Apache-2.0):
 * frameworks/base/services/core/java/com/android/server/location/fudger/LocationFudger.java.
 * ATLAS uses one synchronized process-wide instance for every automatic summary.
 */
class LocationCoarsener internal constructor(
    private val nowElapsedNanos: () -> Long = System::nanoTime,
    private val random: SecureRandom = SecureRandom(),
) {
    private var latitudeOffsetMeters = nextOffset()
    private var longitudeOffsetMeters = nextOffset()
    private var nextUpdateElapsedNanos = nowElapsedNanos() + OFFSET_INTERVAL_NANOS

    @Synchronized
    fun coarsen(fix: AtlasLocationFix): LocationSummary {
        val now = nowElapsedNanos()
        if (now >= nextUpdateElapsedNanos) {
            // Slow offset drift prevents repeated requests from averaging away fresh noise.
            val oldWeight = sqrt(1.0 - OFFSET_CHANGE * OFFSET_CHANGE)
            latitudeOffsetMeters = oldWeight * latitudeOffsetMeters + OFFSET_CHANGE * nextOffset()
            longitudeOffsetMeters = oldWeight * longitudeOffsetMeters + OFFSET_CHANGE * nextOffset()
            nextUpdateElapsedNanos = now + OFFSET_INTERVAL_NANOS
        }

        val latitude = fix.latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        val shiftedLatitude = (latitude + latitudeOffsetMeters / METERS_PER_DEGREE)
            .coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        val latitudeStep = COARSE_METERS / METERS_PER_DEGREE
        val coarseLatitude = (Math.round(shiftedLatitude / latitudeStep) * latitudeStep)
            .coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        val shiftedLongitude = wrapLongitude(
            fix.longitude + longitudeOffsetMeters / metersPerLongitudeDegree(latitude),
        )
        val longitudeStep = COARSE_METERS / metersPerLongitudeDegree(coarseLatitude)
        val coarseLongitude = wrapLongitude(Math.round(shiftedLongitude / longitudeStep) * longitudeStep)

        return LocationSummary(
            latitude = coarseLatitude,
            longitude = coarseLongitude,
            accuracyMeters = maxOf(fix.accuracyMeters, COARSE_METERS),
            measuredAtUtcMillis = fix.capturedAtUtcMillis,
        )
    }

    private fun nextOffset(): Double = random.nextGaussian() * COARSE_METERS / 4.0

    private fun metersPerLongitudeDegree(latitude: Double): Double =
        METERS_PER_DEGREE * cos(Math.toRadians(latitude))

    private fun wrapLongitude(longitude: Double): Double {
        val wrapped = longitude % 360.0
        return when {
            wrapped >= 180.0 -> wrapped - 360.0
            wrapped < -180.0 -> wrapped + 360.0
            else -> wrapped
        }
    }

    companion object {
        const val COARSE_METERS = 2_000.0
        private const val METERS_PER_DEGREE = 111_000.0
        private const val MAX_LATITUDE = 90.0 - 1.0 / METERS_PER_DEGREE
        private const val OFFSET_INTERVAL_NANOS = 60L * 60L * 1_000_000_000L
        private const val OFFSET_CHANGE = 0.03

        /** Shared by every repository/service instance; no caller gets request-scoped noise. */
        val processWide: LocationCoarsener by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            LocationCoarsener()
        }
    }
}
