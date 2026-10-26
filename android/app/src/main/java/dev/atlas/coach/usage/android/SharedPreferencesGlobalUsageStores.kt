package dev.atlas.coach.usage.android

import android.content.Context
import android.content.SharedPreferences
import dev.atlas.coach.usage.GlobalUsageConsent
import dev.atlas.coach.usage.GlobalUsageConsentStore
import dev.atlas.coach.usage.GlobalUsageCursor
import dev.atlas.coach.usage.GlobalUsageCursorStore
import dev.atlas.coach.usage.UsageAppSummary
import dev.atlas.coach.usage.UsageReliability
import dev.atlas.coach.usage.UsageSummary
import dev.atlas.coach.usage.UsageSummaryCoverage
import dev.atlas.coach.usage.UsageSummaryJson
import dev.atlas.coach.usage.UsageSummaryStore
import org.json.JSONArray
import org.json.JSONObject

/** Synchronous local storage so revocation immediately removes the cursor and any stored labels. */
class SharedPreferencesGlobalUsageStores(context: Context) :
    GlobalUsageConsentStore,
    GlobalUsageCursorStore,
    UsageSummaryStore {

    private val preferences: SharedPreferences = context.getSharedPreferences(
        "atlas_global_usage_observation_v1",
        Context.MODE_PRIVATE,
    )

    @Synchronized
    override fun currentConsent(): GlobalUsageConsent {
        return try {
            val raw = preferences.getString(KEY_CONSENT, null) ?: return GlobalUsageConsent.REVOKED
            val json = JSONObject(raw)
            GlobalUsageConsent(
                granted = json.optBoolean("granted", false),
                grantedAtUtc = json.longOrNull("grantedAtUtc"),
                revokedAtUtc = json.longOrNull("revokedAtUtc"),
                consentVersion = json.optInt("consentVersion", 1),
                purpose = json.optString("purpose", ""),
                consentedCategories = json.optJSONArray("categories")?.strings().orEmpty(),
            )
        } catch (_: Exception) {
            GlobalUsageConsent.REVOKED
        }
    }

    @Synchronized
    override fun saveConsent(consent: GlobalUsageConsent) {
        val json = JSONObject()
            .put("granted", consent.granted)
            .put("grantedAtUtc", consent.grantedAtUtc ?: JSONObject.NULL)
            .put("revokedAtUtc", consent.revokedAtUtc ?: JSONObject.NULL)
            .put("consentVersion", consent.consentVersion)
            .put("purpose", consent.purpose)
            .put("categories", JSONArray().apply { consent.consentedCategories.sorted().forEach { put(it) } })
        commit { putString(KEY_CONSENT, json.toString()) }
    }

    @Synchronized
    override fun currentCursor(): GlobalUsageCursor? {
        return try {
            val raw = preferences.getString(KEY_CURSOR, null) ?: return null
            GlobalUsageCursor(JSONObject(raw).getLong("baselineUtc"))
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    override fun saveCursor(cursor: GlobalUsageCursor) {
        commit { putString(KEY_CURSOR, JSONObject().put("baselineUtc", cursor.baselineUtc).toString()) }
    }

    @Synchronized
    override fun clearCursor() {
        commit { remove(KEY_CURSOR) }
    }

    @Synchronized
    override fun currentSummary(): UsageSummary? {
        return try {
            val raw = preferences.getString(KEY_SUMMARY, null) ?: return null
            JSONObject(raw).toUsageSummary()
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    override fun saveSummary(summary: UsageSummary) {
        val minimized = UsageSummaryJson.serialize(summary, currentConsent())
        commit { putString(KEY_SUMMARY, minimized) }
    }

    @Synchronized
    override fun clearSummary() {
        commit { remove(KEY_SUMMARY) }
    }

    private fun JSONObject.toUsageSummary(): UsageSummary {
        val coverageJson = getJSONObject("coverage")
        val appRows = getJSONArray("apps")
        val apps = (0 until appRows.length()).map { index ->
            val row = appRows.getJSONObject(index)
            UsageAppSummary(
                appRef = row.getString("app_ref"),
                displayName = row.stringOrNull("display_name"),
                foregroundMs = row.getLong("foreground_ms"),
                launchCount = row.getInt("launch_count"),
                lastUsedAtUtc = row.longOrNull("last_used_at"),
                reliability = UsageReliability.valueOf(row.getString("reliability")),
            )
        }
        return UsageSummary(
            windowStart = getLong("window_start"),
            windowEnd = getLong("window_end"),
            timezone = getString("timezone"),
            totalForegroundMs = getLong("total_foreground_ms"),
            apps = apps,
            coverage = UsageSummaryCoverage(
                observedMs = coverageJson.getLong("observed_ms"),
                blindMs = coverageJson.longOrNull("blind_ms"),
                eventsSeen = coverageJson.getInt("events_seen"),
                truncated = coverageJson.getBoolean("truncated"),
            ),
            generatedAt = getLong("generated_at"),
            consentVersion = getInt("consent_version"),
        )
    }

    private fun JSONObject.longOrNull(key: String): Long? = if (!has(key) || isNull(key)) null else getLong(key)

    private fun JSONObject.stringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key)

    private fun JSONArray.strings(): Set<String> = buildSet {
        for (index in 0 until length()) add(getString(index))
    }

    private inline fun commit(configure: SharedPreferences.Editor.() -> Unit) {
        check(preferences.edit().apply(configure).commit()) { "Could not commit global usage state" }
    }

    private companion object {
        const val KEY_CONSENT = "consent"
        const val KEY_CURSOR = "cursor"
        const val KEY_SUMMARY = "summary"
    }
}
