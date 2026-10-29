package dev.atlas.coach.plan.v5

import dev.atlas.coach.plan.A1ActivityClass
import dev.atlas.coach.plan.A1ActivityPolicy
import dev.atlas.coach.plan.A1Consent
import dev.atlas.coach.plan.A1ExternalLinkState
import dev.atlas.coach.plan.A1MoveDiff
import dev.atlas.coach.plan.A1PersistentState
import dev.atlas.coach.plan.A1RevisionReceipt
import dev.atlas.coach.plan.DayPlan
import dev.atlas.coach.plan.LocalSchedule
import dev.atlas.coach.plan.PlanJsonCodec
import dev.atlas.coach.plan.PlanStoreDocument
import dev.atlas.coach.plan.requireCanonicalDate
import dev.atlas.coach.plan.requireCanonicalUuid
import dev.atlas.coach.plan.v4.PlanV4JsonCodec
import dev.atlas.coach.plan.v4.PlanV4StoreDocument
import org.json.JSONArray
import org.json.JSONObject

/** V5 adds local A1 policy, consent, quota, and receipts around the stable V3/V4 plan payload. */
internal data class PlanV5StoreDocument(
    val store: PlanStoreDocument,
    val statusEvents: List<dev.atlas.coach.plan.PlanStatusEvent> = emptyList(),
    val reportReceipts: List<dev.atlas.coach.plan.v4.PlanReportReceipt> = emptyList(),
    val schema3Original: String? = null,
    val a1State: A1PersistentState = A1PersistentState(),
    /** Existing V3/V4 users remain on their original envelope until they use A1 settings. */
    val usesV5Envelope: Boolean = false,
) {
    fun asV4(): PlanV4StoreDocument = PlanV4StoreDocument(
        store = store,
        statusEvents = statusEvents,
        reportReceipts = reportReceipts,
        schema3Original = schema3Original,
    )

    companion object {
        fun fromV4(document: PlanV4StoreDocument): PlanV5StoreDocument = PlanV5StoreDocument(
            store = document.store,
            statusEvents = document.statusEvents,
            reportReceipts = document.reportReceipts,
            schema3Original = document.schema3Original,
        )
    }
}

internal object PlanV5JsonCodec {
    const val SCHEMA_VERSION = 5

    fun encodeStore(document: PlanV5StoreDocument): String = JSONObject().apply {
        put("schema_version", SCHEMA_VERSION)
        put("v4_state", PlanV4JsonCodec.encodeStore(document.asV4()))
        put("a1_state", document.a1State.toJson())
    }.toString()

    /** Reads V3, V4, or V5. Legacy documents have no A1 policy and therefore resolve protected. */
    fun decodeStore(json: String): PlanV5StoreDocument {
        val root = JSONObject(json)
        val version = root.int("schema_version")
        return when (version) {
            PlanJsonCodec.SCHEMA_VERSION -> PlanV5StoreDocument.fromV4(
                PlanV4StoreDocument(
                    store = PlanJsonCodec.decodeStore(json),
                    schema3Original = json,
                ),
            )
            PlanV4JsonCodec.SCHEMA_VERSION -> PlanV5StoreDocument.fromV4(PlanV4JsonCodec.decodeStore(json))
            SCHEMA_VERSION -> {
                root.requireKeys(setOf("schema_version", "v4_state", "a1_state"))
                val base = PlanV4JsonCodec.decodeStore(root.string("v4_state"))
                val a1State = root.obj("a1_state").toA1State()
                validateA1State(base.store.dayPlan, a1State)
                PlanV5StoreDocument.fromV4(base).copy(a1State = a1State, usesV5Envelope = true)
            }
            else -> error("unsupported plan schema version $version")
        }
    }

    private fun validateA1State(plan: DayPlan, state: A1PersistentState) {
        require(state.activityPolicies.keys.all { id -> plan.activities.any { it.activityId == id } }) {
            "A1 policy references an unknown activity"
        }
        require(state.receipts.all { it.planId == plan.planId && it.versionAfter <= plan.version }) {
            "A1 receipt belongs to a future or different plan"
        }
        require(state.receipts.all { it.undoVersion == null || it.undoVersion <= plan.version }) {
            "A1 undo receipt belongs to a future plan version"
        }
        require(state.receipts.all { it.consentVersion <= state.consent.version }) {
            "A1 receipt has a future consent version"
        }
        state.quotaByLocalDate.forEach { (date, used) ->
            val appliedCount = state.receipts.count { it.localDate == date }
            require(appliedCount <= used) { "A1 quota is lower than its durable receipts" }
        }
    }

    private fun A1PersistentState.toJson(): JSONObject = JSONObject().apply {
        put("consent", JSONObject().apply {
            put("version", consent.version)
            put("enabled", consent.enabled)
            put("updated_at_utc_millis", consent.updatedAtUtcMillis)
        })
        put("activity_policies", JSONArray().apply {
            activityPolicies.toSortedMap().values.forEach { policy ->
                put(JSONObject().apply {
                    put("activity_id", policy.activityId)
                    put("activity_class", policy.activityClass.name)
                    put("unlocked", policy.unlocked)
                    put("external_link_state", policy.externalLinkState.name)
                })
            }
        })
        put("quota_by_local_date", JSONArray().apply {
            quotaByLocalDate.toSortedMap().forEach { (date, used) ->
                put(JSONObject().apply {
                    put("local_date", date)
                    put("used", used)
                })
            }
        })
        put("receipts", JSONArray().apply { receipts.forEach { put(it.toJson()) } })
    }

    private fun JSONObject.toA1State(): A1PersistentState {
        requireKeys(setOf("consent", "activity_policies", "quota_by_local_date", "receipts"))
        val consentObject = obj("consent").also {
            it.requireKeys(setOf("version", "enabled", "updated_at_utc_millis"))
        }
        val policyItems = array("activity_policies").objects().map { item ->
            item.requireKeys(setOf("activity_id", "activity_class", "unlocked", "external_link_state"))
            val policy = A1ActivityPolicy(
                activityId = item.string("activity_id").also { requireCanonicalUuid(it, "activityId") },
                activityClass = item.enum("activity_class"),
                unlocked = item.boolean("unlocked"),
                externalLinkState = item.enum("external_link_state"),
            )
            policy.activityId to policy
        }
        require(policyItems.map { it.first }.toSet().size == policyItems.size) { "duplicate A1 activity policy" }
        val quotaItems = array("quota_by_local_date").objects().map { item ->
            item.requireKeys(setOf("local_date", "used"))
            val date = item.string("local_date").also(::requireCanonicalDate)
            date to item.int("used")
        }
        require(quotaItems.map { it.first }.toSet().size == quotaItems.size) { "duplicate A1 quota date" }
        val receipts = array("receipts").objects().map { it.toReceipt() }
        return A1PersistentState(
            consent = A1Consent(
                version = consentObject.int("version"),
                enabled = consentObject.boolean("enabled"),
                updatedAtUtcMillis = consentObject.long("updated_at_utc_millis"),
            ),
            activityPolicies = policyItems.toMap(),
            quotaByLocalDate = quotaItems.toMap(),
            receipts = receipts,
        )
    }

    private fun A1RevisionReceipt.toJson(): JSONObject = JSONObject().apply {
        put("proposal_id", proposalId)
        put("revision_id", revisionId)
        put("request_fingerprint", requestFingerprint)
        put("plan_id", planId)
        put("version_before", versionBefore)
        put("version_after", versionAfter)
        put("diff", JSONArray().apply {
            diff.forEach { move ->
                put(JSONObject().apply {
                    put("activity_id", move.activityId)
                    put("from", move.from.toJson())
                    put("to", move.to.toJson())
                })
            }
        })
        put("effect_ids", JSONArray().apply { effectIds.forEach { put(it) } })
        put("scope_hash", scopeHash)
        put("consent_version", consentVersion)
        put("local_date", localDate)
        put("quota_used", quotaUsed)
        put("applied_at_utc_millis", appliedAtUtcMillis)
        put("undo_until_utc_millis", undoUntilUtcMillis)
        put("undo_revision_id", undoRevisionId ?: JSONObject.NULL)
        put("undo_version", undoVersion ?: JSONObject.NULL)
        put("undone_at_utc_millis", undoneAtUtcMillis ?: JSONObject.NULL)
    }

    private fun JSONObject.toReceipt(): A1RevisionReceipt {
        requireKeys(setOf(
            "proposal_id", "revision_id", "request_fingerprint", "plan_id", "version_before", "version_after",
            "diff", "effect_ids", "scope_hash", "consent_version", "local_date", "quota_used",
            "applied_at_utc_millis", "undo_until_utc_millis", "undo_revision_id", "undo_version", "undone_at_utc_millis",
        ))
        return A1RevisionReceipt(
            proposalId = string("proposal_id"),
            revisionId = string("revision_id"),
            requestFingerprint = string("request_fingerprint"),
            planId = string("plan_id"),
            versionBefore = int("version_before"),
            versionAfter = int("version_after"),
            diff = array("diff").objects().map { item ->
                item.requireKeys(setOf("activity_id", "from", "to"))
                A1MoveDiff(
                    activityId = item.string("activity_id"),
                    from = item.obj("from").toSchedule(),
                    to = item.obj("to").toSchedule(),
                )
            },
            effectIds = array("effect_ids").strings(),
            scopeHash = string("scope_hash"),
            consentVersion = int("consent_version"),
            localDate = string("local_date"),
            quotaUsed = int("quota_used"),
            appliedAtUtcMillis = long("applied_at_utc_millis"),
            undoUntilUtcMillis = long("undo_until_utc_millis"),
            undoRevisionId = nullableString("undo_revision_id"),
            undoVersion = nullableInt("undo_version"),
            undoneAtUtcMillis = nullableLong("undone_at_utc_millis"),
        )
    }

    private fun LocalSchedule.toJson(): JSONObject = JSONObject().apply {
        put("local_date", localDate)
        put("start_minute", startMinute)
        put("duration_minutes", durationMinutes)
        put("timezone", timezone)
    }

    private fun JSONObject.toSchedule(): LocalSchedule {
        requireKeys(setOf("local_date", "start_minute", "duration_minutes", "timezone"))
        return LocalSchedule(
            localDate = string("local_date"),
            startMinute = int("start_minute"),
            durationMinutes = int("duration_minutes"),
            timezone = string("timezone"),
        )
    }

    private fun JSONObject.requireKeys(required: Set<String>) {
        val actual = keys().asSequence().toSet()
        require(actual == required) { "invalid V5 JSON fields" }
    }

    private fun JSONObject.string(key: String): String = opt(key) as? String ?: error("$key must be a string")
    private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else string(key)
    private fun JSONObject.boolean(key: String): Boolean = opt(key) as? Boolean ?: error("$key must be a boolean")
    private fun JSONObject.int(key: String): Int = opt(key) as? Int ?: error("$key must be an integer")
    private fun JSONObject.long(key: String): Long = when (val value = opt(key)) {
        is Int -> value.toLong()
        is Long -> value
        else -> error("$key must be an integer")
    }
    private fun JSONObject.nullableInt(key: String): Int? = if (isNull(key)) null else int(key)
    private fun JSONObject.nullableLong(key: String): Long? = if (isNull(key)) null else long(key)
    private fun JSONObject.obj(key: String): JSONObject = opt(key) as? JSONObject ?: error("$key must be an object")
    private fun JSONObject.array(key: String): JSONArray = opt(key) as? JSONArray ?: error("$key must be an array")
    private inline fun <reified T : Enum<T>> JSONObject.enum(key: String): T =
        enumValues<T>().firstOrNull { it.name == string(key) } ?: error("$key has an unknown enum value")
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { index ->
        opt(index) as? JSONObject ?: error("array item must be an object")
    }
    private fun JSONArray.strings(): List<String> = (0 until length()).map { index ->
        opt(index) as? String ?: error("array item must be a string")
    }
}
