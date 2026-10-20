package dev.atlas.coach.location

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * App-level consent and minimized-summary persistence shared by UI, repository, and service.
 * Android OS grants are deliberately queried from PackageManager and never stored here.
 */
interface LocationConsentStore {
    fun isLocationSummarySharingEnabled(): Boolean

    /** Timestamp for the latest explicit sharing grant; older local tracking data stays private. */
    fun locationSummarySharingConsentAtUtcMillis(): Long?

    fun setLocationSummarySharingEnabled(enabled: Boolean)

    fun recentLocationSummaries(
        nowUtcMillis: Long,
        maxAgeMs: Long = DEFAULT_AGENT_SUMMARY_MAX_AGE_MS,
    ): List<LocationSummary>

    fun appendMobilitySummary(summary: LocationSummary): Boolean

    /** Stores a fresh manual capture even when it remains in the same approximate zone. */
    fun appendManualSummary(summary: LocationSummary): Boolean

    fun clearLocationSummaries()

    /** Notifies the foreground service immediately when sharing is revoked in another surface. */
    fun addSharingRevokedListener(listener: () -> Unit): AutoCloseable

    companion object {
        const val DEFAULT_AGENT_SUMMARY_MAX_AGE_MS = 15L * 60L * 1_000L

        /** Open the one private, app-wide store used by future S6 integration as well. */
        fun open(context: Context): LocationConsentStore =
            SharedPreferencesLocationConsentStore(context.applicationContext)
    }
}

private class SharedPreferencesLocationConsentStore(context: Context) : LocationConsentStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    init {
        synchronized(STORE_LOCK) {
            if (!preferences.contains(KEY_SCHEMA_VERSION)) {
                preferences.edit().putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION).commit()
            }
        }
    }

    override fun isLocationSummarySharingEnabled(): Boolean = synchronized(STORE_LOCK) {
        preferences.getBoolean(KEY_SHARING_ENABLED, false)
    }

    override fun locationSummarySharingConsentAtUtcMillis(): Long? = synchronized(STORE_LOCK) {
        preferences.takeIf { it.getBoolean(KEY_SHARING_ENABLED, false) }
            ?.takeIf { it.contains(KEY_SHARING_GRANTED_AT) }
            ?.getLong(KEY_SHARING_GRANTED_AT, 0L)
            ?.takeIf { it > 0L }
    }

    override fun setLocationSummarySharingEnabled(enabled: Boolean) {
        synchronized(STORE_LOCK) {
            val wasEnabled = preferences.getBoolean(KEY_SHARING_ENABLED, false)
            val editor = preferences.edit().putBoolean(KEY_SHARING_ENABLED, enabled)
            if (!enabled) editor.remove(KEY_SUMMARIES).remove(KEY_SHARING_GRANTED_AT)
            else if (!wasEnabled) editor.putLong(KEY_SHARING_GRANTED_AT, System.currentTimeMillis())
            check(editor.commit()) { "Could not persist location sharing consent" }
        }
    }

    override fun recentLocationSummaries(nowUtcMillis: Long, maxAgeMs: Long): List<LocationSummary> {
        require(nowUtcMillis > 0L && maxAgeMs >= 0L)
        if (!isLocationSummarySharingEnabled()) return emptyList()
        val consentedAt = locationSummarySharingConsentAtUtcMillis() ?: return emptyList()
        val cutoff = (nowUtcMillis - maxAgeMs).coerceAtLeast(0L)
        return readSummaries().filter {
            it.measuredAtUtcMillis >= consentedAt && it.measuredAtUtcMillis in cutoff..nowUtcMillis
        }
    }

    override fun appendMobilitySummary(summary: LocationSummary): Boolean = synchronized(STORE_LOCK) {
        val existing = readSummaries()
        if (existing.lastOrNull()?.sameApproximateZone(summary) == true) return false
        persistSummary(existing, summary)
    }

    override fun appendManualSummary(summary: LocationSummary): Boolean = synchronized(STORE_LOCK) {
        if (!preferences.getBoolean(KEY_SHARING_ENABLED, false)) return false
        persistSummary(readSummaries(), summary)
    }

    override fun clearLocationSummaries() {
        synchronized(STORE_LOCK) {
            check(preferences.edit().remove(KEY_SUMMARIES).commit()) {
                "Could not clear location summaries"
            }
        }
    }

    override fun addSharingRevokedListener(listener: () -> Unit): AutoCloseable {
        val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { shared, key ->
            if (key == KEY_SHARING_ENABLED && !shared.getBoolean(KEY_SHARING_ENABLED, false)) listener()
        }
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        return AutoCloseable { preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener) }
    }

    private fun readSummaries(): List<LocationSummary> = synchronized(STORE_LOCK) {
        val raw = preferences.getString(KEY_SUMMARIES, null) ?: return emptyList()
        runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(LocationSummary(
                        latitude = item.getDouble("lat"),
                        longitude = item.getDouble("lon"),
                        accuracyMeters = item.getDouble("accuracy_m"),
                        measuredAtUtcMillis = item.getLong("measured_at_ms"),
                    ))
                }
            }.takeLast(MAX_STORED_SUMMARIES)
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val PREFERENCES_NAME = "atlas_location_consent"
        private const val KEY_SCHEMA_VERSION = "schema_version"
        private const val KEY_SHARING_ENABLED = "location_summary_sharing_enabled"
        private const val KEY_SHARING_GRANTED_AT = "location_summary_sharing_granted_at_ms"
        private const val KEY_SUMMARIES = "coarse_mobility_summaries"
        private const val SCHEMA_VERSION = 1
        private const val MAX_STORED_SUMMARIES = 12
        private val STORE_LOCK = Any()
    }

    private fun persistSummary(existing: List<LocationSummary>, summary: LocationSummary): Boolean {
        val retained = (existing + summary).takeLast(MAX_STORED_SUMMARIES)
        val json = JSONArray().apply {
            retained.forEach { item ->
                put(JSONObject()
                    .put("lat", item.latitude)
                    .put("lon", item.longitude)
                    .put("accuracy_m", item.accuracyMeters)
                    .put("measured_at_ms", item.measuredAtUtcMillis))
            }
        }
        check(preferences.edit().putString(KEY_SUMMARIES, json.toString()).commit()) {
            "Could not persist coarse location summary"
        }
        return true
    }
}
