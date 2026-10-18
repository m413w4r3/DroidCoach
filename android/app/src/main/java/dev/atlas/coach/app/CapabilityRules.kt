package dev.atlas.coach.app

import dev.atlas.coach.focus.FocusStatus

/** Pure capability gates shared by controller logic and JVM regression tests. */
object CapabilityRules {
    fun enabled(capabilities: Map<String, Boolean>, key: String): Boolean = capabilities[key] == true

    fun mayTickFocus(capabilities: Map<String, Boolean>, status: FocusStatus?): Boolean =
        enabled(capabilities, AtlasController.CAP_FOCUS) && status == FocusStatus.RUNNING

    fun mayConfirmHabitLink(capabilities: Map<String, Boolean>): Boolean =
        enabled(capabilities, AtlasController.CAP_HABITS)

    fun mustPauseFocusOnDisable(enabled: Boolean, status: FocusStatus?): Boolean =
        !enabled && status == FocusStatus.RUNNING
}
