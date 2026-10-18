/*
 * Adapted from Olauncher@f8e8d6497c457734c7a3171d36374157678e1f33:
 * app/src/main/java/app/olauncher/data/AppModel.kt, GPL-3.0.
 * The profile and shortcut variants are excluded; ATLAS uses text labels and package pins only.
 */
package dev.atlas.coach.launcher

import android.os.UserHandle
import java.text.CollationKey

data class LauncherAppModel(
    val appLabel: String,
    val key: CollationKey?,
    val appPackage: String,
    val activityClassName: String?,
    val user: UserHandle,
) : Comparable<LauncherAppModel> {
    override fun compareTo(other: LauncherAppModel): Int = when {
        key != null && other.key != null -> key.compareTo(other.key)
        else -> appLabel.compareTo(other.appLabel, ignoreCase = true)
    }
}
