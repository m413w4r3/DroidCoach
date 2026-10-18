package dev.atlas.coach.focus

import kotlin.math.roundToLong

enum class FocusPhase { WORK, SHORT_BREAK, LONG_BREAK }

fun FocusPhase.displayLabel(): String = when (this) {
    FocusPhase.WORK -> "Concentration"
    FocusPhase.SHORT_BREAK -> "Pause courte"
    FocusPhase.LONG_BREAK -> "Pause longue"
}

enum class FocusStatus { IDLE, RUNNING, PAUSED, FINISHED, CANCELLED }

fun FocusStatus.displayLabel(): String = when (this) {
    FocusStatus.IDLE -> "À démarrer"
    FocusStatus.RUNNING -> "En cours"
    FocusStatus.PAUSED -> "En pause"
    FocusStatus.FINISHED -> "Terminée"
    FocusStatus.CANCELLED -> "Annulée"
}

enum class FocusPreset(
    val presetId: String,
    val workSeconds: Long,
    val shortBreakSeconds: Long,
) {
    POMODORO_25_5("pomodoro-25-5", 25 * 60L, 5 * 60L),
    VARIANT_10_2("variant-10-2", 10 * 60L, 2 * 60L),
}

/** Durations are expressed in seconds so accelerated plans need no scheduler or test dependency. */
data class FocusPlan(
    val presetId: String,
    val workSeconds: Long,
    val shortBreakSeconds: Long,
    val longBreakSeconds: Long = 15 * 60L,
    val longBreakEveryCycles: Int = 4,
) {
    init {
        require(presetId.isNotBlank()) { "presetId must not be blank" }
        require(workSeconds in 1L..(Long.MAX_VALUE / 1_000L) &&
            shortBreakSeconds in 1L..(Long.MAX_VALUE / 1_000L) &&
            longBreakSeconds in 1L..(Long.MAX_VALUE / 1_000L)) {
            "Focus phase durations must be positive"
        }
        require(longBreakEveryCycles > 0) { "longBreakEveryCycles must be positive" }
    }

    fun scaled(factor: Double): FocusPlan {
        require(factor.isFinite() && factor > 0.0) { "factor must be finite and positive" }
        fun scale(seconds: Long) = (seconds * factor).roundToLong().coerceAtLeast(1L)
        return copy(
            presetId = "$presetId@${factor}",
            workSeconds = scale(workSeconds),
            shortBreakSeconds = scale(shortBreakSeconds),
            longBreakSeconds = scale(longBreakSeconds),
        )
    }

    companion object {
        fun fromPreset(preset: FocusPreset): FocusPlan = FocusPlan(
            presetId = preset.presetId,
            workSeconds = preset.workSeconds,
            shortBreakSeconds = preset.shortBreakSeconds,
        )

        fun custom(workSeconds: Long): FocusPlan = FocusPlan(
            presetId = "custom-$workSeconds",
            workSeconds = workSeconds,
            shortBreakSeconds = 5 * 60L,
        )
    }
}

/** All fields are primitives or nullable primitives and can be stored by a simple local adapter. */
data class FocusSession(
    val id: String,
    val directiveId: String?,
    val presetId: String,
    val phase: FocusPhase,
    val status: FocusStatus,
    val createdAtUtcMillis: Long,
    val updatedAtUtcMillis: Long,
    val remainingSeconds: Long,
    val completedWorkCycles: Int,
    val workSeconds: Long,
    val shortBreakSeconds: Long,
    val longBreakSeconds: Long,
    val longBreakEveryCycles: Int,
    val remainingMillis: Long = remainingSeconds * 1_000L,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "directiveId" to directiveId,
        "presetId" to presetId,
        "phase" to phase.name,
        "status" to status.name,
        "createdAtUtcMillis" to createdAtUtcMillis,
        "updatedAtUtcMillis" to updatedAtUtcMillis,
        "remainingSeconds" to remainingSeconds,
        "remainingMillis" to remainingMillis,
        "completedWorkCycles" to completedWorkCycles,
        "workSeconds" to workSeconds,
        "shortBreakSeconds" to shortBreakSeconds,
        "longBreakSeconds" to longBreakSeconds,
        "longBreakEveryCycles" to longBreakEveryCycles,
    )

    fun toPlan(): FocusPlan = FocusPlan(
        presetId = presetId,
        workSeconds = workSeconds,
        shortBreakSeconds = shortBreakSeconds,
        longBreakSeconds = longBreakSeconds,
        longBreakEveryCycles = longBreakEveryCycles,
    )

    companion object {
        fun fromMap(values: Map<String, Any?>): FocusSession {
            fun requiredString(key: String) = values[key] as? String
                ?: error("Missing or invalid focus field: $key")

            fun requiredLong(key: String) = (values[key] as? Number)?.toLong()
                ?: error("Missing or invalid focus field: $key")

            fun requiredInt(key: String) = (values[key] as? Number)?.toInt()
                ?: error("Missing or invalid focus field: $key")

            val id = requiredString("id")
            val presetId = requiredString("presetId")
            val remainingSeconds = requiredLong("remainingSeconds")
            val remainingMillis = (values["remainingMillis"] as? Number)?.toLong() ?: run {
                require(remainingSeconds <= Long.MAX_VALUE / 1_000L) { "Invalid focus progress" }
                remainingSeconds * 1_000L
            }
            val session = FocusSession(
                id = id,
                directiveId = values["directiveId"] as? String,
                presetId = presetId,
                phase = FocusPhase.valueOf(requiredString("phase")),
                status = FocusStatus.valueOf(requiredString("status")),
                createdAtUtcMillis = requiredLong("createdAtUtcMillis"),
                updatedAtUtcMillis = requiredLong("updatedAtUtcMillis"),
                remainingSeconds = remainingSeconds,
                completedWorkCycles = requiredInt("completedWorkCycles"),
                workSeconds = requiredLong("workSeconds"),
                shortBreakSeconds = requiredLong("shortBreakSeconds"),
                longBreakSeconds = requiredLong("longBreakSeconds"),
                longBreakEveryCycles = requiredInt("longBreakEveryCycles"),
                remainingMillis = remainingMillis,
            )
            require(id.isNotBlank() && presetId.isNotBlank()) { "Focus IDs must not be blank" }
            require(session.createdAtUtcMillis >= 0L && session.updatedAtUtcMillis >= 0L) { "Invalid focus timestamps" }
            require(session.remainingSeconds >= 0L && session.remainingMillis >= 0L && session.completedWorkCycles >= 0) {
                "Invalid focus progress"
            }
            val plan = session.toPlan()
            val phaseLimitSeconds = when (session.phase) {
                FocusPhase.WORK -> plan.workSeconds
                FocusPhase.SHORT_BREAK -> plan.shortBreakSeconds
                FocusPhase.LONG_BREAK -> plan.longBreakSeconds
            }
            require(session.remainingMillis <= phaseLimitSeconds * 1_000L)
            val roundedSeconds = session.remainingMillis / 1_000L + if (session.remainingMillis % 1_000L == 0L) 0 else 1
            require(session.remainingSeconds == roundedSeconds) { "Inconsistent focus progress" }
            return session
        }
    }
}
