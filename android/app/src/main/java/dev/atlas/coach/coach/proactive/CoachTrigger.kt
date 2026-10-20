package dev.atlas.coach.coach.proactive

import org.json.JSONObject

/** The only event sources accepted by the proactive coach. */
enum class CoachTriggerSource(
    val wireValue: String,
    val defaultConsentCategory: String?,
) {
    FOCUS_COMPLETED("focus_completed", "focus"),
    GOAL_REVIEW_DUE("goal_review_due", "goals"),
    HABIT_REMINDER_DUE("habit_reminder_due", "habit"),
    DISTRACTION_THRESHOLD_REACHED("distraction_threshold_reached", "app_usage_summary"),
    MOBILITY_SESSION_COMPLETED("mobility_session_completed", "location_summary"),
    DAILY_PLAN_DUE("daily_plan_due", null);

    companion object {
        fun fromWireValue(value: String): CoachTriggerSource = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("Unknown coach trigger source: $value")
    }
}

enum class CoachTriggerReliability(val wireValue: String) {
    VERIFIED("verified"),
    INSUFFICIENT_EVIDENCE("insufficient_evidence");

    companion object {
        fun fromWireValue(value: String): CoachTriggerReliability = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("Unknown trigger reliability: $value")
    }
}

/**
 * A minimized, local event. It deliberately carries no screen text, package name, goal title,
 * location, or conversation content. [manual] is only an origin flag for quiet-hours handling.
 */
data class CoachTrigger(
    val eventId: String,
    val source: CoachTriggerSource,
    val occurredAtUtcMillis: Long,
    val motifKey: String = source.wireValue,
    val consentCategory: String? = source.defaultConsentCategory,
    val reliability: CoachTriggerReliability = CoachTriggerReliability.INSUFFICIENT_EVIDENCE,
    val manual: Boolean = false,
) {
    init {
        require(eventId.isNotBlank() && eventId.length <= MAX_EVENT_ID_LENGTH) {
            "Trigger event ID must be non-blank and at most $MAX_EVENT_ID_LENGTH characters"
        }
        require(occurredAtUtcMillis >= 0L) { "Trigger timestamp must be non-negative" }
        require(motifKey.matches(MOTIF_KEY_PATTERN)) { "Trigger motif must be a stable, bounded key" }
        require(consentCategory == null || consentCategory in ALLOWED_CONSENT_CATEGORIES) {
            "Trigger consent category is not supported"
        }
        require(consentCategory == source.defaultConsentCategory) {
            "Trigger consent category must match its source"
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("event_id", eventId)
        .put("source", source.wireValue)
        .put("occurred_at_utc_ms", occurredAtUtcMillis)
        .put("motif_key", motifKey)
        .put("consent_category", consentCategory ?: JSONObject.NULL)
        .put("reliability", reliability.wireValue)
        .put("manual", manual)

    companion object {
        const val MAX_EVENT_ID_LENGTH = 160
        private val MOTIF_KEY_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,119}")
        private val ALLOWED_CONSENT_CATEGORIES = setOf(
            "focus", "habit", "content", "coach_interaction", "app_usage_summary", "goals", "location_summary",
        )

        fun fromJson(value: JSONObject): CoachTrigger = CoachTrigger(
            eventId = value.getString("event_id"),
            source = CoachTriggerSource.fromWireValue(value.getString("source")),
            occurredAtUtcMillis = value.getLong("occurred_at_utc_ms"),
            motifKey = value.getString("motif_key"),
            consentCategory = if (value.isNull("consent_category")) null else value.getString("consent_category"),
            reliability = CoachTriggerReliability.fromWireValue(value.getString("reliability")),
            manual = value.optBoolean("manual", false),
        )
    }
}

/** Current grants and their latest grant timestamps, read again before every external effect. */
data class ProactiveConsentSnapshot(
    val grantedAtUtcMillisByCategory: Map<String, Long> = emptyMap(),
) {
    init {
        require(grantedAtUtcMillisByCategory.keys.all { it in ALLOWED_CONSENT_CATEGORIES })
        require(grantedAtUtcMillisByCategory.values.all { it >= 0L })
    }

    fun allows(trigger: CoachTrigger): Boolean {
        val category = trigger.consentCategory ?: return true
        val grantedAt = grantedAtUtcMillisByCategory[category] ?: return false
        return trigger.occurredAtUtcMillis >= grantedAt
    }

    companion object {
        val EMPTY = ProactiveConsentSnapshot()
        private val ALLOWED_CONSENT_CATEGORIES = setOf(
            "focus", "habit", "content", "coach_interaction", "app_usage_summary", "goals", "location_summary",
        )
    }
}
