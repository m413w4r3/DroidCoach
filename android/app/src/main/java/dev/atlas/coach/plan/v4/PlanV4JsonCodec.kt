package dev.atlas.coach.plan.v4

import dev.atlas.coach.plan.ActivityStatus
import dev.atlas.coach.plan.DayPlan
import dev.atlas.coach.plan.PlanActivity
import dev.atlas.coach.plan.PlanJsonCodec
import dev.atlas.coach.plan.PlanRevisionEngine
import dev.atlas.coach.plan.PlanStatusPolicy
import dev.atlas.coach.plan.PlanStatusEvent
import dev.atlas.coach.plan.PlanStoreDocument
import dev.atlas.coach.plan.StatusProvenance
import dev.atlas.coach.plan.StatusSource
import dev.atlas.coach.plan.requireCanonicalUuid
import java.time.OffsetDateTime
import org.json.JSONArray
import org.json.JSONObject

internal data class PlanReportReceipt(
    val idempotencyKey: String,
    val requestFingerprint: String,
    val revisionId: String,
    val originalActivityId: String,
    val replacementActivityId: String,
    val planVersion: Int,
    val scopeHash: String,
    val reason: String,
) {
    init {
        requireCanonicalUuid(idempotencyKey, "idempotencyKey")
        requireCanonicalUuid(revisionId, "revisionId")
        requireCanonicalUuid(originalActivityId, "originalActivityId")
        requireCanonicalUuid(replacementActivityId, "replacementActivityId")
        require(requestFingerprint.matches(HEX_256)) { "requestFingerprint must be SHA-256" }
        require(scopeHash.matches(HEX_256)) { "scopeHash must be SHA-256" }
        require(planVersion >= 2) { "report planVersion must be >= 2" }
        require(reason.isNotBlank() && reason.length <= 280) { "report reason must contain 1..280 characters" }
    }

    companion object {
        private val HEX_256 = Regex("[0-9a-f]{64}")
    }
}

internal data class PlanV4StoreDocument(
    val store: PlanStoreDocument,
    val statusEvents: List<PlanStatusEvent> = emptyList(),
    val reportReceipts: List<PlanReportReceipt> = emptyList(),
    /** Exact schema-3 bytes present when this document first upgrades. */
    val schema3Original: String? = null,
)

/** V4 wraps the complete schema-3 projection so older readers can roll back safely. */
internal object PlanV4JsonCodec {
    const val SCHEMA_VERSION = 4

    fun encodeStore(document: PlanV4StoreDocument): String {
        val stateV3 = PlanJsonCodec.encodeStore(document.store.withoutLineage())
        val lineages = JSONArray().apply {
            document.store.dayPlan.activities
                .filter { it.continuityId != null || it.supersedesActivityId != null }
                .sortedBy { it.activityId }
                .forEach { activity ->
                    put(JSONObject().apply {
                        put("activity_id", activity.activityId)
                        activity.continuityId?.let { put("continuity_id", it) }
                        activity.supersedesActivityId?.let { put("supersedes_activity_id", it) }
                    })
                }
        }
        return JSONObject().apply {
            put("schema_version", SCHEMA_VERSION)
            put("schema3_original", document.schema3Original ?: JSONObject.NULL)
            put("schema3_state", stateV3)
            put("activity_lineage", lineages)
            put("status_events", JSONArray().apply { document.statusEvents.forEach { put(it.toJson()) } })
            put("report_receipts", JSONArray().apply { document.reportReceipts.forEach { put(it.toJson()) } })
        }.toString()
    }

    fun decodeStore(json: String): PlanV4StoreDocument {
        val root = JSONObject(json)
        root.requireKeys(
            setOf("schema_version", "schema3_original", "schema3_state", "activity_lineage", "status_events", "report_receipts"),
        )
        require(root.int("schema_version") == SCHEMA_VERSION) { "unsupported plan schema version" }
        val original = root.nullableString("schema3_original")
        original?.let(PlanJsonCodec::decodeStore)
        val stateV3 = root.string("schema3_state")
        val base = PlanJsonCodec.decodeStore(stateV3)
        val lineage = root.array("activity_lineage").objects().associate { item ->
            item.requireKeys(setOf("activity_id"), setOf("continuity_id", "supersedes_activity_id"))
            val activityId = item.string("activity_id")
            requireCanonicalUuid(activityId, "activityId")
            activityId to ActivityLineage(
                continuityId = item.nullableString("continuity_id"),
                supersedesActivityId = item.nullableString("supersedes_activity_id"),
            )
        }
        require(lineage.size == root.array("activity_lineage").length()) { "duplicate activity lineage" }
        val activities = base.dayPlan.activities.map { activity ->
            val links = lineage[activity.activityId]
            if (links == null) activity else activity.copy(
                continuityId = links.continuityId,
                supersedesActivityId = links.supersedesActivityId,
            )
        }
        require(lineage.keys.all { key -> activities.any { it.activityId == key } }) { "lineage references an unknown activity" }
        val events = root.array("status_events").objects().map { it.toStatusEvent() }
        require(events.map { it.statusEventId }.toSet().size == events.size) { "duplicate status event" }
        val receipts = root.array("report_receipts").objects().map { it.toReportReceipt() }
        require(receipts.map { it.idempotencyKey }.toSet().size == receipts.size) { "duplicate report idempotency key" }
        val plan = base.dayPlan.copy(activities = activities)
        require(events.all { it.planId == plan.planId && it.planVersion <= plan.version }) { "status event belongs to a future or different plan" }
        require(receipts.all { it.planVersion <= plan.version }) { "report receipt belongs to a future plan version" }
        events.forEach { it.validateAgainst(plan, receipts) }
        return PlanV4StoreDocument(
            store = base.copy(dayPlan = plan),
            statusEvents = events,
            reportReceipts = receipts,
            schema3Original = original,
        )
    }

    /** Returns the current schema-3 projection for rollback, without changing the V4 source. */
    fun schema3RollbackDocument(json: String): String = JSONObject(json).string("schema3_state")

    private data class ActivityLineage(val continuityId: String?, val supersedesActivityId: String?)

    private fun PlanStatusEvent.validateAgainst(plan: DayPlan, receipts: List<PlanReportReceipt>) {
        val terminalStatuses = setOf(
            ActivityStatus.DONE,
            ActivityStatus.MISSED,
            ActivityStatus.CANCELLED,
            ActivityStatus.POSTPONED,
        )
        when (provenance) {
            StatusProvenance.DECLARED,
            StatusProvenance.CONFIRMED,
            -> {
                require(source == StatusSource.USER_UI) { "user status provenance requires USER_UI source" }
                require(fromStatus != toStatus) { "user status event must change status" }
            }
            StatusProvenance.MEASURED -> {
                require(source in setOf(StatusSource.FOCUS_TIMER, StatusSource.USAGE_STATS, StatusSource.WGER_READ, StatusSource.CORE_IMPORT)) {
                    "measured status event requires an observation source"
                }
                require(fromStatus == toStatus) { "measured observation cannot change status" }
                require(fromStatus !in terminalStatuses) { "measured observation cannot establish a terminal status" }
            }
            StatusProvenance.UNKNOWN -> {
                require(source in setOf(StatusSource.USAGE_STATS, StatusSource.WGER_READ, StatusSource.CORE_IMPORT)) {
                    "unknown status provenance requires an imported or observed source"
                }
                require(fromStatus != toStatus) { "unknown status event must describe a transition" }
                require(toStatus !in terminalStatuses) { "unknown provenance cannot establish a terminal status" }
            }
        }
        if (source == StatusSource.FOCUS_TIMER) {
            require(provenance == StatusProvenance.MEASURED) { "focus timer cannot decide a status" }
        }
        if (toStatus in setOf(ActivityStatus.DONE, ActivityStatus.MISSED, ActivityStatus.CANCELLED)) {
            require(
                source == StatusSource.USER_UI &&
                    provenance in setOf(StatusProvenance.DECLARED, StatusProvenance.CONFIRMED),
            ) { "terminal completion status requires a user decision" }
        }
        if (toStatus == ActivityStatus.MISSED) {
            // New events carry their own slot end. Legacy V4 events without it can only
            // use the current schedule when they describe the current plan version; a
            // later version may have moved or removed the activity, so no safe inference
            // is possible from that projection alone. Such legacy events stay readable
            // as unverified historical decisions; the current slot is not evidence for them.
            val historicalSlotEnd = slotEndAt?.let { OffsetDateTime.parse(it).toInstant() }
                ?: if (planVersion == plan.version) {
                    plan.activities.singleOrNull { it.activityId == activityId }?.let { activity ->
                        val schedule = activity.scheduledLocal
                        PlanRevisionEngine().localToInstant(schedule)
                            .plusSeconds(schedule.durationMinutes.toLong() * 60L)
                    }
                } else {
                    null
                }
            if (historicalSlotEnd != null) {
                require(!OffsetDateTime.parse(decidedAt).toInstant().isBefore(historicalSlotEnd)) {
                    "MISSED status decision precedes the historical scheduled slot end"
                }
            }
        }
        if (toStatus == ActivityStatus.POSTPONED) {
            val report = receipts.singleOrNull {
                it.originalActivityId == activityId && it.planVersion == planVersion
            } ?: error("POSTPONED status requires an atomic report receipt")
            require(source == StatusSource.USER_UI && provenance == StatusProvenance.CONFIRMED) {
                "POSTPONED status requires a confirmed user report"
            }
            val original = plan.activities.firstOrNull { it.activityId == activityId }
            val replacement = plan.activities.firstOrNull { it.activityId == report.replacementActivityId }
            require(original?.status == ActivityStatus.POSTPONED) { "report original is not POSTPONED" }
            require(
                replacement?.supersedesActivityId == activityId &&
                    replacement.continuityId == continuityId,
            ) { "report replacement lineage does not match status event" }
        } else {
            require(PlanStatusPolicy.structuralRejection(fromStatus, toStatus) == null) {
                "status event has an invalid transition"
            }
        }
        if (planVersion == plan.version) {
            require(plan.activities.singleOrNull { it.activityId == activityId }?.status == toStatus) {
                "current-version status event does not match the plan projection"
            }
        }
    }

    private fun PlanStoreDocument.withoutLineage(): PlanStoreDocument = copy(
        dayPlan = dayPlan.copy(activities = dayPlan.activities.map { it.copy(continuityId = null, supersedesActivityId = null) }),
    )

    private fun PlanStatusEvent.toJson() = JSONObject().apply {
        put("status_event_id", statusEventId)
        put("plan_id", planId)
        put("activity_id", activityId)
        put("continuity_id", continuityId ?: JSONObject.NULL)
        put("from_status", fromStatus.name)
        put("to_status", toStatus.name)
        put("provenance", provenance.name)
        put("source", source.name)
        put("decided_at", decidedAt)
        put("observed_at", observedAt)
        put("plan_version", planVersion)
        put("consent_version", consentVersion)
        put("focus_session_id", focusSessionId ?: JSONObject.NULL)
        slotEndAt?.let { put("slot_end_at", it) }
    }

    private fun JSONObject.toStatusEvent(): PlanStatusEvent {
        requireKeys(
            setOf("status_event_id", "plan_id", "activity_id", "continuity_id", "from_status", "to_status", "provenance", "source", "decided_at", "observed_at", "plan_version", "consent_version", "focus_session_id"),
            setOf("slot_end_at"),
        )
        val decidedAt = string("decided_at")
        val observedAt = string("observed_at")
        OffsetDateTime.parse(decidedAt)
        OffsetDateTime.parse(observedAt)
        return PlanStatusEvent(
            statusEventId = string("status_event_id"),
            planId = string("plan_id"),
            activityId = string("activity_id"),
            continuityId = nullableString("continuity_id"),
            fromStatus = enum("from_status"),
            toStatus = enum("to_status"),
            provenance = enum("provenance"),
            source = enum("source"),
            decidedAt = decidedAt,
            observedAt = observedAt,
            planVersion = int("plan_version"),
            consentVersion = int("consent_version"),
            focusSessionId = nullableString("focus_session_id"),
            slotEndAt = nullableString("slot_end_at"),
        )
    }

    private fun PlanReportReceipt.toJson() = JSONObject().apply {
        put("idempotency_key", idempotencyKey)
        put("request_fingerprint", requestFingerprint)
        put("revision_id", revisionId)
        put("original_activity_id", originalActivityId)
        put("replacement_activity_id", replacementActivityId)
        put("plan_version", planVersion)
        put("scope_hash", scopeHash)
        put("reason", reason)
    }

    private fun JSONObject.toReportReceipt(): PlanReportReceipt {
        requireKeys(setOf("idempotency_key", "request_fingerprint", "revision_id", "original_activity_id", "replacement_activity_id", "plan_version", "scope_hash", "reason"))
        return PlanReportReceipt(
            idempotencyKey = string("idempotency_key"),
            requestFingerprint = string("request_fingerprint"),
            revisionId = string("revision_id"),
            originalActivityId = string("original_activity_id"),
            replacementActivityId = string("replacement_activity_id"),
            planVersion = int("plan_version"),
            scopeHash = string("scope_hash"),
            reason = string("reason"),
        )
    }

    private fun JSONObject.requireKeys(required: Set<String>, optional: Set<String> = emptySet()) {
        val actual = keys().asSequence().toSet()
        require(actual.containsAll(required) && actual.all { it in required || it in optional }) { "invalid V4 JSON fields" }
    }

    private fun JSONObject.string(key: String): String = opt(key) as? String ?: error("$key must be a string")
    private fun JSONObject.nullableString(key: String): String? =
        if (isNull(key)) null else string(key)

    private fun JSONObject.int(key: String): Int = opt(key) as? Int ?: error("$key must be an integer")
    private fun JSONObject.array(key: String): JSONArray = opt(key) as? JSONArray ?: error("$key must be an array")
    private inline fun <reified T : Enum<T>> JSONObject.enum(key: String): T =
        enumValues<T>().firstOrNull { it.name == string(key) } ?: error("$key has an unknown enum value")
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { index ->
        opt(index) as? JSONObject ?: error("array item must be an object")
    }
}
