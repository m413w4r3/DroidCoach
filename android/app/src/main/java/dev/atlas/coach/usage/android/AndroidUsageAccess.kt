package dev.atlas.coach.usage.android

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import dev.atlas.coach.usage.UsageAccessStatus

class AndroidUsageAccessStatus(private val context: Context) : UsageAccessStatus {
    override fun isAllowed(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }
}

/** The only supported user-facing grant path. R3 never toggles AppOps itself. */
fun usageAccessSettingsIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
