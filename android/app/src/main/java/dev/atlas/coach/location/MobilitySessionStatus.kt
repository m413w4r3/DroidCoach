package dev.atlas.coach.location

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Why a visible mobility session stopped; S6 can pass capability and emergency stop reasons. */
enum class MobilitySessionStopReason {
    MANUAL,
    SHARING_REVOKED,
    PERMISSION_REVOKED,
    LOCATION_DISABLED,
    DURATION_LIMIT,
    CAPABILITY_DISABLED,
    EMERGENCY_PAUSE,
    SERVICE_INTERRUPTED,
}

data class MobilitySessionCompletion(
    val startedAtUtcMillis: Long,
    val stoppedAtUtcMillis: Long,
    val stopReason: MobilitySessionStopReason,
    val measurementCount: Int,
    val zoneChangeCount: Int,
) {
    /** A completed event is meaningful only after at least one fresh coarse measurement. */
    val completedReliably: Boolean get() = measurementCount > 0

    /** A process/service interruption is not reported as a user-visible completed session. */
    val shouldEmitCompletedEvent: Boolean
        get() = completedReliably && stopReason != MobilitySessionStopReason.SERVICE_INTERRUPTED
}

/** Process-wide, observable state for UI Stop controls and S5's optional completion event. */
data class MobilitySessionStatus(
    val active: Boolean = false,
    val startedAtUtcMillis: Long? = null,
    val lastMeasurementAtUtcMillis: Long? = null,
    val measurementCount: Int = 0,
    val zoneChangeCount: Int = 0,
    val lastCompletion: MobilitySessionCompletion? = null,
)

object MobilitySessionStatusStore {
    private val mutableStatus = MutableStateFlow(MobilitySessionStatus())

    /** Collect from any UI layer without depending on the Android Service instance. */
    val status: StateFlow<MobilitySessionStatus> = mutableStatus.asStateFlow()

    @Synchronized
    internal fun sessionStarted(startedAtUtcMillis: Long) {
        mutableStatus.value = mutableStatus.value.copy(
            active = true,
            startedAtUtcMillis = startedAtUtcMillis,
            lastMeasurementAtUtcMillis = null,
            measurementCount = 0,
            zoneChangeCount = 0,
        )
    }

    @Synchronized
    internal fun measurementRecorded(summary: LocationSummary, zoneChanged: Boolean) {
        val current = mutableStatus.value
        if (!current.active) return
        mutableStatus.value = current.copy(
            lastMeasurementAtUtcMillis = summary.measuredAtUtcMillis,
            measurementCount = current.measurementCount + 1,
            zoneChangeCount = current.zoneChangeCount + if (zoneChanged) 1 else 0,
        )
    }

    @Synchronized
    internal fun sessionStopped(reason: MobilitySessionStopReason, stoppedAtUtcMillis: Long) {
        val current = mutableStatus.value
        val startedAt = current.startedAtUtcMillis ?: return
        if (!current.active) return
        val completion = MobilitySessionCompletion(
            startedAtUtcMillis = startedAt,
            stoppedAtUtcMillis = stoppedAtUtcMillis,
            stopReason = reason,
            measurementCount = current.measurementCount,
            zoneChangeCount = current.zoneChangeCount,
        )
        mutableStatus.value = current.copy(active = false, lastCompletion = completion)
    }

    @Synchronized
    internal fun resetForTests() {
        mutableStatus.value = MobilitySessionStatus()
    }
}
