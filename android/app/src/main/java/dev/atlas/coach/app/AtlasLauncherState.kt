package dev.atlas.coach.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Small observable launcher state kept apart from the domain-engine coordinator. */
internal class AtlasLauncherState(initialPinnedPackages: Set<String>) {
    var pinnedPackages by mutableStateOf(initialPinnedPackages)
        private set

    var homeRoleHeld by mutableStateOf<Boolean?>(null)
        private set

    fun togglePin(packageName: String): Boolean {
        pinnedPackages = if (packageName in pinnedPackages) pinnedPackages - packageName else pinnedPackages + packageName
        return packageName in pinnedPackages
    }

    fun refreshHomeRole(isAtlasDefault: Boolean) {
        homeRoleHeld = isAtlasDefault
    }
}
