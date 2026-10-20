package dev.atlas.coach.location

/**
 * Adapted from OpenClaw `apps/android/app/src/main/java/ai/openclaw/app/LocationMode.kt`.
 * Source: openclaw/openclaw @ e3fe108378f6eca2d4e2a4333fd08bf81bb87105, MIT (OpenClaw LICENSE).
 * ATLAS exposes foreground-only capture. Legacy `always` values are retired to Off.
 */
enum class LocationMode(val rawValue: String) {
    Off("off"),
    WhileUsing("whileUsing");

    companion object {
        fun fromRawValue(raw: String?): LocationMode {
            val normalized = raw?.trim()?.lowercase()
            return entries.firstOrNull { it.rawValue.lowercase() == normalized } ?: Off
        }
    }
}
