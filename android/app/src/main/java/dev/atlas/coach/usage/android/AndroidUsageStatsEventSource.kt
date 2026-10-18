package dev.atlas.coach.usage.android

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.os.Build
import dev.atlas.coach.usage.ObservationProvider
import dev.atlas.coach.usage.UsageCursor
import dev.atlas.coach.usage.UsageEventBatch
import dev.atlas.coach.usage.UsageEventSource
import dev.atlas.coach.usage.UsageRetention
import dev.atlas.coach.usage.UsageSourceEvent
import java.security.MessageDigest

/**
 * Reads only ACTIVITY_RESUMED / MOVE_TO_FOREGROUND package transitions. Activity names and all
 * notification, screen, URL and account data are deliberately never read or persisted.
 */
class AndroidUsageStatsEventSource(
    private val usageStatsManager: UsageStatsManager,
) : UsageEventSource {
    override fun query(cursor: UsageCursor, throughUtc: Long, maxRawEvents: Int): UsageEventBatch {
        require(maxRawEvents in 1..UsageRetention.MAX_RAW_EVENTS_PER_POLL)
        val boundedStart = if (throughUtc < UsageRetention.MAX_LOOKBACK_MILLIS) 0L
        else throughUtc - UsageRetention.MAX_LOOKBACK_MILLIS
        val begin = maxOf(cursor.timestampUtc, boundedStart)
        if (throughUtc < begin) return UsageEventBatch(emptyList(), cursor, false)

        val queryCursor = if (begin == cursor.timestampUtc) cursor else UsageCursor(begin)
        val usageEvents = usageStatsManager.queryEvents(begin, throughUtc)
        val event = UsageEvents.Event()
        val launches = mutableListOf<UsageSourceEvent>()
        var scanned = 0
        var hasMore = false
        var lastTimestamp = queryCursor.timestampUtc
        var fingerprintsAtLastTimestamp = queryCursor.fingerprintsAtTimestamp.toMutableSet()

        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            val timestamp = event.timeStamp
            if (timestamp < begin) continue
            if (timestamp > throughUtc) break
            val packageName = event.packageName.orEmpty()
            val fingerprint = fingerprint(timestamp, packageName, event.eventType)
            if (timestamp == queryCursor.timestampUtc && fingerprint in queryCursor.fingerprintsAtTimestamp) continue

            if (scanned >= maxRawEvents) {
                hasMore = true
                break
            }
            scanned++
            if (timestamp > lastTimestamp) {
                lastTimestamp = timestamp
                fingerprintsAtLastTimestamp.clear()
            }
            fingerprintsAtLastTimestamp += fingerprint

            if (packageName.isNotBlank() && isForegroundEvent(event.eventType)) {
                launches += UsageSourceEvent(
                    eventId = fingerprint,
                    packageName = packageName,
                    timestampUtc = timestamp,
                )
            }
        }

        val nextCursor = if (hasMore) {
            UsageCursor(lastTimestamp, fingerprintsAtLastTimestamp)
        } else {
            UsageCursor(throughUtc)
        }
        return UsageEventBatch(launches, nextCursor, hasMore)
    }

    @Suppress("DEPRECATION")
    private fun isForegroundEvent(type: Int): Boolean =
        type == UsageEvents.Event.MOVE_TO_FOREGROUND ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && type == UsageEvents.Event.ACTIVITY_RESUMED)

    private fun fingerprint(timestamp: Long, packageName: String, type: Int): String {
        val raw = "$timestamp\u0000$packageName\u0000$type"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return "usage:${digest.joinToString(separator = "") { byte -> "%02x".format(byte) }}"
    }
}

/** Feature flags keep this provider off until an explicit app-level integration enables it. */
class UsageStatsProvider(
    private val manager: UsageStatsManager,
    private val enabled: Boolean = false,
) : ObservationProvider {
    override val capabilityFlag: String = "observation.usageStats"
    override val enabledByDefault: Boolean = false
    override fun eventSourceOrNull(): UsageEventSource? =
        if (enabled) AndroidUsageStatsEventSource(manager) else null
}
