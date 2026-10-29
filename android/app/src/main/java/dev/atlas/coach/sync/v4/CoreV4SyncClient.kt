package dev.atlas.coach.sync.v4

import dev.atlas.coach.focus.FocusSession
import dev.atlas.coach.goals.GoalProgress
import dev.atlas.coach.goals.GoalProgressCalculator
import dev.atlas.coach.goals.GoalRecord
import dev.atlas.coach.habits.HabitCheckIn
import dev.atlas.coach.plan.ActivityKind
import dev.atlas.coach.plan.ActivityStatus
import dev.atlas.coach.plan.DayPlan
import dev.atlas.coach.plan.PlanOrigin
import dev.atlas.coach.plan.PlanState
import dev.atlas.coach.plan.PlanStatusEvent
import dev.atlas.coach.plan.Priority
import dev.atlas.coach.plan.StatusProvenance
import dev.atlas.coach.plan.StatusSource
import dev.atlas.coach.plan.TrainingPlanReference
import dev.atlas.coach.plan.TrainingReferenceSyncState
import dev.atlas.coach.usage.GlobalUsageConsent
import dev.atlas.coach.usage.UsageSummary
import dev.atlas.coach.usage.UsageAppSummary
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

enum class CoreV4Category(val wireValue: String, val capability: String, val route: String) {
    DAY_PLAN("DAY_PLAN", "day_plan.snapshot.write", "/v4/sync/day-plan-snapshots"),
    ACTIVITY_STATUS("ACTIVITY_STATUS", "activity_status.write", "/v4/sync/activity-status-events"),
    USAGE_SUMMARY("USAGE_SUMMARY", "usage_summary.write", "/v4/sync/usage-summaries"),
    GOAL_PROGRESS("GOAL_PROGRESS", "goal_progress.write", "/v4/sync/goal-progress-snapshots"),
    TRAINING_PLAN("TRAINING_PLAN", "training_plan.write", "/v4/sync/training-snapshots"),
}

enum class CoreV4ConsentState { GRANTED, REVOKED, UNKNOWN }

data class CoreV4ConsentSnapshot(
    val category: CoreV4Category,
    val version: Int,
    val state: CoreV4ConsentState,
    val syncedVersion: Int = 0,
) {
    init {
        require(version >= 1 && syncedVersion >= 0)
    }
}

data class CoreV4ProjectionRequest(
    val category: CoreV4Category,
    val consentVersion: Int,
    val requestId: String,
    val idempotencyKey: String,
    val sentAt: String,
    val data: JSONObject,
) {
    init {
        require(consentVersion >= 1)
    }
}

data class CoreV4Receipt(
    val category: CoreV4Category,
    val version: Int,
    val resultCode: String,
    val lastSyncedAt: String,
    val snapshotHash: String? = null,
    val replayed: Boolean = false,
)

data class CoreV4ConsentReceipt(
    val category: CoreV4Category,
    val version: Int,
    val state: CoreV4ConsentState,
    val resultCode: String,
    val updatedAt: String,
    val replayed: Boolean,
)

enum class CoreV4FailureKind {
    AUTHENTICATION, CONSENT, CONFLICT, REJECTED, UNAVAILABLE, OFFLINE,
    PROTOCOL, CONFIGURATION, IDEMPOTENCY_CONFLICT,
}

class CoreV4SyncException(
    val kind: CoreV4FailureKind,
    val retryable: Boolean,
    val httpStatus: Int? = null,
    val errorCode: String? = null,
) : IOException(
    when (kind) {
        CoreV4FailureKind.AUTHENTICATION -> "Authentification V4 requise."
        CoreV4FailureKind.CONSENT -> "Le consentement V4 courant ne permet pas cet envoi."
        CoreV4FailureKind.CONFLICT -> "Le serveur a refusé une projection V4 périmée ou conflictuelle."
        CoreV4FailureKind.REJECTED -> "Le serveur a refusé la projection V4."
        CoreV4FailureKind.UNAVAILABLE -> "Le serveur V4 est temporairement indisponible."
        CoreV4FailureKind.OFFLINE -> "Le backend V4 est hors ligne."
        CoreV4FailureKind.PROTOCOL -> "La réponse V4 ne respecte pas le contrat."
        CoreV4FailureKind.CONFIGURATION -> "La configuration du backend V4 n'est pas autorisée."
        CoreV4FailureKind.IDEMPOTENCY_CONFLICT -> "Une clé V4 d'idempotence ne peut pas changer de contenu."
    } + (httpStatus?.let { " HTTP $it." } ?: "") +
        (errorCode?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,64}")) }?.let { " ($it)" } ?: ""),
)

data class CoreV4HttpResponse(val statusCode: Int, val body: String)

data class CoreV4HttpRequest(val path: String, val bearerToken: String, val body: String) {
    override fun toString(): String =
        "CoreV4HttpRequest(path=$path, bearerToken=[redacted], bodyBytes=" +
            body.toByteArray(Charsets.UTF_8).size + ")"
}

interface CoreV4HttpTransport {
    @Throws(IOException::class)
    fun post(request: CoreV4HttpRequest): CoreV4HttpResponse
    fun cancel() = Unit
}

internal fun interface CoreV4CancelableConnection {
    fun cancel()
}

/** Serializes transport cancellation with connection registration. */
internal class CoreV4TransportStartGate {
    private val lock = Any()
    private var cancelled = false
    private var registered: CoreV4CancelableConnection? = null

    fun register(connection: CoreV4CancelableConnection): Boolean = synchronized(lock) {
        if (cancelled) {
            connection.cancel()
            false
        } else {
            registered = connection
            true
        }
    }

    fun mayStart(connection: CoreV4CancelableConnection): Boolean = synchronized(lock) {
        !cancelled && registered === connection
    }

    fun start(connection: CoreV4CancelableConnection, action: () -> Unit): Boolean = synchronized(lock) {
        if (cancelled || registered !== connection) {
            false
        } else {
            action()
            true
        }
    }

    fun complete(connection: CoreV4CancelableConnection) {
        synchronized(lock) {
            if (registered === connection) registered = null
        }
    }

    fun cancel() {
        val connection = synchronized(lock) {
            cancelled = true
            registered.also { registered = null }
        }
        connection?.cancel()
    }
}

/** Fixed V4 routes, bounded HTTP, local schema validation and typed server errors. */
class CoreV4SyncClient(private val transport: CoreV4HttpTransport) {
    private val idempotencyBodies = ConcurrentHashMap<String, String>()

    fun consentBody(
        category: CoreV4Category,
        version: Int,
        state: CoreV4ConsentState,
        requestId: String,
        idempotencyKey: String,
        sentAt: String,
    ): String = JSONObject()
        .put("contract_version", CONTRACT_VERSION)
        .put("schema_version", SCHEMA_VERSION)
        .put("request_id", requestId)
        .put("capability", CONSENT_CAPABILITY)
        .put("sent_at", sentAt)
        .put("category", category.wireValue)
        .put("consent_version", version)
        .put("state", state.name)
        .put("idempotency_key", idempotencyKey)
        .toString()
        .also { validateConsentBody(it) }

    fun projectionBody(request: CoreV4ProjectionRequest): String {
        validateProjectionData(request.category, request.data)
        return JSONObject()
            .put("contract_version", CONTRACT_VERSION)
            .put("schema_version", SCHEMA_VERSION)
            .put("request_id", request.requestId)
            .put("capability", request.category.capability)
            .put("sent_at", request.sentAt)
            .put("consent_version", request.consentVersion)
            .put("idempotency_key", request.idempotencyKey)
            .put("data", request.data)
            .toString()
            .also { validateProjectionBody(request.category, it) }
    }

    fun sendConsent(body: String, bearerToken: String): CoreV4ConsentReceipt {
        val request = validateConsentBody(body)
        val receipt = parseObject(execute(CONSENT_ROUTE, body, bearerToken))
        exactKeys(receipt, setOf(
            "contract_version", "schema_version", "request_id", "capability", "category",
            "consent_version", "state", "updated_at", "source", "replayed", "result_code",
        ))
        requireReceiptEnvelope(receipt, request.getString("request_id"), CONSENT_CAPABILITY)
        if (receipt.optString("category") != request.getString("category") ||
            receipt.optInt("consent_version") != request.getInt("consent_version") ||
            receipt.optString("state") != request.getString("state") ||
            receipt.optString("source") != "ANDROID_SYNC" ||
            receipt.optString("result_code") !in setOf("ACCEPTED", "DUPLICATE") ||
            receipt.get("replayed") !is Boolean
        ) protocolFailure()
        requireUtc(receipt.optString("updated_at"), "updated_at")
        val category = CoreV4Category.entries.first { it.wireValue == receipt.getString("category") }
        return CoreV4ConsentReceipt(
            category = category,
            version = receipt.getInt("consent_version"),
            state = CoreV4ConsentState.valueOf(receipt.getString("state")),
            resultCode = receipt.getString("result_code"),
            updatedAt = receipt.getString("updated_at"),
            replayed = receipt.getBoolean("replayed"),
        )
    }

    fun sendProjection(
        request: CoreV4ProjectionRequest,
        body: String,
        bearerToken: String,
        consent: CoreV4ConsentSnapshot,
        dayPlanConsent: CoreV4ConsentSnapshot? = null,
    ): CoreV4Receipt {
        if (consent.category != request.category || consent.state != CoreV4ConsentState.GRANTED ||
            consent.version != request.consentVersion || consent.syncedVersion != consent.version
        ) throw CoreV4SyncException(CoreV4FailureKind.CONSENT, false)
        if (request.category == CoreV4Category.ACTIVITY_STATUS &&
            (dayPlanConsent?.category != CoreV4Category.DAY_PLAN ||
                dayPlanConsent.state != CoreV4ConsentState.GRANTED ||
                dayPlanConsent.syncedVersion != dayPlanConsent.version)
        ) throw CoreV4SyncException(CoreV4FailureKind.CONSENT, false)
        val envelope = validateProjectionBody(request.category, body)
        if (envelope.optString("request_id") != request.requestId ||
            envelope.optString("idempotency_key") != request.idempotencyKey ||
            envelope.optJSONObject("data")?.toString() != request.data.toString()
        ) protocolFailure()
        val digest = sha256(body)
        val previous = idempotencyBodies.putIfAbsent(request.idempotencyKey, digest)
        if (previous != null && previous != digest) {
            throw CoreV4SyncException(CoreV4FailureKind.IDEMPOTENCY_CONFLICT, false)
        }
        val receipt = parseObject(execute(request.category.route, body, bearerToken))
        requireReceiptEnvelope(receipt, request.requestId, request.category.capability)
        if (request.category == CoreV4Category.ACTIVITY_STATUS) {
            exactKeys(receipt, setOf("contract_version", "schema_version", "request_id", "capability", "items"))
            val items = receipt.optJSONArray("items") ?: protocolFailure()
            if (items.length() != 1) protocolFailure()
            val item = items.getJSONObject(0)
            exactKeys(item, setOf("event_id", "result_code", "last_synced_at"))
            if (item.optString("event_id") != request.data.getString("event_id")) protocolFailure()
            val resultCode = item.optString("result_code")
            if (resultCode in setOf("STALE_EVENT", "REJECTED")) {
                throw CoreV4SyncException(CoreV4FailureKind.CONFLICT, false)
            }
            if (resultCode !in setOf("ACCEPTED", "DUPLICATE")) protocolFailure()
            val syncedAt = item.optString("last_synced_at")
            requireUtc(syncedAt, "last_synced_at")
            return CoreV4Receipt(request.category, request.data.getInt("sequence"), resultCode, syncedAt,
                replayed = resultCode == "DUPLICATE")
        }

        exactKeys(receipt, setOf(
            "contract_version", "schema_version", "request_id", "capability", "accepted",
            "replayed", "stored_version", "snapshot_hash", "last_synced_at", "result_code",
        ))
        val resultCode = receipt.optString("result_code")
        if (resultCode in setOf("STALE_VERSION", "REJECTED")) {
            throw CoreV4SyncException(CoreV4FailureKind.CONFLICT, false)
        }
        val expectedVersion = if (request.category == CoreV4Category.TRAINING_PLAN) {
            request.consentVersion
        } else {
            projectionVersion(request.category, request.data)
        }
        if (resultCode !in setOf("ACCEPTED", "DUPLICATE") ||
            !receipt.optBoolean("accepted") ||
            receipt.optInt("stored_version") != expectedVersion ||
            receipt.get("replayed") !is Boolean ||
            !receipt.optString("snapshot_hash").matches(SNAPSHOT_HASH)
        ) protocolFailure()
        val syncedAt = receipt.optString("last_synced_at")
        requireUtc(syncedAt, "last_synced_at")
        return CoreV4Receipt(request.category, expectedVersion, resultCode, syncedAt,
            receipt.getString("snapshot_hash"), receipt.getBoolean("replayed"))
    }

    fun cancel() = transport.cancel()

    private fun execute(path: String, body: String, token: String): String {
        if (token.isBlank() || token.length > MAX_TOKEN_LENGTH) {
            throw CoreV4SyncException(CoreV4FailureKind.AUTHENTICATION, false, 401)
        }
        if (path !in ALLOWED_PATHS) throw CoreV4SyncException(CoreV4FailureKind.CONFIGURATION, false)
        if (body.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BYTES) protocolFailure()
        val response = try {
            transport.post(CoreV4HttpRequest(path, token, body))
        } catch (error: CoreV4SyncException) {
            throw error
        } catch (_: IOException) {
            throw CoreV4SyncException(CoreV4FailureKind.OFFLINE, true)
        } catch (_: Exception) {
            protocolFailure()
        }
        if (response.body.toByteArray(Charsets.UTF_8).size > MAX_RESPONSE_BYTES) protocolFailure()
        if (response.statusCode !in 200..299) {
            val code = runCatching {
                JSONObject(response.body).optJSONObject("error")?.optString("code")
            }.getOrNull()?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,64}")) }
            val status = response.statusCode
            val kind = when (status) {
                401 -> CoreV4FailureKind.AUTHENTICATION
                403 -> CoreV4FailureKind.CONSENT
                409 -> CoreV4FailureKind.CONFLICT
                422 -> CoreV4FailureKind.REJECTED
                408, 429 -> CoreV4FailureKind.UNAVAILABLE
                in 500..599 -> CoreV4FailureKind.UNAVAILABLE
                else -> CoreV4FailureKind.REJECTED
            }
            throw CoreV4SyncException(kind, status in setOf(408, 429) || status in 500..599, status, code)
        }
        return response.body
    }

    companion object {
        const val CONTRACT_VERSION = 4
        const val SCHEMA_VERSION = 4
        const val CONSENT_CAPABILITY = "consent.state.write"
        const val CONSENT_ROUTE = "/v4/sync/consent-state"
        const val MAX_REQUEST_BYTES = 65_536
        const val MAX_RESPONSE_BYTES = 262_144
        private const val MAX_TOKEN_LENGTH = 4_096
        private val ALLOWED_PATHS = setOf(CONSENT_ROUTE) + CoreV4Category.entries.map { it.route }
        private val SNAPSHOT_HASH = Regex("^sha256:[0-9a-f]{64}$")

        fun validBackendUrl(value: String, debuggable: Boolean): Boolean {
            val uri = runCatching { URI(value) }.getOrNull() ?: return false
            val scheme = uri.scheme?.lowercase() ?: return false
            val host = uri.host?.lowercase() ?: return false
            if (scheme !in setOf("http", "https") || host.isBlank() ||
                uri.userInfo != null || uri.query != null || uri.fragment != null
            ) return false
            return scheme == "https" || (debuggable && host in setOf("10.0.2.2", "127.0.0.1", "localhost"))
        }

        fun requireUuid(value: String, field: String = "uuid") {
            val parsed = runCatching { UUID.fromString(value) }.getOrNull()
            if (parsed == null || parsed.toString() != value) {
                throw CoreV4SyncException(CoreV4FailureKind.PROTOCOL, false, errorCode = "invalid_$field")
            }
        }

        fun isoUtc(epochMillis: Long): String {
            require(epochMillis >= 0L)
            return Instant.ofEpochMilli(epochMillis).toString()
        }

        fun planData(plan: DayPlan, events: List<PlanStatusEvent>, observedAt: String): JSONObject {
            require(plan.state == PlanState.APPROVED) { "Only an approved Android plan can be projected." }
            requireUtc(observedAt, "observed_at")
            val latest = events.filter { it.planId == plan.planId && it.planVersion == plan.version }
                .groupBy { it.activityId }.mapValues { (_, rows) -> rows.maxBy { it.decidedAt } }
            return JSONObject()
                .put("plan_id", plan.planId)
                .put("local_date", plan.localDate)
                .put("timezone", plan.timezone)
                .put("version", plan.version)
                .put("current_revision_id", plan.currentRevisionId)
                .put("state", plan.state.name)
                .put("origin", plan.origin.name)
                .put("observed_at", observedAt)
                .put("activities", JSONArray().also { rows ->
                    plan.activities.forEach { activity ->
                        val event = latest[activity.activityId]?.takeIf { it.toStatus == activity.status }
                        rows.put(JSONObject()
                            .put("activity_id", activity.activityId)
                            .put("kind", activity.kind.name)
                            .put("title", genericTitle(activity.kind))
                            .put("status", activity.status.name)
                            .put("scheduled_local", JSONObject()
                                .put("local_date", activity.scheduledLocal.localDate)
                                .put("start_minute", activity.scheduledLocal.startMinute)
                                .put("duration_minutes", activity.scheduledLocal.durationMinutes)
                                .put("timezone", activity.scheduledLocal.timezone))
                            .put("priority", activity.priority.name)
                            .put("goal_id", activity.goalId ?: JSONObject.NULL)
                            .put("continuity_id", activity.continuityId ?: JSONObject.NULL)
                            .put("supersedes_activity_id", activity.supersedesActivityId ?: JSONObject.NULL)
                            .put("status_provenance", event?.provenance?.name ?: StatusProvenance.UNKNOWN.name)
                            .put("status_decided_at", event?.decidedAt ?: JSONObject.NULL))
                    }
                })
        }

        fun statusData(event: PlanStatusEvent, planVersion: Int, sequence: Long): JSONObject {
            require(sequence in 1..Int.MAX_VALUE.toLong())
            require(event.planVersion == planVersion) { "Stale status events cannot be projected." }
            val source = when (event.source) {
                StatusSource.USER_UI -> "USER_UI"
                StatusSource.FOCUS_TIMER -> "FOCUS_TIMER"
                StatusSource.USAGE_STATS, StatusSource.WGER_READ -> "ANDROID"
                StatusSource.CORE_IMPORT -> "UNKNOWN"
            }
            return JSONObject()
                .put("event_id", event.statusEventId)
                .put("sequence", sequence)
                .put("plan_id", event.planId)
                .put("activity_id", event.activityId)
                .put("continuity_id", event.continuityId ?: JSONObject.NULL)
                .put("plan_version", event.planVersion)
                .put("from_status", event.fromStatus.name)
                .put("to_status", event.toStatus.name)
                .put("provenance", event.provenance.name)
                .put("source", source)
                .put("decided_at", event.decidedAt)
                .put("observed_at", event.observedAt)
                .put("focus_session_id", event.focusSessionId ?: JSONObject.NULL)
        }

        fun usageData(summary: UsageSummary, sourceConsent: GlobalUsageConsent, v4ConsentVersion: Int): JSONObject {
            require(sourceConsent.isExplicit) { "Usage requires current local source consent." }
            require(summary.consentVersion == sourceConsent.consentVersion) { "Usage summary consent is stale." }
            val apps = summary.apps.sortedBy(UsageAppSummary::appRef).take(10).map { app ->
                val appRef = app.appRef.removePrefix("app:")
                if (!appRef.matches(Regex("[a-f0-9]{24}"))) {
                    throw CoreV4SyncException(CoreV4FailureKind.PROTOCOL, false, errorCode = "invalid_app_ref")
                }
                JSONObject()
                    .put("app_ref", appRef)
                    .put("display_name", JSONObject.NULL)
                    .put("foreground_ms", app.foregroundMs)
                    .put("launch_count", app.launchCount)
                    .put("last_used_at", app.lastUsedAtUtc?.let(::isoUtc) ?: JSONObject.NULL)
                    .put("reliability", app.reliability.name)
            }
            return JSONObject()
                .put("window_start", isoUtc(summary.windowStart))
                .put("window_end", isoUtc(summary.windowEnd))
                .put("timezone", summary.timezone)
                .put("total_foreground_ms", summary.totalForegroundMs)
                .put("apps", JSONArray().also { rows -> apps.forEach { rows.put(it) } })
                .put("coverage", JSONObject()
                    .put("observed_ms", summary.coverage.observedMs)
                    .put("blind_ms", summary.coverage.blindMs ?: 0L)
                    .put("events_seen", summary.coverage.eventsSeen)
                    .put("truncated", summary.coverage.truncated))
                .put("generated_at", isoUtc(summary.generatedAt))
                .put("consent_version", v4ConsentVersion)
        }

        fun goalData(
            goals: List<GoalRecord>,
            progress: Map<String, GoalProgress>,
            version: Int,
            observedAt: String,
        ): JSONObject {
            require(version >= 1)
            requireUtc(observedAt, "observed_at")
            val rows = goals.take(20).mapIndexed { index, goal ->
                requireUuid(goal.id, "goal_id")
                JSONObject()
                    .put("goal_id", goal.id)
                    .put("title", "Objectif " + (index + 1))
                    .put("status", goal.status.name)
                    .put("progress_value", progress[goal.id]?.currentValue ?: JSONObject.NULL)
                    .put("unit", goal.metric.wireValue.take(32))
            }
            return JSONObject()
                .put("goals_version", version)
                .put("observed_at", observedAt)
                .put("goals", JSONArray().also { values -> rows.forEach { values.put(it) } })
        }

        fun goalProgress(
            goals: List<GoalRecord>,
            focus: Iterable<FocusSession>,
            checkIns: Iterable<HabitCheckIn>,
        ): Map<String, GoalProgress> = goals.associate { it.id to GoalProgressCalculator.calculate(it, focus, checkIns) }

        fun trainingData(references: List<TrainingPlanReference>, observedAt: String): JSONObject {
            require(references.isNotEmpty()) { "Training projection requires an existing wger reference." }
            requireUtc(observedAt, "observed_at")
            if (references.any { it.syncState == TrainingReferenceSyncState.UNAVAILABLE }) {
                throw CoreV4SyncException(CoreV4FailureKind.UNAVAILABLE, true)
            }
            if (references.any { it.syncState == TrainingReferenceSyncState.MISMATCH }) {
                throw CoreV4SyncException(CoreV4FailureKind.PROTOCOL, false, errorCode = "training_mismatch")
            }
            val rows = references.distinctBy { it.routineId to it.dayId }
                .sortedWith(compareBy<TrainingPlanReference> { it.routineId }.thenBy { it.dayId ?: 0 })
                .take(20)
            val state = when {
                rows.any { it.syncState == TrainingReferenceSyncState.MISMATCH } -> "MISMATCH"
                rows.any { it.syncState == TrainingReferenceSyncState.UNAVAILABLE } -> "UNAVAILABLE"
                rows.any { it.syncState == TrainingReferenceSyncState.STALE } -> "STALE"
                rows.all { it.syncState == TrainingReferenceSyncState.FRESH } -> "FRESH"
                else -> "UNKNOWN"
            }
            return JSONObject()
                .put("provider", "wger")
                .put("source", "WGER_API")
                .put("sync_state", state)
                .put("observed_at", observedAt)
                .put("source_updated_at", rows.mapNotNull { it.sourceUpdatedAt }.maxOrNull() ?: JSONObject.NULL)
                .put("routines", JSONArray().also { values ->
                    rows.forEach { ref ->
                        if (ref.routineId !in 1..1_000_000_000 ||
                            ref.dayId?.let { it !in 1..1_000_000_000 } == true
                        ) throw CoreV4SyncException(CoreV4FailureKind.PROTOCOL, false, errorCode = "invalid_training_id")
                        values.put(JSONObject().put("routine_id", ref.routineId).put("day_id", ref.dayId ?: JSONObject.NULL))
                    }
                })
                .put("sessions", JSONArray())
                .put("results", JSONArray())
        }

        private fun genericTitle(kind: ActivityKind): String = when (kind) {
            ActivityKind.FOCUS -> "Focus"
            ActivityKind.TRAINING -> "Entraînement"
            ActivityKind.APPOINTMENT -> "Rendez-vous"
            ActivityKind.ADMIN -> "Administration"
            ActivityKind.MEAL -> "Repas"
            ActivityKind.REST -> "Repos"
            ActivityKind.BUFFER -> "Marge"
            ActivityKind.OTHER -> "Activité"
        }

        internal fun validateConsentBody(body: String): JSONObject {
            val json = parseObject(body)
            exactKeys(json, setOf(
                "contract_version", "schema_version", "request_id", "capability", "sent_at",
                "category", "consent_version", "state", "idempotency_key",
            ))
            validateEnvelope(json, CONSENT_CAPABILITY)
            if (CoreV4Category.entries.none { it.wireValue == json.optString("category") } ||
                json.optInt("consent_version") < 1 ||
                CoreV4ConsentState.entries.none { it.name == json.optString("state") }
            ) protocolFailure()
            return json
        }

        internal fun validateProjectionBody(category: CoreV4Category, body: String): JSONObject {
            val json = parseObject(body)
            exactKeys(json, setOf(
                "contract_version", "schema_version", "request_id", "capability", "sent_at",
                "consent_version", "idempotency_key", "data",
            ))
            validateEnvelope(json, category.capability)
            if (json.optInt("consent_version") < 1) protocolFailure()
            validateProjectionData(category, json.optJSONObject("data") ?: protocolFailure())
            return json
        }

        internal fun validateProjectionData(category: CoreV4Category, data: JSONObject) {
            when (category) {
                CoreV4Category.DAY_PLAN -> validatePlan(data)
                CoreV4Category.ACTIVITY_STATUS -> validateStatus(data)
                CoreV4Category.USAGE_SUMMARY -> validateUsage(data)
                CoreV4Category.GOAL_PROGRESS -> validateGoals(data)
                CoreV4Category.TRAINING_PLAN -> validateTraining(data)
            }
        }

        private fun validatePlan(data: JSONObject) {
            exactKeys(data, setOf(
                "plan_id", "local_date", "timezone", "version", "current_revision_id", "state",
                "origin", "observed_at", "activities",
            ))
            requireUuid(data.optString("plan_id"), "plan_id")
            requireUuid(data.optString("current_revision_id"), "current_revision_id")
            requireDate(data.optString("local_date"))
            requireTimezone(data.optString("timezone"))
            if (data.optInt("version") < 1 || data.optString("state") !in setOf("DRAFT", "APPROVED") ||
                data.optString("origin") !in PlanOrigin.entries.map { it.name }
            ) protocolFailure()
            requireUtc(data.optString("observed_at"), "observed_at")
            val activities = data.optJSONArray("activities") ?: protocolFailure()
            if (activities.length() > 32) protocolFailure()
            for (i in 0 until activities.length()) {
                val row = activities.getJSONObject(i)
                exactKeys(row, setOf(
                    "activity_id", "kind", "title", "status", "scheduled_local", "priority",
                    "goal_id", "continuity_id", "supersedes_activity_id", "status_provenance", "status_decided_at",
                ))
                requireUuid(row.optString("activity_id"), "activity_id")
                if (row.optString("kind") !in ActivityKind.entries.map { it.name } ||
                    row.optString("status") !in ActivityStatus.entries.map { it.name } ||
                    row.optString("priority") !in Priority.entries.map { it.name } ||
                    row.optString("status_provenance") !in StatusProvenance.entries.map { it.name } ||
                    row.optString("title").let { it.isBlank() || it.length > 80 }
                ) protocolFailure()
                listOf("goal_id", "continuity_id", "supersedes_activity_id")
                    .forEach { key -> if (!row.isNull(key)) requireUuid(row.optString(key), key) }
                if (!row.isNull("status_decided_at")) requireUtc(row.optString("status_decided_at"), "status_decided_at")
                val slot = row.optJSONObject("scheduled_local") ?: protocolFailure()
                exactKeys(slot, setOf("local_date", "start_minute", "duration_minutes", "timezone"))
                requireDate(slot.optString("local_date"))
                if (slot.optInt("start_minute", -1) !in 0..1439 ||
                    slot.optInt("duration_minutes", -1) !in 1..1440
                ) protocolFailure()
                requireTimezone(slot.optString("timezone"))
            }
        }

        private fun validateStatus(data: JSONObject) {
            exactKeys(data, setOf(
                "event_id", "sequence", "plan_id", "activity_id", "continuity_id", "plan_version",
                "from_status", "to_status", "provenance", "source", "decided_at", "observed_at", "focus_session_id",
            ))
            listOf("event_id", "plan_id", "activity_id").forEach { requireUuid(data.optString(it), it) }
            listOf("continuity_id", "focus_session_id")
                .forEach { key -> if (!data.isNull(key)) requireUuid(data.optString(key), key) }
            if (data.optLong("sequence") < 1 || data.optInt("plan_version") < 1 ||
                data.optString("from_status") !in ActivityStatus.entries.map { it.name } ||
                data.optString("to_status") !in ActivityStatus.entries.map { it.name } ||
                data.optString("provenance") !in StatusProvenance.entries.map { it.name } ||
                data.optString("source") !in setOf("USER_UI", "FOCUS_TIMER", "ANDROID", "UNKNOWN")
            ) protocolFailure()
            requireUtc(data.optString("decided_at"), "decided_at")
            requireUtc(data.optString("observed_at"), "observed_at")
        }

        private fun validateUsage(data: JSONObject) {
            exactKeys(data, setOf(
                "window_start", "window_end", "timezone", "total_foreground_ms", "apps",
                "coverage", "generated_at", "consent_version",
            ))
            if (!requireUtc(data.optString("window_end"), "window_end")
                    .isAfter(requireUtc(data.optString("window_start"), "window_start"))
            ) protocolFailure()
            requireTimezone(data.optString("timezone"))
            boundedInt(data, "total_foreground_ms", 0, 1_209_600_000)
            if (data.optInt("consent_version") < 1) protocolFailure()
            requireUtc(data.optString("generated_at"), "generated_at")
            val apps = data.optJSONArray("apps") ?: protocolFailure()
            if (apps.length() > 10) protocolFailure()
            for (i in 0 until apps.length()) {
                val row = apps.getJSONObject(i)
                exactKeys(row, setOf("app_ref", "display_name", "foreground_ms", "launch_count", "last_used_at", "reliability"))
                if (row.optString("app_ref").let { it.isBlank() || it.length > 80 || !it.matches(Regex("[A-Za-z0-9_-]+")) } ||
                    (!row.isNull("display_name") && row.optString("display_name").length !in 1..80) ||
                    row.optString("reliability") !in setOf("HIGH", "MEDIUM", "LOW")
                ) protocolFailure()
                boundedInt(row, "foreground_ms", 0, 1_209_600_000)
                boundedInt(row, "launch_count", 0, 100_000)
                if (!row.isNull("last_used_at")) requireUtc(row.optString("last_used_at"), "last_used_at")
            }
            val coverage = data.optJSONObject("coverage") ?: protocolFailure()
            exactKeys(coverage, setOf("observed_ms", "blind_ms", "events_seen", "truncated"))
            boundedInt(coverage, "observed_ms", 0, 1_209_600_000)
            boundedInt(coverage, "blind_ms", 0, 1_209_600_000)
            boundedInt(coverage, "events_seen", 0, 1_000_000)
            if (coverage.opt("truncated") !is Boolean) protocolFailure()
        }

        private fun validateGoals(data: JSONObject) {
            exactKeys(data, setOf("goals_version", "observed_at", "goals"))
            if (data.optInt("goals_version") < 1) protocolFailure()
            requireUtc(data.optString("observed_at"), "observed_at")
            val goals = data.optJSONArray("goals") ?: protocolFailure()
            if (goals.length() > 20) protocolFailure()
            for (i in 0 until goals.length()) {
                val row = goals.getJSONObject(i)
                exactKeys(row, setOf("goal_id", "title", "status", "progress_value", "unit"))
                requireUuid(row.optString("goal_id"), "goal_id")
                if (row.optString("title").let { it.isBlank() || it.length > 80 } ||
                    row.optString("status") !in setOf("ACTIVE", "PAUSED", "COMPLETED", "ABANDONED") ||
                    (!row.isNull("progress_value") && row.get("progress_value") !is Number) ||
                    (!row.isNull("unit") && row.optString("unit").let { it.isBlank() || it.length > 32 })
                ) protocolFailure()
            }
        }

        private fun validateTraining(data: JSONObject) {
            exactKeys(data, setOf(
                "provider", "source", "sync_state", "observed_at", "source_updated_at",
                "routines", "sessions", "results",
            ))
            if (data.optString("provider") != "wger" ||
                data.optString("source") !in setOf("WGER_API", "ANDROID_SYNC") ||
                data.optString("sync_state") !in setOf("FRESH", "STALE", "UNAVAILABLE", "MISMATCH", "UNKNOWN")
            ) protocolFailure()
            requireUtc(data.optString("observed_at"), "observed_at")
            if (!data.isNull("source_updated_at")) requireUtc(data.optString("source_updated_at"), "source_updated_at")
            val routines = data.optJSONArray("routines") ?: protocolFailure()
            val sessions = data.optJSONArray("sessions") ?: protocolFailure()
            val results = data.optJSONArray("results") ?: protocolFailure()
            if (routines.length() > 20 || sessions.length() > 20 || results.length() > 50) protocolFailure()
            for (i in 0 until routines.length()) {
                val row = routines.getJSONObject(i)
                exactKeys(row, setOf("routine_id", "day_id"))
                if (row.optInt("routine_id") !in 1..1_000_000_000 ||
                    (!row.isNull("day_id") && row.optInt("day_id") !in 1..1_000_000_000)
                ) protocolFailure()
            }
            for (i in 0 until sessions.length()) {
                val row = sessions.getJSONObject(i)
                exactKeys(row, setOf("session_id", "scheduled_date", "title"))
                if (row.optString("session_id").let { it.isBlank() || it.length > 128 } ||
                    row.optString("title").let { it.isBlank() || it.length > 80 }
                ) protocolFailure()
                if (!row.isNull("scheduled_date")) requireDate(row.optString("scheduled_date"))
            }
            for (i in 0 until results.length()) {
                val row = results.getJSONObject(i)
                exactKeys(row, setOf("result_id", "performed_at", "source", "verification"))
                requireUuid(row.optString("result_id"), "result_id")
                requireUtc(row.optString("performed_at"), "performed_at")
                if (row.optString("source") !in setOf("WGER_API", "USER_DECLARED", "IMPORTED") ||
                    row.optString("verification") !in setOf("API_READ_BACK", "USER_CONFIRMED", "UNVERIFIED")
                ) protocolFailure()
            }
        }

        private fun validateEnvelope(json: JSONObject, capability: String) {
            if (json.optInt("contract_version") != CONTRACT_VERSION ||
                json.optInt("schema_version") != SCHEMA_VERSION ||
                json.optString("capability") != capability
            ) protocolFailure()
            requireUuid(json.optString("request_id"), "request_id")
            requireUuid(json.optString("idempotency_key"), "idempotency_key")
            requireUtc(json.optString("sent_at"), "sent_at")
        }

        private fun requireReceiptEnvelope(receipt: JSONObject, requestId: String, capability: String) {
            if (receipt.optInt("contract_version") != CONTRACT_VERSION ||
                receipt.optInt("schema_version") != SCHEMA_VERSION ||
                receipt.optString("request_id") != requestId ||
                receipt.optString("capability") != capability
            ) protocolFailure()
        }

        private fun projectionVersion(category: CoreV4Category, data: JSONObject): Int =
            when (category) {
                CoreV4Category.DAY_PLAN -> data.optInt("version")
                CoreV4Category.ACTIVITY_STATUS -> data.optInt("sequence")
                CoreV4Category.USAGE_SUMMARY -> data.optInt("consent_version")
                CoreV4Category.GOAL_PROGRESS -> data.optInt("goals_version")
                CoreV4Category.TRAINING_PLAN -> 1
            }.takeIf { it >= 1 } ?: protocolFailure()

        private fun exactKeys(json: JSONObject, expected: Set<String>) {
            if (json.keys().asSequence().toSet() != expected) protocolFailure()
        }

        private fun boundedInt(json: JSONObject, key: String, min: Long, max: Long) {
            val value = json.opt(key)
            if (value !is Number || value.toDouble() % 1.0 != 0.0 || value.toLong() !in min..max) protocolFailure()
        }

        private fun requireDate(value: String) {
            if (value.length != 10 || runCatching { LocalDate.parse(value) }.isFailure) protocolFailure()
        }

        private fun requireTimezone(value: String) {
            if (value.length !in 1..64 ||
                !value.matches(Regex("^(UTC|[A-Za-z_+-]+(?:/[A-Za-z0-9_+.-]+)+)$")) ||
                runCatching { ZoneId.of(value) }.isFailure
            ) protocolFailure()
        }

        private fun requireUtc(value: String, field: String): Instant {
            if (value.length !in 20..40 || !value.endsWith("Z")) protocolFailure()
            return runCatching { Instant.parse(value) }.getOrElse {
                throw CoreV4SyncException(CoreV4FailureKind.PROTOCOL, false, errorCode = "invalid_$field")
            }
        }

        private fun parseObject(body: String): JSONObject =
            runCatching { JSONObject(body) }.getOrElse { protocolFailure() }

        private fun protocolFailure(): Nothing =
            throw CoreV4SyncException(CoreV4FailureKind.PROTOCOL, false)

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

/** Uses the configured backend origin, fixed paths, bounded timeouts and no redirects. */
class CoreV4UrlConnectionTransport(
    baseUrl: String,
    debuggable: Boolean,
    private val connectTimeoutMillis: Int = 4_000,
    private val readTimeoutMillis: Int = 4_000,
    private val requestAllowed: (CoreV4HttpRequest) -> Boolean = { true },
    private val requestStart: ((CoreV4HttpRequest, () -> Unit) -> Boolean)? = null,
) : CoreV4HttpTransport {
    private val base: String
    private val startGate = CoreV4TransportStartGate()

    init {
        if (!CoreV4SyncClient.validBackendUrl(baseUrl, debuggable) ||
            connectTimeoutMillis !in 1..10_000 || readTimeoutMillis !in 1..10_000
        ) throw CoreV4SyncException(CoreV4FailureKind.CONFIGURATION, false)
        base = baseUrl.trimEnd('/')
    }

    override fun post(request: CoreV4HttpRequest): CoreV4HttpResponse {
        if (!requestAllowed(request)) throw CoreV4SyncException(CoreV4FailureKind.CONSENT, false)
        val bytes = request.body.toByteArray(Charsets.UTF_8)
        if (bytes.size > CoreV4SyncClient.MAX_REQUEST_BYTES) {
            throw CoreV4SyncException(CoreV4FailureKind.PROTOCOL, false)
        }
        val connection = URL(base + request.path).openConnection() as HttpURLConnection
        val registration = CoreV4CancelableConnection { connection.disconnect() }
        if (!startGate.register(registration)) {
            throw CoreV4SyncException(CoreV4FailureKind.CONSENT, false)
        }
        try {
            if (!startGate.mayStart(registration) || !requestAllowed(request)) {
                throw CoreV4SyncException(CoreV4FailureKind.CONSENT, false)
            }
            connection.instanceFollowRedirects = false
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.setRequestProperty("Authorization", "Bearer " + request.bearerToken)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.doOutput = true
            var outputStream: java.io.OutputStream? = null
            val started = startGate.start(registration) {
                val startConnection = { outputStream = connection.outputStream }
                val authorized = requestStart?.invoke(request, startConnection) ?: run {
                    if (requestAllowed(request)) {
                        startConnection()
                        true
                    } else {
                        false
                    }
                }
                if (!authorized) throw CoreV4SyncException(CoreV4FailureKind.CONSENT, false)
            }
            if (!started) throw CoreV4SyncException(CoreV4FailureKind.CONSENT, false)
            checkNotNull(outputStream).use { output -> output.write(bytes) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { input ->
                val result = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8_192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > CoreV4SyncClient.MAX_RESPONSE_BYTES) {
                        throw CoreV4SyncException(CoreV4FailureKind.PROTOCOL, false)
                    }
                    result.write(buffer, 0, count)
                }
                result.toString(Charsets.UTF_8.name())
            }.orEmpty()
            return CoreV4HttpResponse(status, text)
        } finally {
            startGate.complete(registration)
            connection.disconnect()
        }
    }

    override fun cancel() {
        startGate.cancel()
    }
}
