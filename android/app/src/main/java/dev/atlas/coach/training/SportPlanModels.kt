package dev.atlas.coach.training

enum class SyncState {
    FRESH,
    STALE,
    UNAVAILABLE,
    MISMATCH,
}

enum class SportPlanSource {
    WGER_READ,
    FIXTURE,
}

enum class WorkoutResultSource {
    WGER_API,
    USER_DECLARED,
    IMPORTED,
}

enum class WorkoutVerification {
    API_READ_BACK,
    USER_CONFIRMED,
    UNVERIFIED,
}

data class SportPlanSet(
    val reps: Int,
    val weightKg: Double?,
)

data class LastPerformance(
    val performedAt: String,
    val sets: List<SportPlanSet>,
)

data class PlannedExercise(
    val exerciseId: String,
    val name: String?,
    val sets: Int,
    val reps: Int,
    val weightKg: Double?,
    val restSeconds: Int?,
    val lastPerformance: LastPerformance?,
)

data class PerformedWorkout(
    val resultId: String,
    val performedAt: String,
    val durationMinutes: Int?,
    val source: WorkoutResultSource,
    val verification: WorkoutVerification,
)

data class SportPlanLimits(
    val maxExercises: Int,
    val truncated: Boolean,
)

/** A prescription and actual workout results occupy separate immutable fields. */
data class SportPlan(
    val schemaVersion: Int,
    val localDate: String?,
    val timezone: String?,
    val source: SportPlanSource,
    val syncState: SyncState,
    val routineId: String?,
    val dayId: String?,
    val dayName: String?,
    val planned: Boolean,
    val exercises: List<PlannedExercise>,
    val performed: List<PerformedWorkout>,
    val generatedAt: String?,
    val limits: SportPlanLimits,
)

/** Renderable result of a remote load, including failures with no cached plan. */
data class SportPlanUiState(
    val plan: SportPlan?,
    val syncState: SyncState,
    val lastSyncAt: String? = plan?.generatedAt,
    val source: SportPlanSource = plan?.source ?: SportPlanSource.WGER_READ,
)
