package dev.atlas.coach.rules

/** Immutable baseline exclusions. Android adapters add the current HOME and installed system roles. */
object SafetyDenylist {
    val packageNames: Set<String> = setOf(
        "dev.atlas.coach",
        "com.android.settings",
        "com.android.systemui",
        "com.google.android.systemui",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.android.emergency",
        "com.google.android.apps.emergency",
        "com.android.cellbroadcastreceiver",
        "com.google.android.cellbroadcastreceiver",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.managedprovisioning",
        "com.google.android.gms",
        "com.google.android.marvin.talkback",
    )

    /** Packages whose brief foreground transition commonly represents the notification shade. */
    val transientForegroundPackages: Set<String> = setOf(
        "com.android.systemui",
        "com.google.android.systemui",
    )

    fun isProtected(packageName: String, platformProtectedPackages: Set<String> = emptySet()): Boolean =
        packageName in packageNames || packageName in platformProtectedPackages

    fun selectablePackages(
        candidates: Iterable<String>,
        platformProtectedPackages: Set<String> = emptySet(),
    ): Set<String> = candidates
        .filter(String::isNotBlank)
        .filterNot { isProtected(it, platformProtectedPackages) }
        .toSortedSet()
}
