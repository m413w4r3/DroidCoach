package dev.atlas.coach.app

import dev.atlas.coach.focus.FocusStatus

/** Pure capability gates shared by controller logic and JVM regression tests. */
object CapabilityRules {
    fun enabled(capabilities: Map<String, Boolean>, key: String): Boolean = capabilities[key] == true

    /** The Android HOME role is the explicit opt-in; legacy capability state is not required. */
    fun mayRenderLauncherHome(capabilities: Map<String, Boolean>, isAndroidHomeRole: Boolean): Boolean =
        isAndroidHomeRole || enabled(capabilities, AtlasController.CAP_LAUNCHER_HOME)

    fun mayTickFocus(capabilities: Map<String, Boolean>, status: FocusStatus?): Boolean =
        enabled(capabilities, AtlasController.CAP_FOCUS) && status == FocusStatus.RUNNING

    fun mayConfirmHabitLink(capabilities: Map<String, Boolean>): Boolean =
        enabled(capabilities, AtlasController.CAP_HABITS)

    fun mustPauseFocusOnDisable(enabled: Boolean, status: FocusStatus?): Boolean =
        !enabled && status == FocusStatus.RUNNING

    fun mayObserveUsageInBackground(
        capabilities: Map<String, Boolean>,
        usageConsentActive: Boolean,
        osPermissionGranted: Boolean,
    ): Boolean = enabled(capabilities, AtlasController.CAP_USAGE_BACKGROUND) &&
        enabled(capabilities, AtlasController.CAP_APP_TRACKING) && usageConsentActive && osPermissionGranted

    fun mayRunProactiveCoach(
        capabilities: Map<String, Boolean>,
        agentEnabled: Boolean,
    ): Boolean = enabled(capabilities, AtlasController.CAP_AGENT_PROACTIVE) && agentEnabled

    fun mayUseLocation(capabilities: Map<String, Boolean>): Boolean =
        enabled(capabilities, AtlasController.CAP_LOCATION)

    fun mayTrackMobility(capabilities: Map<String, Boolean>): Boolean =
        enabled(capabilities, AtlasController.CAP_MOBILITY)
}
