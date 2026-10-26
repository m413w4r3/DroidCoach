package dev.atlas.coach.usage.android

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import dev.atlas.coach.usage.GlobalUsageEvent
import dev.atlas.coach.usage.GlobalUsageEventBatch
import dev.atlas.coach.usage.GlobalUsageEventSource
import dev.atlas.coach.usage.GlobalUsageEventType
import dev.atlas.coach.usage.StableUsageAppRefProvider
import dev.atlas.coach.usage.UsageAppRefProvider
import dev.atlas.coach.usage.UsageDisplayNameProvider
import dev.atlas.coach.usage.UsageSummaryLimits
import java.security.MessageDigest
import java.security.SecureRandom

/** Reads foreground lifecycle transitions globally and discards all other UsageEvents fields. */
class AndroidGlobalUsageEventSource(
    private val usageStatsManager: UsageStatsManager,
) : GlobalUsageEventSource {
    override fun query(fromUtc: Long, throughUtc: Long, maxEvents: Int): GlobalUsageEventBatch {
        require(fromUtc >= 0L && throughUtc >= fromUtc)
        require(maxEvents in 1..UsageSummaryLimits.MAX_EVENTS_PER_QUERY)
        val usageEvents = usageStatsManager.queryEvents(fromUtc, throughUtc)
        val event = UsageEvents.Event()
        val selected = mutableListOf<GlobalUsageEvent>()
        var truncated = false
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            val type = foregroundEventType(event.eventType) ?: continue
            val packageName = event.packageName.orEmpty()
            if (packageName.isBlank()) continue
            if (selected.size >= maxEvents) {
                truncated = true
                break
            }
            selected += GlobalUsageEvent(
                eventId = fingerprint(event.timeStamp, packageName, event.eventType),
                packageName = packageName,
                timestampUtc = event.timeStamp,
                type = type,
            )
        }
        return GlobalUsageEventBatch(selected, truncated)
    }

    @Suppress("DEPRECATION")
    private fun foregroundEventType(type: Int): GlobalUsageEventType? = when (type) {
        UsageEvents.Event.ACTIVITY_RESUMED,
        UsageEvents.Event.MOVE_TO_FOREGROUND -> GlobalUsageEventType.ACTIVITY_RESUMED

        UsageEvents.Event.ACTIVITY_PAUSED,
        UsageEvents.Event.MOVE_TO_BACKGROUND -> GlobalUsageEventType.ACTIVITY_PAUSED

        else -> null
    }

    private fun fingerprint(timestampUtc: Long, packageName: String, eventType: Int): String {
        val value = "$timestampUtc\u0000$packageName\u0000$eventType"
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return "global:${digest.take(16).joinToString("") { "%02x".format(it) }}"
    }
}

/** The secret is generated once and stored locally; it is never sent with a usage summary. */
class AndroidUsageAppRefProvider(context: Context) : UsageAppRefProvider {
    private val delegate = StableUsageAppRefProvider(loadOrCreateSalt(context.applicationContext))

    override fun appRef(packageName: String): String = delegate.appRef(packageName)

    private companion object {
        private const val PREFERENCES = "atlas_global_usage_identity_v1"
        private const val KEY_SALT = "app_ref_salt"

        @Synchronized
        private fun loadOrCreateSalt(context: Context): ByteArray {
            val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            preferences.getString(KEY_SALT, null)?.let { encoded ->
                runCatching { Base64.decode(encoded, Base64.NO_WRAP) }
                    .getOrNull()
                    ?.takeIf { it.size >= 16 }
                    ?.let { return it }
            }
            val salt = ByteArray(32).also(SecureRandom()::nextBytes)
            check(
                preferences.edit().putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP)).commit(),
            ) { "Could not persist local usage pseudonym salt" }
            return salt
        }
    }
}

class AndroidUsageDisplayNameProvider(context: Context) : UsageDisplayNameProvider {
    private val packageManager = context.applicationContext.packageManager

    override fun displayName(packageName: String): String? = try {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString().takeIf(String::isNotBlank)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } catch (_: SecurityException) {
        null
    }
}
