package dev.atlas.coach.location

import android.Manifest

/** Runtime permission request policy for the S6 UI adapter; it never launches a permission prompt. */
object LocationPermissionPolicy {
    /** Initial location flow is approximate only. Fine must not be co-requested on first use. */
    fun initialRequestPermissions(): Array<String> = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION)

    /** Call only after a user selects a feature that needs precise local location. */
    fun preciseFeatureRequestPermissions(userExplicitlyRequestedPreciseFeature: Boolean): Array<String> =
        if (userExplicitlyRequestedPreciseFeature) {
            arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            emptyArray()
        }
}
