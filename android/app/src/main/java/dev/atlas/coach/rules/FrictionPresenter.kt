package dev.atlas.coach.rules

enum class FrictionPresentationResult {
    POSTED,
    PERMISSION_REQUIRED,
    UNAVAILABLE,
}

/** Presents a voluntary suggestion only; implementations must never launch UI automatically. */
fun interface FrictionPresenter {
    fun present(intervention: Intervention): FrictionPresentationResult
}
