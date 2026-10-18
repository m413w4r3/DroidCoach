package dev.atlas.coach.usage.android

import android.content.Context
import android.content.SharedPreferences
import dev.atlas.coach.usage.ObservationConsent
import dev.atlas.coach.usage.ObservationConsentStore
import dev.atlas.coach.usage.ObservationPurpose
import dev.atlas.coach.usage.ObservationRecord
import dev.atlas.coach.usage.ObservationRecordStore
import dev.atlas.coach.usage.UsageCursor
import dev.atlas.coach.usage.UsageCursorStore
import dev.atlas.coach.usage.UsageObserverSnapshot
import dev.atlas.coach.usage.UsageObserverStateStore
import dev.atlas.coach.usage.UsageRetention
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Local-only adapter. Each mutation commits synchronously so revoke/purge has immediate semantics. */
class SharedPreferencesUsageStores(context: Context) :
    ObservationConsentStore,
    UsageCursorStore,
    UsageObserverStateStore,
    ObservationRecordStore {

    private val preferences: SharedPreferences = context.getSharedPreferences(
        "atlas_usage_observation_v1",
        Context.MODE_PRIVATE,
    )

    @Synchronized
    override fun currentConsent(): ObservationConsent {
        return try {
            val raw = preferences.getString(KEY_CONSENT, null) ?: return ObservationConsent.REVOKED
            val json = JSONObject(raw)
            val purposeName = json.optString("purpose").takeIf(String::isNotBlank)
            ObservationConsent(
                enabled = json.optBoolean("enabled", false),
                purpose = purposeName?.let(ObservationPurpose::valueOf),
                allowlistedPackageNames = json.getJSONArray("packages").strings(),
                grantedAtUtc = if (json.has("grantedAtUtc")) json.getLong("grantedAtUtc") else null,
            )
        } catch (_: Exception) {
            ObservationConsent.REVOKED
        }
    }

    @Synchronized
    override fun saveConsent(consent: ObservationConsent) {
        val json = JSONObject()
            .put("enabled", consent.enabled)
            .put("purpose", consent.purpose?.name ?: JSONObject.NULL)
            .put("packages", JSONArray().apply { consent.allowlistedPackageNames.sorted().forEach { put(it) } })
            .put("grantedAtUtc", consent.grantedAtUtc ?: JSONObject.NULL)
        commit { putString(KEY_CONSENT, json.toString()) }
    }

    @Synchronized
    override fun currentCursor(): UsageCursor? {
        return try {
            val raw = preferences.getString(KEY_CURSOR, null) ?: return null
            val json = JSONObject(raw)
            UsageCursor(json.getLong("timestampUtc"), json.getJSONArray("fingerprints").strings())
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    override fun saveCursor(cursor: UsageCursor) {
        val json = JSONObject()
            .put("timestampUtc", cursor.timestampUtc)
            .put("fingerprints", JSONArray().apply { cursor.fingerprintsAtTimestamp.take(512).forEach { put(it) } })
        commit { putString(KEY_CURSOR, json.toString()) }
    }

    @Synchronized
    override fun clearCursor() {
        commit { remove(KEY_CURSOR) }
    }

    @Synchronized
    override fun currentSnapshot(): UsageObserverSnapshot {
        return try {
            val raw = preferences.getString(KEY_SNAPSHOT, null) ?: return UsageObserverSnapshot()
            val json = JSONObject(raw)
            val ids = json.optJSONObject("recentIds") ?: JSONObject()
            val recent = buildMap {
                ids.keys().forEach { key -> put(key, ids.getLong(key)) }
            }
            UsageObserverSnapshot(
                foregroundPackage = json.optString("foreground").takeIf(String::isNotBlank),
                foregroundTimestampUtc = if (json.has("foregroundAtUtc")) json.getLong("foregroundAtUtc") else null,
                foregroundWasTransient = json.optBoolean("transient", false),
                stableForegroundPackage = json.optString("stableForeground").takeIf(String::isNotBlank),
                recentSourceEventTimestampsUtc = recent,
            )
        } catch (_: Exception) {
            UsageObserverSnapshot()
        }
    }

    @Synchronized
    override fun saveSnapshot(snapshot: UsageObserverSnapshot) {
        val recent = JSONObject().apply {
            snapshot.recentSourceEventTimestampsUtc.entries
                .sortedByDescending { it.value }
                .take(UsageRetention.MAX_RECENT_SOURCE_EVENT_IDS)
                .forEach { (id, timestamp) -> put(id, timestamp) }
        }
        val json = JSONObject()
            .put("foreground", snapshot.foregroundPackage ?: JSONObject.NULL)
            .put("foregroundAtUtc", snapshot.foregroundTimestampUtc ?: JSONObject.NULL)
            .put("transient", snapshot.foregroundWasTransient)
            .put("stableForeground", snapshot.stableForegroundPackage ?: JSONObject.NULL)
            .put("recentIds", recent)
        commit { putString(KEY_SNAPSHOT, json.toString()) }
    }

    @Synchronized
    override fun clearSnapshot() {
        commit { remove(KEY_SNAPSHOT) }
    }

    @Synchronized
    override fun allRecords(): List<ObservationRecord> = readRecords()

    @Synchronized
    override fun appendRecord(record: ObservationRecord) {
        val records = readRecords().toMutableList()
        if (records.none { it.eventId == record.eventId }) records += record
        writeRecords(records.takeLast(UsageRetention.MAX_STORED_EVENTS))
    }

    @Synchronized
    override fun keepOnlyPackages(packageNames: Set<String>) {
        writeRecords(readRecords().filter { it.packageName in packageNames })
    }

    @Synchronized
    override fun pruneBefore(cutoffUtc: Long) {
        writeRecords(readRecords().filter { it.timestampUtc >= cutoffUtc })
    }

    @Synchronized
    override fun clearRecords() {
        commit { remove(KEY_RECORDS) }
    }

    private fun readRecords(): List<ObservationRecord> {
        return try {
            val raw = preferences.getString(KEY_RECORDS, null) ?: return emptyList()
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val row = array.getJSONObject(index)
                ObservationRecord(row.getString("id"), row.getString("package"), row.getLong("time"))
            }.takeLast(UsageRetention.MAX_STORED_EVENTS)
        } catch (_: JSONException) {
            emptyList()
        }
    }

    private fun writeRecords(records: List<ObservationRecord>) {
        val array = JSONArray().apply {
            records.takeLast(UsageRetention.MAX_STORED_EVENTS).forEach { row ->
                put(JSONObject().put("id", row.eventId).put("package", row.packageName).put("time", row.timestampUtc))
            }
        }
        commit { putString(KEY_RECORDS, array.toString()) }
    }

    private inline fun commit(configure: SharedPreferences.Editor.() -> Unit) {
        val editor = preferences.edit().apply(configure)
        check(editor.commit()) { "Could not commit local usage-observation state" }
    }

    private fun JSONArray.strings(): Set<String> = buildSet {
        for (index in 0 until length()) add(getString(index))
    }

    private companion object {
        const val KEY_CONSENT = "consent"
        const val KEY_CURSOR = "cursor"
        const val KEY_SNAPSHOT = "snapshot"
        const val KEY_RECORDS = "records"
    }
}
