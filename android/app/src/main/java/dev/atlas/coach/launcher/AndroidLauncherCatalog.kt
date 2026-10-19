/*
 * ATLAS-owned adapter: query activities declaring MAIN/LAUNCHER for the optional launcher.
 * No Olauncher package-discovery helper is copied; the query is narrowed for Android 11+ package visibility.
 * This code does not inspect usage or enumerate all packages.
 */
package dev.atlas.coach.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build

class AndroidLauncherCatalog(context: Context) {
    private val appContext = context.applicationContext

    fun list(): List<LauncherAppModel> {
        val packageManager = appContext.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return LauncherAppRules.sort(queryActivities(packageManager, launcherIntent)
            .mapNotNull { resolveInfo ->
                val activity = resolveInfo.activityInfo ?: return@mapNotNull null
                if (activity.packageName == appContext.packageName) return@mapNotNull null
                val label = resolveInfo.loadLabel(packageManager)?.toString()?.trim().orEmpty()
                if (label.isEmpty()) return@mapNotNull null
                LauncherAppModel(
                    appLabel = label,
                    appPackage = activity.packageName,
                    activityClassName = activity.name,
                )
            }
        )
    }

    fun launch(app: LauncherAppModel): Boolean = runCatching {
        val className = app.activityClassName ?: return false
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(app.appPackage, className)
        appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    private fun queryActivities(packageManager: PackageManager, intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            queryActivitiesLegacy(packageManager, intent)
        }

    @Suppress("DEPRECATION")
    private fun queryActivitiesLegacy(packageManager: PackageManager, intent: Intent): List<ResolveInfo> =
        packageManager.queryIntentActivities(intent, 0)
}
