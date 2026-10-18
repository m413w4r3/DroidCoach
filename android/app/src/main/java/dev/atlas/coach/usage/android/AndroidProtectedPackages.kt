package dev.atlas.coach.usage.android

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.telecom.TelecomManager
import android.provider.Telephony
import dev.atlas.coach.rules.SafetyDenylist

/** Resolves platform-owned safety exclusions without QUERY_ALL_PACKAGES. Unknown packages fail closed. */
object AndroidProtectedPackages {
    fun protectedPackages(context: Context, candidatePackages: Set<String>): Set<String> {
        val result = SafetyDenylist.packageNames.toMutableSet()
        result += context.packageName

        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName?.let(result::add)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage?.let(result::add)
        }
        Telephony.Sms.getDefaultSmsPackage(context)?.let(result::add)

        val devicePolicy = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        devicePolicy?.activeAdmins.orEmpty().forEach { result += it.packageName }

        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')
            ?.mapNotNull { ComponentName.unflattenFromString(it) }
            ?.forEach { result += it.packageName }

        candidatePackages.forEach { packageName ->
            val appInfo = runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.getOrNull()
            if (appInfo == null || appInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
                result += packageName
            }
        }
        return result
    }

    /** Pass only packages already surfaced by an app picker query; hidden packages stay unavailable. */
    fun selectablePackages(context: Context, visibleCandidates: Iterable<String>): Set<String> {
        val candidates = visibleCandidates.toSet()
        return SafetyDenylist.selectablePackages(candidates, protectedPackages(context, candidates))
    }
}
