package dev.atlas.coach.usage

import dev.atlas.coach.rules.AppOpenEvent

enum class ObservationPurpose(val label: String) {
    GENTLE_FRICTION("Proposer une pause après des ouvertures répétées"),
}

/** A consent record is usable only when its purpose and app allowlist were explicitly selected. */
data class ObservationConsent(
    val enabled: Boolean = false,
    val purpose: ObservationPurpose? = null,
    val allowlistedPackageNames: Set<String> = emptySet(),
    val grantedAtUtc: Long? = null,
) {
    val isExplicit: Boolean
        get() = enabled && purpose != null && allowlistedPackageNames.isNotEmpty() && grantedAtUtc != null

    init {
        require(allowlistedPackageNames.none(String::isBlank)) { "Package names must not be blank" }
        require(grantedAtUtc == null || grantedAtUtc >= 0L) { "Consent time must be non-negative" }
    }

    companion object {
        val REVOKED = ObservationConsent()

        fun grant(
            purpose: ObservationPurpose,
            allowlistedPackageNames: Set<String>,
            nowUtc: Long,
        ): ObservationConsent {
            require(allowlistedPackageNames.isNotEmpty()) { "Choose at least one app explicitly" }
            require(nowUtc >= 0L) { "Consent time must be non-negative" }
            return ObservationConsent(true, purpose, allowlistedPackageNames.toSet(), nowUtc)
        }
    }
}

data class UsageCursor(
    val timestampUtc: Long,
    /** Opaque event fingerprints at the cursor timestamp, needed for inclusive API queries. */
    val fingerprintsAtTimestamp: Set<String> = emptySet(),
)

data class UsageSourceEvent(
    val eventId: String,
    val packageName: String,
    val timestampUtc: Long,
) {
    init {
        require(eventId.isNotBlank() && packageName.isNotBlank())
    }
}

data class UsageEventBatch(
    val events: List<UsageSourceEvent>,
    val nextCursor: UsageCursor,
    val hasMore: Boolean,
)

data class ObservationRecord(
    val eventId: String,
    val packageName: String,
    val timestampUtc: Long,
) {
    fun toAppOpenEvent() = AppOpenEvent(eventId, packageName, timestampUtc)
}

data class UsageObserverSnapshot(
    val foregroundPackage: String? = null,
    val foregroundTimestampUtc: Long? = null,
    val foregroundWasTransient: Boolean = false,
    val stableForegroundPackage: String? = null,
    val recentSourceEventTimestampsUtc: Map<String, Long> = emptyMap(),
)

enum class UsagePollStatus {
    PROCESSED,
    THROTTLED,
    DISABLED,
    PERMISSION_REQUIRED,
    BASELINE_ESTABLISHED,
}

data class UsagePollResult(
    val status: UsagePollStatus,
    val acceptedEvents: List<AppOpenEvent> = emptyList(),
    val interventions: List<dev.atlas.coach.rules.Intervention> = emptyList(),
    val moreEventsPending: Boolean = false,
)

object UsageRetention {
    const val MAX_STORED_EVENTS = 500
    const val MAX_RECENT_SOURCE_EVENT_IDS = 2_000
    const val MAX_LOOKBACK_MILLIS = 6L * 60L * 60L * 1_000L
    const val EVENT_RETENTION_MILLIS = 7L * 24L * 60L * 60L * 1_000L
    const val MAX_RAW_EVENTS_PER_POLL = 256
    const val VISIBLE_POLL_INTERVAL_MILLIS = 10_000L
    const val NOTIFICATION_SHADE_RETURN_WINDOW_MILLIS = 20_000L
}
