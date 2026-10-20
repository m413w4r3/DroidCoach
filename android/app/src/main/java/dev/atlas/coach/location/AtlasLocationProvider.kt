package dev.atlas.coach.location

/**
 * Small Android-independent location contract for the repository and foreground service.
 * S6 can supply this repository to the Coach without exposing LocationManager or Activity.
 */
interface AtlasLocationProvider {
    fun permissionState(): LocationPermissionState

    suspend fun capture(request: LocationCaptureRequest = LocationCaptureRequest()): AtlasLocationFix
}

enum class LocationAccuracy {
    COARSE,
    PRECISE,
}

data class LocationCaptureRequest(
    val desiredAccuracy: LocationAccuracy = LocationAccuracy.COARSE,
    val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    init {
        require(maxAgeMs >= 0L)
        require(timeoutMs > 0L)
    }

    companion object {
        const val DEFAULT_MAX_AGE_MS = 15_000L
        const val DEFAULT_TIMEOUT_MS = 10_000L
    }
}

data class LocationPermissionState(
    val coarseGranted: Boolean,
    val fineGranted: Boolean,
) {
    val canCapture: Boolean get() = coarseGranted || fineGranted
}

/** Minimal fix: no provider name, altitude, speed, bearing, or Android extras cross this boundary. */
data class AtlasLocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val capturedAtUtcMillis: Long,
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(accuracyMeters.isFinite() && accuracyMeters >= 0.0)
        require(capturedAtUtcMillis > 0L)
    }
}

/** Persistable and shareable location shape. Construction enforces the ATLAS 2 km boundary. */
data class LocationSummary(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val measuredAtUtcMillis: Long,
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(accuracyMeters.isFinite() && accuracyMeters >= LocationCoarsener.COARSE_METERS)
        require(measuredAtUtcMillis > 0L)
    }

    internal fun sameApproximateZone(other: LocationSummary): Boolean =
        latitude == other.latitude && longitude == other.longitude
}

object LocationErrorCode {
    const val DISABLED = "LOCATION_DISABLED"
    const val PERMISSION_REQUIRED = "LOCATION_PERMISSION_REQUIRED"
    const val BACKGROUND_UNAVAILABLE = "LOCATION_BACKGROUND_UNAVAILABLE"
    const val TIMEOUT = "LOCATION_TIMEOUT"
    const val UNAVAILABLE = "LOCATION_UNAVAILABLE"
}

class LocationCaptureException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
