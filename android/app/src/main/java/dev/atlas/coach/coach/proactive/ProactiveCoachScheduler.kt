package dev.atlas.coach.coach.proactive

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private fun JSONArray.stringList(): List<String> = buildList(length()) {
    for (index in 0 until length()) add(getString(index))
}

private fun JSONArray.jsonObjects(): List<JSONObject> = buildList(length()) {
    for (index in 0 until length()) add(getJSONObject(index))
}

/** User-selectable coaching cadence. This is independent from the action mandate's preauthorized mode. */
enum class ProactiveEngagementMode(
    val wireValue: String,
    val notificationLimitPerLocalDay: Int,
) {
    NORMAL("normal", 3),
    REINFORCED("reinforced", 5);

    companion object {
        const val NORMAL_NOTIFICATION_LIMIT = 3
        const val REINFORCED_NOTIFICATION_LIMIT = 5

        fun fromWireValue(value: String): ProactiveEngagementMode = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("Unknown proactive engagement mode: $value")
    }
}

enum class ProactiveRequestStatus(val wireValue: String) {
    RESERVED("reserved"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    REVOKED_BEFORE_REQUEST("revoked_before_request");

    companion object {
        fun fromWireValue(value: String): ProactiveRequestStatus = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("Unknown proactive request status: $value")
    }
}

enum class ProactiveNotificationStatus(val wireValue: String) {
    RESERVED("reserved"),
    DELIVERED("delivered"),
    FAILED("failed");

    companion object {
        fun fromWireValue(value: String): ProactiveNotificationStatus = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("Unknown proactive notification status: $value")
    }
}

data class ProactiveRequestReservation(
    val reservationId: String,
    val reservedAtUtcMillis: Long,
    val triggerEventIds: List<String>,
    val motifKeys: Set<String>,
    val status: ProactiveRequestStatus = ProactiveRequestStatus.RESERVED,
) {
    init {
        require(reservationId.isNotBlank())
        require(reservedAtUtcMillis >= 0L)
        require(triggerEventIds.isNotEmpty() && triggerEventIds.distinct().size == triggerEventIds.size)
        require(motifKeys.isNotEmpty())
    }

    fun toJson(): JSONObject = JSONObject()
        .put("reservation_id", reservationId)
        .put("reserved_at_utc_ms", reservedAtUtcMillis)
        .put("trigger_event_ids", JSONArray(triggerEventIds))
        .put("motif_keys", JSONArray(motifKeys.sorted()))
        .put("status", status.wireValue)

    companion object {
        fun fromJson(value: JSONObject): ProactiveRequestReservation = ProactiveRequestReservation(
            reservationId = value.getString("reservation_id"),
            reservedAtUtcMillis = value.getLong("reserved_at_utc_ms"),
            triggerEventIds = value.getJSONArray("trigger_event_ids").stringList(),
            motifKeys = value.getJSONArray("motif_keys").stringList().toSet(),
            status = ProactiveRequestStatus.fromWireValue(value.getString("status")),
        )
    }
}

data class ProactiveNotificationReservation(
    val notificationId: String,
    val requestReservationId: String,
    val reservedAtUtcMillis: Long,
    val mode: ProactiveEngagementMode,
    val status: ProactiveNotificationStatus = ProactiveNotificationStatus.RESERVED,
) {
    init {
        require(notificationId.isNotBlank() && requestReservationId.isNotBlank())
        require(reservedAtUtcMillis >= 0L)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("notification_id", notificationId)
        .put("request_reservation_id", requestReservationId)
        .put("reserved_at_utc_ms", reservedAtUtcMillis)
        .put("mode", mode.wireValue)
        .put("status", status.wireValue)

    companion object {
        fun fromJson(value: JSONObject): ProactiveNotificationReservation = ProactiveNotificationReservation(
            notificationId = value.getString("notification_id"),
            requestReservationId = value.getString("request_reservation_id"),
            reservedAtUtcMillis = value.getLong("reserved_at_utc_ms"),
            mode = ProactiveEngagementMode.fromWireValue(value.getString("mode")),
            status = ProactiveNotificationStatus.fromWireValue(value.getString("status")),
        )
    }
}

/** Replay protection for one completed, cancelled, expired, or revoked trigger. */
data class ProactiveEventTombstone(
    val eventId: String,
    val consumedAtUtcMillis: Long,
) {
    init {
        require(eventId.isNotBlank())
        require(consumedAtUtcMillis >= 0L)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("event_id", eventId)
        .put("consumed_at_utc_ms", consumedAtUtcMillis)

    companion object {
        fun fromJson(value: JSONObject): ProactiveEventTombstone = ProactiveEventTombstone(
            eventId = value.getString("event_id"),
            consumedAtUtcMillis = value.getLong("consumed_at_utc_ms"),
        )
    }
}

/** Versioned, serializable subdocument intended for the existing ATLAS local-state adapter. */
data class ProactiveCoachState(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val mode: ProactiveEngagementMode = ProactiveEngagementMode.NORMAL,
    val pendingTriggers: List<CoachTrigger> = emptyList(),
    val gateMemory: ProactiveTriggerGate.Memory = ProactiveTriggerGate.Memory(),
    /** Timestamped tombstones keep replay protection bounded to the accepted event-retention window. */
    val consumedEventTombstones: List<ProactiveEventTombstone> = emptyList(),
    /** All reserved attempts count against quota, including failed and crash-interrupted requests. */
    val requestReservations: List<ProactiveRequestReservation> = emptyList(),
    /** Reserved before posting, so a process death cannot create a duplicate notification. */
    val notificationReservations: List<ProactiveNotificationReservation> = emptyList(),
    val nextReservationSequence: Long = 1L,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "Unsupported proactive state schema version: $schemaVersion" }
        require(pendingTriggers.map(CoachTrigger::eventId).distinct().size == pendingTriggers.size)
        require(consumedEventTombstones.map(ProactiveEventTombstone::eventId).distinct().size == consumedEventTombstones.size)
        require(nextReservationSequence > 0L)
    }

    fun toJsonString(): String = toJson().toString()

    fun toJson(): JSONObject = JSONObject()
        .put("schema_version", schemaVersion)
        .put("mode", mode.wireValue)
        .put("pending_triggers", JSONArray().apply { pendingTriggers.forEach { put(it.toJson()) } })
        .put("gate_memory", JSONObject()
            .put("recent_event_ids", JSONArray(gateMemory.recentEventIds))
            .put("last_requested_at_utc_ms_by_motif", JSONObject(gateMemory.lastRequestedAtUtcMillisByMotif)))
        .put("consumed_event_tombstones", JSONArray().apply { consumedEventTombstones.forEach { put(it.toJson()) } })
        .put("request_reservations", JSONArray().apply { requestReservations.forEach { put(it.toJson()) } })
        .put("notification_reservations", JSONArray().apply { notificationReservations.forEach { put(it.toJson()) } })
        .put("next_reservation_sequence", nextReservationSequence)

    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
        private const val LEGACY_SCHEMA_VERSION = 1

        fun fromJsonString(
            json: String,
            migrationNowUtcMillis: Long = System.currentTimeMillis(),
        ): ProactiveCoachState = fromJson(JSONObject(json), migrationNowUtcMillis)

        fun fromJson(
            value: JSONObject,
            migrationNowUtcMillis: Long = System.currentTimeMillis(),
        ): ProactiveCoachState {
            require(migrationNowUtcMillis >= 0L)
            val version = value.getInt("schema_version")
            require(version == CURRENT_SCHEMA_VERSION || version == LEGACY_SCHEMA_VERSION) {
                "Unsupported proactive state schema version: $version"
            }
            val gate = value.getJSONObject("gate_memory")
            val motifTimes = buildMap {
                val names = gate.getJSONObject("last_requested_at_utc_ms_by_motif").keys()
                while (names.hasNext()) {
                    val key = names.next()
                    put(key, gate.getJSONObject("last_requested_at_utc_ms_by_motif").getLong(key))
                }
            }
            val requestReservations = value.getJSONArray("request_reservations").jsonObjects()
                .map { ProactiveRequestReservation.fromJson(it) }
            val consumedEventsJson = value.getJSONArray(
                if (version == LEGACY_SCHEMA_VERSION) "consumed_event_ids" else "consumed_event_tombstones",
            )
            val consumedTombstones = if (version == LEGACY_SCHEMA_VERSION) {
                val reservedAtByEventId = buildMap {
                    requestReservations.forEach { reservation ->
                        reservation.triggerEventIds.forEach { eventId -> put(eventId, reservation.reservedAtUtcMillis) }
                    }
                }
                buildList {
                    for (index in 0 until consumedEventsJson.length()) {
                        val eventId = consumedEventsJson.getString(index)
                        add(ProactiveEventTombstone(
                            eventId = eventId,
                            // Legacy cancellation/revocation IDs had no timestamp; conservatively
                            // preserve them for one full replay window from schema migration.
                            consumedAtUtcMillis = reservedAtByEventId[eventId] ?: migrationNowUtcMillis,
                        ))
                    }
                }.distinctBy(ProactiveEventTombstone::eventId)
            } else {
                consumedEventsJson.jsonObjects().map { ProactiveEventTombstone.fromJson(it) }
            }
            return ProactiveCoachState(
                schemaVersion = CURRENT_SCHEMA_VERSION,
                mode = ProactiveEngagementMode.fromWireValue(value.getString("mode")),
                pendingTriggers = value.getJSONArray("pending_triggers").jsonObjects().map { CoachTrigger.fromJson(it) },
                gateMemory = ProactiveTriggerGate.Memory(
                    recentEventIds = gate.getJSONArray("recent_event_ids").stringList(),
                    lastRequestedAtUtcMillisByMotif = motifTimes,
                ),
                consumedEventTombstones = consumedTombstones,
                requestReservations = requestReservations,
                notificationReservations = value.getJSONArray("notification_reservations").jsonObjects()
                    .map { ProactiveNotificationReservation.fromJson(it) },
                nextReservationSequence = value.getLong("next_reservation_sequence"),
            )
        }
    }
}

data class ProactivePlanRequest(
    val reservationId: String,
    val requestedAtUtcMillis: Long,
    val mode: ProactiveEngagementMode,
    val triggers: List<CoachTrigger>,
)

/** The S6 `/v2/agent/plan` adapter supplies notification copy only after a successful plan response. */
data class ProactivePlanResponse(
    val notification: ProactiveNotificationContent? = null,
)

data class ProactiveNotificationContent(
    val title: String,
    val body: String,
) {
    init {
        require(title.isNotBlank() && title.length <= MAX_TITLE_LENGTH)
        require(body.isNotBlank() && body.length <= MAX_BODY_LENGTH)
    }

    companion object {
        const val MAX_TITLE_LENGTH = 120
        const val MAX_BODY_LENGTH = 500
    }
}

data class ProactiveNotification(
    val notificationId: String,
    val title: String,
    val body: String,
    val mode: ProactiveEngagementMode,
    val triggerSources: Set<CoachTriggerSource>,
)

enum class ProactiveRunStatus {
    REQUESTED,
    REQUEST_FAILED,
    NOTIFICATION_FAILED,
    NOTIFICATION_QUOTA_REACHED,
    CONSENT_REVOKED,
    NO_ELIGIBLE_TRIGGERS,
    QUIET_HOURS,
    MOTIF_COOLDOWN,
    DAILY_REQUEST_QUOTA,
    PERSISTENCE_FAILED,
}

data class ProactiveRunResult(
    val status: ProactiveRunStatus,
    val reservationId: String? = null,
    val groupedTriggerCount: Int = 0,
    val notificationReserved: Boolean = false,
)

enum class CoachTriggerRecordStatus {
    RECORDED,
    DUPLICATE,
    UNRELIABLE,
    CONSENT_REQUIRED,
    OUTSIDE_RETENTION,
    FUTURE_EVENT,
    QUEUE_FULL,
    PERSISTENCE_FAILED,
}

/**
 * Deterministic scheduler: all state persistence and external effects are injected. The write
 * callback must synchronously and durably replace the versioned state before it returns.
 */
class ProactiveCoachScheduler(
    private val readState: () -> ProactiveCoachState,
    private val writeState: (ProactiveCoachState) -> Unit,
    private val currentConsent: () -> ProactiveConsentSnapshot,
    private val triggerGate: ProactiveTriggerGate = ProactiveTriggerGate(),
    private val clockUtcMillis: () -> Long = System::currentTimeMillis,
) {
    fun record(event: CoachTrigger, nowUtcMillis: Long = clockUtcMillis()): CoachTriggerRecordStatus {
        if (event.reliability != CoachTriggerReliability.VERIFIED) return CoachTriggerRecordStatus.UNRELIABLE
        if (nowUtcMillis < event.occurredAtUtcMillis) return CoachTriggerRecordStatus.FUTURE_EVENT
        if (nowUtcMillis - event.occurredAtUtcMillis > MAX_TRIGGER_AGE_MS) {
            return CoachTriggerRecordStatus.OUTSIDE_RETENTION
        }
        val consent = runCatching(currentConsent).getOrElse { return CoachTriggerRecordStatus.PERSISTENCE_FAILED }
        if (!consent.allows(event)) return CoachTriggerRecordStatus.CONSENT_REQUIRED

        return synchronized(PROCESS_STATE_LOCK) {
            val state = readStateDetached() ?: return@synchronized CoachTriggerRecordStatus.PERSISTENCE_FAILED
            val unexpiredState = pruneTombstones(state, nowUtcMillis)
            if (unexpiredState != state && !persist(unexpiredState)) {
                return@synchronized CoachTriggerRecordStatus.PERSISTENCE_FAILED
            }
            if (unexpiredState.consumedEventTombstones.any { it.eventId == event.eventId } ||
                unexpiredState.pendingTriggers.any { it.eventId == event.eventId } ||
                triggerGate.hasSeen(event.eventId, unexpiredState.gateMemory)
            ) return@synchronized CoachTriggerRecordStatus.DUPLICATE
            if (unexpiredState.pendingTriggers.size >= MAX_PENDING_TRIGGERS) return@synchronized CoachTriggerRecordStatus.QUEUE_FULL

            val updated = unexpiredState.copy(
                pendingTriggers = unexpiredState.pendingTriggers + event,
                gateMemory = triggerGate.remember(event.eventId, unexpiredState.gateMemory),
            )
            if (!persist(updated)) CoachTriggerRecordStatus.PERSISTENCE_FAILED else CoachTriggerRecordStatus.RECORDED
        }
    }

    /**
     * Groups every currently due, reliable, consented event into one plan request. The request
     * quota, motif cooldown, event tombstones, and notification slot are persisted before their
     * respective network or Android-notification callbacks run.
     */
    fun runDue(
        nowUtcMillis: Long,
        mandateZoneId: ZoneId,
        requestPlan: (ProactivePlanRequest) -> ProactivePlanResponse?,
        showNotification: (ProactiveNotification) -> Unit,
    ): ProactiveRunResult {
        require(nowUtcMillis >= 0L)
        val initialConsent = runCatching(currentConsent).getOrElse {
            return ProactiveRunResult(ProactiveRunStatus.PERSISTENCE_FAILED)
        }
        val reservation = synchronized(PROCESS_STATE_LOCK) {
            reserveDue(nowUtcMillis, mandateZoneId, initialConsent)
        }
        if (reservation.result.status != ProactiveRunStatus.REQUESTED) return reservation.result
        val request = requireNotNull(reservation.request)

        val preflightConsent = runCatching(currentConsent).getOrElse {
            return markRequestStatus(request.reservationId, ProactiveRequestStatus.REVOKED_BEFORE_REQUEST,
                ProactiveRunStatus.CONSENT_REVOKED, request.triggers.size)
        }
        if (request.triggers.any { !preflightConsent.allows(it) }) {
            return markRequestStatus(request.reservationId, ProactiveRequestStatus.REVOKED_BEFORE_REQUEST,
                ProactiveRunStatus.CONSENT_REVOKED, request.triggers.size)
        }

        val response = try {
            requestPlan(request)
        } catch (_: Exception) {
            null
        }
        if (response == null) {
            return markRequestStatus(request.reservationId, ProactiveRequestStatus.FAILED,
                ProactiveRunStatus.REQUEST_FAILED, request.triggers.size)
        }
        if (!markRequestStatus(request.reservationId, ProactiveRequestStatus.SUCCEEDED,
                ProactiveRunStatus.REQUESTED, request.triggers.size).let { it.status == ProactiveRunStatus.REQUESTED }
        ) {
            return ProactiveRunResult(ProactiveRunStatus.PERSISTENCE_FAILED, request.reservationId, request.triggers.size)
        }

        val content = response.notification ?: return ProactiveRunResult(
            ProactiveRunStatus.REQUESTED, request.reservationId, request.triggers.size,
        )
        val latestConsent = runCatching(currentConsent).getOrElse {
            return ProactiveRunResult(ProactiveRunStatus.CONSENT_REVOKED, request.reservationId, request.triggers.size)
        }
        if (request.triggers.any { !latestConsent.allows(it) }) {
            return ProactiveRunResult(ProactiveRunStatus.CONSENT_REVOKED, request.reservationId, request.triggers.size)
        }

        val notification = synchronized(PROCESS_STATE_LOCK) {
            val notificationAt = maxOf(nowUtcMillis, runCatching(clockUtcMillis).getOrDefault(nowUtcMillis))
            reserveNotification(request, content, notificationAt, mandateZoneId)
        }
        if (notification.result.status != ProactiveRunStatus.REQUESTED) return notification.result
        val delivery = requireNotNull(notification.notification)
        val deliverySucceeded = try {
            showNotification(delivery)
            true
        } catch (_: Exception) {
            false
        }
        val updated = markNotificationStatus(
            delivery.notificationId,
            if (deliverySucceeded) ProactiveNotificationStatus.DELIVERED else ProactiveNotificationStatus.FAILED,
        )
        if (!updated) return ProactiveRunResult(
            ProactiveRunStatus.PERSISTENCE_FAILED, request.reservationId, request.triggers.size, notificationReserved = true,
        )
        return ProactiveRunResult(
            if (deliverySucceeded) ProactiveRunStatus.REQUESTED else ProactiveRunStatus.NOTIFICATION_FAILED,
            request.reservationId,
            request.triggers.size,
            notificationReserved = true,
        )
    }

    fun cancel(eventId: String): Boolean = synchronized(PROCESS_STATE_LOCK) {
        val state = readStateDetached() ?: return@synchronized false
        val exists = state.pendingTriggers.any { it.eventId == eventId }
        if (!exists) return@synchronized false
        val nowUtcMillis = runCatching(clockUtcMillis).getOrNull()?.takeIf { it >= 0L }
            ?: return@synchronized false
        val updated = state.copy(
            pendingTriggers = state.pendingTriggers.filterNot { it.eventId == eventId },
            consumedEventTombstones = appendTombstones(state.consumedEventTombstones, listOf(eventId), nowUtcMillis),
        )
        persist(pruneTombstones(updated, nowUtcMillis))
    }

    /** Cancel all queued events while preserving quota history and bounded replay protection. */
    fun cancelAllPending(): Int = synchronized(PROCESS_STATE_LOCK) {
        val state = readStateDetached() ?: return@synchronized 0
        val nowUtcMillis = runCatching(clockUtcMillis).getOrNull()?.takeIf { it >= 0L }
            ?: return@synchronized 0
        val eventIds = state.pendingTriggers.map(CoachTrigger::eventId)
        val updated = pruneTombstones(
            state.copy(
                pendingTriggers = emptyList(),
                consumedEventTombstones = appendTombstones(state.consumedEventTombstones, eventIds, nowUtcMillis),
            ),
            nowUtcMillis,
        )
        if (updated == state) return@synchronized 0
        if (persist(updated)) eventIds.size else 0
    }

    /** Purge pending events immediately after a revocation; the tombstones prevent later replay. */
    fun purgeRevoked(): Int {
        val consent = runCatching(currentConsent).getOrElse { return 0 }
        return synchronized(PROCESS_STATE_LOCK) {
            val state = readStateDetached() ?: return@synchronized 0
            val nowUtcMillis = runCatching(clockUtcMillis).getOrNull()?.takeIf { it >= 0L }
                ?: return@synchronized 0
            val revokedIds = state.pendingTriggers.filterNot(consent::allows).map(CoachTrigger::eventId)
            if (revokedIds.isEmpty()) return@synchronized 0
            val revokedSet = revokedIds.toSet()
            val updated = state.copy(
                pendingTriggers = state.pendingTriggers.filterNot { it.eventId in revokedSet },
                consumedEventTombstones = appendTombstones(state.consumedEventTombstones, revokedIds, nowUtcMillis),
            )
            if (persist(pruneTombstones(updated, nowUtcMillis))) revokedIds.size else 0
        }
    }

    fun mode(): ProactiveEngagementMode? = synchronized(PROCESS_STATE_LOCK) { readStateDetached()?.mode }

    fun setMode(mode: ProactiveEngagementMode): Boolean = synchronized(PROCESS_STATE_LOCK) {
        val state = readStateDetached() ?: return@synchronized false
        if (state.mode == mode) return@synchronized true
        persist(state.copy(mode = mode))
    }

    private fun reserveDue(
        nowUtcMillis: Long,
        zoneId: ZoneId,
        currentConsent: ProactiveConsentSnapshot,
    ): ReservationOutcome {
        val state = readStateDetached()
            ?: return ReservationOutcome(ProactiveRunResult(ProactiveRunStatus.PERSISTENCE_FAILED))
        val expiredOrRevoked = state.pendingTriggers.filter { trigger ->
            !currentConsent.allows(trigger) || trigger.reliability != CoachTriggerReliability.VERIFIED ||
                nowUtcMillis - trigger.occurredAtUtcMillis > MAX_TRIGGER_AGE_MS
        }.map(CoachTrigger::eventId).toSet()
        var cleaned = if (expiredOrRevoked.isEmpty()) state else state.copy(
            pendingTriggers = state.pendingTriggers.filterNot { it.eventId in expiredOrRevoked },
            consumedEventTombstones = appendTombstones(state.consumedEventTombstones, expiredOrRevoked, nowUtcMillis),
        )
        cleaned = pruneTombstones(cleaned, nowUtcMillis)
        // Old ledgers are unnecessary for quotas or cooldowns; retain eight days for timezone shifts.
        val ledgerCutoff = (nowUtcMillis - LEDGER_RETENTION_MS).coerceAtLeast(0L)
        val prunedRequests = cleaned.requestReservations.filter { it.reservedAtUtcMillis >= ledgerCutoff }
        val prunedNotifications = cleaned.notificationReservations.filter { it.reservedAtUtcMillis >= ledgerCutoff }
        if (prunedRequests.size != cleaned.requestReservations.size || prunedNotifications.size != cleaned.notificationReservations.size) {
            cleaned = cleaned.copy(requestReservations = prunedRequests, notificationReservations = prunedNotifications)
        }
        if (cleaned != state && !persist(cleaned)) {
            return ReservationOutcome(ProactiveRunResult(ProactiveRunStatus.PERSISTENCE_FAILED))
        }

        if (cleaned.pendingTriggers.isEmpty()) {
            return ReservationOutcome(ProactiveRunResult(ProactiveRunStatus.NO_ELIGIBLE_TRIGGERS))
        }
        val dueTriggers = cleaned.pendingTriggers.filter { it.occurredAtUtcMillis <= nowUtcMillis }
        if (dueTriggers.isEmpty()) return ReservationOutcome(ProactiveRunResult(ProactiveRunStatus.NO_ELIGIBLE_TRIGGERS))
        val localTime = Instant.ofEpochMilli(nowUtcMillis).atZone(zoneId).toLocalTime()
        val quietHours = localTime >= SILENT_FROM || localTime < SILENT_UNTIL
        val notSilenced = dueTriggers.filter { !quietHours || it.manual }
        if (notSilenced.isEmpty()) return ReservationOutcome(ProactiveRunResult(ProactiveRunStatus.QUIET_HOURS))

        val cooldownEligible = notSilenced.filter { trigger ->
            triggerGate.canRequest(setOf(trigger.motifKey), nowUtcMillis, cleaned.gateMemory)
        }
        if (cooldownEligible.isEmpty()) return ReservationOutcome(ProactiveRunResult(ProactiveRunStatus.MOTIF_COOLDOWN))

        val localDate = Instant.ofEpochMilli(nowUtcMillis).atZone(zoneId).toLocalDate()
        val requestsToday = cleaned.requestReservations.count { it.reservedAtUtcMillis.localDate(zoneId) == localDate }
        if (requestsToday >= MAX_REQUESTS_PER_LOCAL_DAY) {
            return ReservationOutcome(ProactiveRunResult(ProactiveRunStatus.DAILY_REQUEST_QUOTA))
        }

        val eventIds = cooldownEligible.map(CoachTrigger::eventId)
        val motifs = cooldownEligible.map(CoachTrigger::motifKey).toSet()
        val reservationId = "proactive-${cleaned.nextReservationSequence}"
        val reservationRecord = ProactiveRequestReservation(
            reservationId = reservationId,
            reservedAtUtcMillis = nowUtcMillis,
            triggerEventIds = eventIds,
            motifKeys = motifs,
        )
        val request = ProactivePlanRequest(
            reservationId = reservationId,
            requestedAtUtcMillis = nowUtcMillis,
            mode = cleaned.mode,
            triggers = cooldownEligible,
        )
        val next = cleaned.copy(
            pendingTriggers = cleaned.pendingTriggers.filterNot { it.eventId in eventIds.toSet() },
            consumedEventTombstones = appendTombstones(cleaned.consumedEventTombstones, eventIds, nowUtcMillis),
            gateMemory = triggerGate.markRequested(motifs, nowUtcMillis, cleaned.gateMemory),
            requestReservations = cleaned.requestReservations + reservationRecord,
            nextReservationSequence = cleaned.nextReservationSequence + 1L,
        )
        if (!persist(next)) return ReservationOutcome(ProactiveRunResult(ProactiveRunStatus.PERSISTENCE_FAILED))
        return ReservationOutcome(
            ProactiveRunResult(ProactiveRunStatus.REQUESTED, reservationId, cooldownEligible.size),
            request,
        )
    }

    private fun reserveNotification(
        request: ProactivePlanRequest,
        content: ProactiveNotificationContent,
        nowUtcMillis: Long,
        zoneId: ZoneId,
    ): NotificationOutcome {
        val state = readStateDetached()
            ?: return NotificationOutcome(ProactiveRunResult(ProactiveRunStatus.PERSISTENCE_FAILED, request.reservationId))
        val localDate = Instant.ofEpochMilli(nowUtcMillis).atZone(zoneId).toLocalDate()
        val localTime = Instant.ofEpochMilli(nowUtcMillis).atZone(zoneId).toLocalTime()
        val quietHours = localTime >= SILENT_FROM || localTime < SILENT_UNTIL
        if (quietHours && request.triggers.none(CoachTrigger::manual)) {
            return NotificationOutcome(ProactiveRunResult(
                ProactiveRunStatus.QUIET_HOURS, request.reservationId, request.triggers.size,
            ))
        }
        val notificationsToday = state.notificationReservations.count { it.reservedAtUtcMillis.localDate(zoneId) == localDate }
        if (notificationsToday >= state.mode.notificationLimitPerLocalDay) {
            return NotificationOutcome(ProactiveRunResult(
                ProactiveRunStatus.NOTIFICATION_QUOTA_REACHED, request.reservationId, request.triggers.size,
            ))
        }
        val notificationId = "${request.reservationId}-notification"
        if (state.notificationReservations.any { it.notificationId == notificationId }) {
            return NotificationOutcome(ProactiveRunResult(
                ProactiveRunStatus.NOTIFICATION_QUOTA_REACHED, request.reservationId, request.triggers.size,
            ))
        }
        val modeNow = state.mode
        val record = ProactiveNotificationReservation(
            notificationId = notificationId,
            requestReservationId = request.reservationId,
            reservedAtUtcMillis = nowUtcMillis,
            mode = modeNow,
        )
        val next = state.copy(notificationReservations = state.notificationReservations + record)
        if (!persist(next)) {
            return NotificationOutcome(ProactiveRunResult(ProactiveRunStatus.PERSISTENCE_FAILED, request.reservationId))
        }
        return NotificationOutcome(
            ProactiveRunResult(ProactiveRunStatus.REQUESTED, request.reservationId, request.triggers.size, notificationReserved = true),
            ProactiveNotification(
                notificationId = notificationId,
                title = content.title,
                body = content.body,
                mode = modeNow,
                triggerSources = request.triggers.map(CoachTrigger::source).toSet(),
            ),
        )
    }

    private fun markRequestStatus(
        reservationId: String,
        status: ProactiveRequestStatus,
        result: ProactiveRunStatus,
        triggerCount: Int,
    ): ProactiveRunResult = synchronized(PROCESS_STATE_LOCK) {
        val state = readStateDetached()
            ?: return@synchronized ProactiveRunResult(ProactiveRunStatus.PERSISTENCE_FAILED, reservationId, triggerCount)
        val updatedReservations = state.requestReservations.map { reservation ->
            if (reservation.reservationId == reservationId) reservation.copy(status = status) else reservation
        }
        if (updatedReservations.none { it.reservationId == reservationId }) {
            return@synchronized ProactiveRunResult(ProactiveRunStatus.PERSISTENCE_FAILED, reservationId, triggerCount)
        }
        if (!persist(state.copy(requestReservations = updatedReservations))) {
            ProactiveRunResult(ProactiveRunStatus.PERSISTENCE_FAILED, reservationId, triggerCount)
        } else {
            ProactiveRunResult(result, reservationId, triggerCount)
        }
    }

    private fun markNotificationStatus(notificationId: String, status: ProactiveNotificationStatus): Boolean =
        synchronized(PROCESS_STATE_LOCK) {
            val state = readStateDetached() ?: return@synchronized false
            val updatedReservations = state.notificationReservations.map { reservation ->
                if (reservation.notificationId == notificationId) reservation.copy(status = status) else reservation
            }
            if (updatedReservations.none { it.notificationId == notificationId }) return@synchronized false
            persist(state.copy(notificationReservations = updatedReservations))
        }

    private fun readStateDetached(): ProactiveCoachState? = runCatching(readState).getOrNull()?.detached()

    private fun persist(state: ProactiveCoachState): Boolean = runCatching { writeState(state.detached()) }.isSuccess

    private fun ProactiveCoachState.detached(): ProactiveCoachState = copy(
        pendingTriggers = pendingTriggers.toList(),
        gateMemory = gateMemory.copy(
            recentEventIds = gateMemory.recentEventIds.toList(),
            lastRequestedAtUtcMillisByMotif = gateMemory.lastRequestedAtUtcMillisByMotif.toMap(),
        ),
        consumedEventTombstones = consumedEventTombstones.toList(),
        requestReservations = requestReservations.map { it.copy(
            triggerEventIds = it.triggerEventIds.toList(),
            motifKeys = it.motifKeys.toSet(),
        ) },
        notificationReservations = notificationReservations.toList(),
    )

    private fun pruneTombstones(state: ProactiveCoachState, nowUtcMillis: Long): ProactiveCoachState {
        val cutoff = (nowUtcMillis - MAX_TRIGGER_AGE_MS).coerceAtLeast(0L)
        val retained = state.consumedEventTombstones.filter { it.consumedAtUtcMillis >= cutoff }
        return if (retained.size == state.consumedEventTombstones.size) state
        else state.copy(consumedEventTombstones = retained)
    }

    private data class ReservationOutcome(
        val result: ProactiveRunResult,
        val request: ProactivePlanRequest? = null,
    )

    private data class NotificationOutcome(
        val result: ProactiveRunResult,
        val notification: ProactiveNotification? = null,
    )

    companion object {
        /** One process-wide lock serializes controller, UI, and WorkManager scheduler instances. */
        private val PROCESS_STATE_LOCK = Any()

        const val MAX_REQUESTS_PER_LOCAL_DAY = 6
        const val SILENT_FROM_HOUR = 22
        const val SILENT_UNTIL_HOUR = 8
        const val MAX_PENDING_TRIGGERS = 200
        const val MAX_TRIGGER_AGE_MS = 7L * 24L * 60L * 60L * 1_000L
        private const val LEDGER_RETENTION_MS = 8L * 24L * 60L * 60L * 1_000L
        private const val SILENT_FROM_MINUTE = 0
        private const val SILENT_UNTIL_MINUTE = 0
        private val SILENT_FROM = LocalTime.of(SILENT_FROM_HOUR, SILENT_FROM_MINUTE)
        private val SILENT_UNTIL = LocalTime.of(SILENT_UNTIL_HOUR, SILENT_UNTIL_MINUTE)

        private fun appendTombstones(
            existing: List<ProactiveEventTombstone>,
            eventIds: Iterable<String>,
            consumedAtUtcMillis: Long,
        ): List<ProactiveEventTombstone> {
            val result = LinkedHashMap<String, ProactiveEventTombstone>()
            existing.forEach { result[it.eventId] = it }
            eventIds.forEach { eventId ->
                result.putIfAbsent(eventId, ProactiveEventTombstone(eventId, consumedAtUtcMillis))
            }
            return result.values.toList()
        }

        private fun Long.localDate(zoneId: ZoneId): LocalDate = Instant.ofEpochMilli(this).atZone(zoneId).toLocalDate()

    }
}
