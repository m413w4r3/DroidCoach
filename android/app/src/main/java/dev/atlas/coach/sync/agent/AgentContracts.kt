package dev.atlas.coach.sync.agent

import dev.atlas.coach.sync.OutboxStorage
import dev.atlas.coach.content.ContentUrlValidator
import dev.atlas.coach.rules.SafetyDenylist
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.math.BigDecimal
import java.util.UUID

/** A future provider can return proposals only; this module has no Android action executor. */
interface CoachDecisionProvider {
    fun propose(context: String, mandate: AgentMandate): ProposedAction
}

interface AgentAdapter : CoachDecisionProvider

enum class ProposedActionType(val wireValue: String) {
    FOCUS_SESSION("focus_session"),
    SUGGEST_BREAK("suggest_break"),
    OPEN_CONTENT("open_content"),
}

data class ProposedAction(
    val type: String,
    val parameters: Map<String, String>,
    val justification: String,
)

enum class AgentMode(val wireValue: String) {
    PROPOSALS_ONLY("proposals_only"), PREAUTHORIZED("preauthorized"),
}

enum class AgentActionType(val wireValue: String) {
    HOME_SET_DIRECTIVE("home.set_directive"), HOME_REORDER_CARDS("home.reorder_cards"),
    FOCUS_PROPOSE("focus.propose"), FOCUS_PLAN("focus.plan"),
    FOCUS_START("focus.start"), FOCUS_PAUSE("focus.pause"),
    HABIT_SUGGEST("habit.suggest"), APP_POLICY_PROPOSE("app_policy.propose"),
    APP_POLICY_ADJUST("app_policy.adjust"), CONTENT_RECOMMEND("content.recommend");

    companion object {
        fun parse(value: String): AgentActionType? = entries.firstOrNull { it.wireValue == value }
    }
}

enum class ProposalLevel(val wireValue: String) {
    PROPOSAL_ONLY("proposal_only"), CONFIRM("confirm"), PREAUTHORIZED_OK("preauthorized_ok");
}

enum class ProposalState(val wireValue: String) {
    PROPOSED("proposed"), PENDING_CONFIRMATION("pending_confirmation"), ACCEPTED("accepted"),
    EXECUTED("executed"), REJECTED("rejected"), EXPIRED("expired"), CANCELLED("cancelled");
    val terminal: Boolean get() = this in setOf(EXECUTED, REJECTED, EXPIRED, CANCELLED)

    companion object {
        fun parse(value: String): ProposalState? = entries.firstOrNull { it.wireValue == value }
    }
}

data class AgentFocusWindow(
    val days: Set<Int> = (1..7).toSet(),
    val start: String = "08:00",
    val end: String = "20:00",
    val timezone: String = ZoneId.systemDefault().id,
    val maxMinutesPerSession: Int = 90,
    val maxSessionsPerDay: Int = 4,
) {
    init {
        require(days.isNotEmpty() && days.all { it in 1..7 })
        LocalTime.parse(start); LocalTime.parse(end); ZoneId.of(timezone)
        require(maxMinutesPerSession in 1..180 && maxSessionsPerDay in 1..24)
    }
}

data class AgentAppPolicyBounds(
    val minLimitMinutes: Int,
    val maxMode: String,
    val maxValidDays: Int,
) {
    init {
        require(minLimitMinutes in 5..480)
        require(maxMode in setOf("soft_reminder", "friction"))
        require(maxValidDays in 1..14)
    }
}

/** Mandate v2 is the only authorization source. The secondary constructor migrates V1 callers. */
data class AgentMandate(
    val id: String,
    val version: Long = 1L,
    val revoked: Boolean = false,
    val suspended: Boolean = false,
    val mode: AgentMode = AgentMode.PROPOSALS_ONLY,
    val allowedActions: Set<String> = emptySet(),
    val focusWindow: AgentFocusWindow = AgentFocusWindow(),
    val appPolicyBounds: Map<String, AgentAppPolicyBounds> = emptyMap(),
    val maxChangesPerDay: Int = 20,
    val sharedDataCategories: Set<String> = emptySet(),
    val expiresAtUtcMillis: Long = DEFAULT_MANDATE_EXPIRY_UTC_MILLIS,
) {
    constructor(
        id: String,
        allowedActionTypes: Set<String>,
        revoked: Boolean = false,
        version: Long = 1L,
    ) : this(
        id = id,
        version = version,
        revoked = revoked,
        mode = AgentMode.PROPOSALS_ONLY,
        allowedActions = allowedActionTypes.mapNotNull(::legacyActionName).toSet(),
    )

    val allowedActionTypes: Set<String> get() = allowedActions

    init {
        require(id.isNotBlank() && id.length <= 120)
        require(version > 0L && maxChangesPerDay in 1..500)
        require(allowedActions.all { AgentActionType.parse(it) != null })
        require(appPolicyBounds.keys.none(String::isBlank))
        require(sharedDataCategories.all { it in ALLOWED_SHARED_CATEGORIES })
    }

    fun toJson(): JSONObject = JSONObject()
        .put("schema_version", 2)
        .put("id", id)
        .put("version", version)
        .put("revoked", revoked)
        .put("suspended", suspended)
        .put("mode", mode.wireValue)
        .put("allowed_actions", JSONArray(allowedActions.sorted()))
        .put("focus_window", focusWindow.toJson())
        .put("app_policy_bounds", JSONObject().also { root ->
            appPolicyBounds.toSortedMap().forEach { (key, value) -> root.put(key, value.toJson()) }
        })
        .put("max_changes_per_day", maxChangesPerDay)
        .put("shared_data_categories", JSONArray(sharedDataCategories.sorted()))
        .put("expires_at", isoUtc(expiresAtUtcMillis))

    companion object {
        val ALL_ACTIONS: Set<String> = AgentActionType.entries.mapTo(linkedSetOf(), AgentActionType::wireValue)
        const val DEFAULT_MANDATE_EXPIRY_UTC_MILLIS = 4_102_444_800_000L // 2100-01-01T00:00:00Z
        val ALLOWED_SHARED_CATEGORIES = setOf(
            "focus", "habit", "content", "coach_interaction", "app_usage_summary",
            "goals", "location_summary",
        )

        fun default(id: String = "agent-local-v2"): AgentMandate = AgentMandate(
            id = id,
            allowedActions = ALL_ACTIONS,
            mode = AgentMode.PROPOSALS_ONLY,
            sharedDataCategories = emptySet(),
        )

        /** Strict v2 decode plus explicit V1 migration to proposals_only. */
        fun fromJson(raw: String): AgentMandate {
            val root = JSONObject(raw)
            val rawVersion = root.opt("schema_version")
            val version = if (rawVersion == null) 1 else {
                val parsed = (rawVersion as? Number)?.let(::exactLong)
                    ?: throw IllegalArgumentException("schema_version must be an integer")
                require(parsed in 1..Int.MAX_VALUE.toLong())
                parsed.toInt()
            }
            if (version == 1) return fromV1(root)
            require(version == 2) { "Unsupported mandate schema" }
            root.requireKeys(setOf(
                "schema_version", "id", "version", "revoked", "suspended", "mode", "allowed_actions",
                "focus_window", "app_policy_bounds", "max_changes_per_day", "shared_data_categories", "expires_at",
            ))
            val window = root.getJSONObject("focus_window")
            window.requireKeys(setOf("days", "start", "end", "timezone", "max_minutes_per_session", "max_sessions_per_day"))
            val bounds = root.getJSONObject("app_policy_bounds")
            val parsedBounds = buildMap {
                val keys = bounds.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val item = bounds.getJSONObject(key)
                    item.requireKeys(setOf("min_limit_minutes", "max_mode", "max_valid_days"))
                    put(key, AgentAppPolicyBounds(
                        item.requiredInt("min_limit_minutes"), item.requiredString("max_mode"), item.requiredInt("max_valid_days"),
                    ))
                }
            }
            val daysJson = window.getJSONArray("days")
            val daysList = (0 until daysJson.length()).map {
                val day = daysJson.get(it) as? Number ?: throw IllegalArgumentException("Invalid weekday")
                exactLong(day)?.also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }?.toInt()
                    ?: throw IllegalArgumentException("Invalid weekday")
            }
            require(daysList.distinct().size == daysList.size) { "Duplicate weekday" }
            val actions = root.getJSONArray("allowed_actions").toStringList()
            val categories = root.getJSONArray("shared_data_categories").toStringList()
            require(actions.distinct().size == actions.size && categories.distinct().size == categories.size)
            return AgentMandate(
                id = root.requiredString("id"),
                version = root.requiredLong("version"),
                revoked = root.requiredBoolean("revoked"),
                suspended = root.requiredBoolean("suspended"),
                mode = AgentMode.entries.firstOrNull { it.wireValue == root.requiredString("mode") }
                    ?: error("Invalid mandate mode"),
                allowedActions = actions.toSet(),
                focusWindow = AgentFocusWindow(
                    daysList.toSet(), window.requiredString("start"), window.requiredString("end"), window.requiredString("timezone"),
                    window.requiredInt("max_minutes_per_session"), window.requiredInt("max_sessions_per_day"),
                ),
                appPolicyBounds = parsedBounds,
                maxChangesPerDay = root.requiredInt("max_changes_per_day"),
                sharedDataCategories = categories.toSet(),
                expiresAtUtcMillis = parseUtc(root.requiredString("expires_at")),
            )
        }

        private fun fromV1(root: JSONObject): AgentMandate {
            val id = root.optString("id", "legacy-v1")
            val allowed = root.optJSONArray("allowed_actions")?.toStringList()
                ?: root.optJSONArray("allowedActionTypes")?.toStringList().orEmpty()
            val revoked = root.optBoolean("revoked", false)
            val version = root.optLong("version", 1L).coerceAtLeast(1L)
            return AgentMandate(
                id = id,
                version = version,
                revoked = revoked,
                mode = AgentMode.PROPOSALS_ONLY,
                allowedActions = allowed.mapNotNull(::legacyActionName).toSet(),
            )
        }
    }
}

private fun legacyActionName(value: String): String? = when (value) {
    "focus_session" -> AgentActionType.FOCUS_PROPOSE.wireValue
    "suggest_break" -> AgentActionType.FOCUS_PROPOSE.wireValue
    "open_content" -> AgentActionType.CONTENT_RECOMMEND.wireValue
    else -> AgentActionType.parse(value)?.wireValue
}

private fun AgentFocusWindow.toJson() = JSONObject()
    .put("days", JSONArray(days.sorted()))
    .put("start", start).put("end", end).put("timezone", timezone)
    .put("max_minutes_per_session", maxMinutesPerSession)
    .put("max_sessions_per_day", maxSessionsPerDay)

private fun AgentAppPolicyBounds.toJson() = JSONObject()
    .put("min_limit_minutes", minLimitMinutes).put("max_mode", maxMode).put("max_valid_days", maxValidDays)

data class ProposalV2(
    val schemaVersion: Int,
    val proposalId: String,
    val idempotencyKey: String,
    val conversationId: String?,
    val mandateId: String,
    val mandateVersion: Long,
    val contextVersion: String,
    val createdAtUtcMillis: Long,
    val expiresAtUtcMillis: Long,
    val actionType: String,
    val parameters: Map<String, Any?>,
    val reason: String,
    val requiredLevel: ProposalLevel,
    val state: ProposalState = ProposalState.PROPOSED,
) {
    init {
        require(schemaVersion == 2)
        require(UUID.fromString(proposalId).toString() == proposalId)
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= 80)
        require(conversationId == null || (conversationId.isNotBlank() && conversationId.length <= 120))
        require(mandateId.isNotBlank() && mandateVersion > 0L)
        require(contextVersion.isNotBlank() && contextVersion.length <= 120)
        require(createdAtUtcMillis >= 0L && expiresAtUtcMillis > createdAtUtcMillis)
        require(expiresAtUtcMillis - createdAtUtcMillis <= MAX_TTL_MILLIS)
        require(AgentActionType.parse(actionType) != null)
        require(reason.isNotBlank() && reason.length <= 240)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("schema_version", schemaVersion)
        .put("proposal_id", proposalId)
        .put("idempotency_key", idempotencyKey)
        .put("conversation_id", conversationId ?: JSONObject.NULL)
        .put("mandate_id", mandateId)
        .put("mandate_version", mandateVersion)
        .put("context_version", contextVersion)
        .put("created_at", isoUtc(createdAtUtcMillis))
        .put("expires_at", isoUtc(expiresAtUtcMillis))
        .put("action_type", actionType)
        .put("parameters", JSONObject(parameters))
        .put("reason", reason)
        .put("required_level", requiredLevel.wireValue)
        .put("state", state.wireValue)

    fun withState(next: ProposalState) = copy(state = next)

    companion object {
        const val MAX_TTL_MILLIS = 24L * 60L * 60L * 1_000L

        fun fromJson(root: JSONObject): ProposalV2 {
            root.requireKeys(setOf(
                "schema_version", "proposal_id", "idempotency_key", "conversation_id", "mandate_id",
                "mandate_version", "context_version", "created_at", "expires_at", "action_type",
                "parameters", "reason", "required_level", "state",
            ))
            val action = root.requiredString("action_type")
            require(AgentActionType.parse(action) != null) { "Unknown action" }
            val level = ProposalLevel.entries.firstOrNull { it.wireValue == root.requiredString("required_level") }
                ?: error("Invalid required_level")
            val state = ProposalState.parse(root.requiredString("state")) ?: error("Invalid proposal state")
            val params = root.getJSONObject("parameters").toScalarValueMap()
            require(params.values.none(::containsNullValue)) { "Null action parameters are not allowed" }
            validateParameterShape(action, params)
            val rawConversation = root.get("conversation_id")
            val conversation = if (rawConversation == JSONObject.NULL) null else rawConversation as? String
                ?: error("conversation_id must be a string or null")
            return ProposalV2(
                schemaVersion = root.requiredInt("schema_version"),
                proposalId = root.requiredString("proposal_id"),
                idempotencyKey = root.requiredString("idempotency_key"),
                conversationId = conversation,
                mandateId = root.requiredString("mandate_id"),
                mandateVersion = root.requiredLong("mandate_version"),
                contextVersion = root.requiredString("context_version"),
                createdAtUtcMillis = parseUtc(root.requiredString("created_at")),
                expiresAtUtcMillis = parseUtc(root.requiredString("expires_at")),
                actionType = action,
                parameters = params,
                reason = root.requiredString("reason"),
                requiredLevel = level,
                state = state,
            )
        }

        fun parse(raw: String): ProposalV2 = fromJson(JSONObject(raw))
    }
}

private fun validateParameterShape(action: String, params: Map<String, Any?>) {
    val allowed = when (action) {
        "home.set_directive" -> setOf("title", "body", "primary_action", "focus_proposal_id")
        "home.reorder_cards" -> setOf("order")
        "focus.propose" -> setOf("focus_minutes", "break_minutes", "cycles", "label")
        "focus.plan" -> setOf("focus_minutes", "break_minutes", "cycles", "label", "start_at", "timezone")
        "focus.start", "focus.pause" -> setOf("session_ref")
        "habit.suggest" -> setOf("name", "schedule")
        "app_policy.propose", "app_policy.adjust" -> setOf("package_ref", "daily_limit_minutes", "window", "mode", "valid_until")
        "content.recommend" -> setOf("content_id", "url", "title")
        else -> throw IllegalArgumentException("Unknown action")
    }
    require(params.keys == allowedForPayload(action, params, allowed)) { "Action parameters do not match the contract" }
    fun text(key: String, max: Int, required: Boolean = true) {
        val value = params[key]
        if (!required && value == null) return
        require(value is String && value.isNotBlank() && value.length <= max) { "Invalid $key" }
    }
    fun int(key: String, range: IntRange) {
        val value = params[key] as? Number
        val parsed = value?.let(::exactLong)
        require(parsed != null && parsed >= range.first.toLong() && parsed <= range.last.toLong()) {
            "Invalid $key: value=$value class=${value?.javaClass?.name} parsed=$parsed"
        }
    }
    when (action) {
        "home.set_directive" -> {
            text("title", 60); text("body", 200)
            require(params["primary_action"] in setOf("focus.start", "open_coach", "none"))
            text("focus_proposal_id", 80, required = false)
        }
        "home.reorder_cards" -> {
            val order = params["order"] as? List<*> ?: error("order must be an array")
            require(order.isNotEmpty() && order.size <= 3 && order.distinct().size == order.size)
            require(order.all { it in setOf("directive", "focus", "content") })
        }
        "focus.propose", "focus.plan" -> {
            int("focus_minutes", 1..180); int("break_minutes", 0..60); int("cycles", 1..8)
            text("label", 60, required = false)
            if (action == "focus.plan") { text("start_at", 40); text("timezone", 80); parseUtc(params.getValue("start_at") as String); ZoneId.of(params.getValue("timezone") as String) }
        }
        "focus.start", "focus.pause" -> text("session_ref", 80)
        "habit.suggest" -> { text("name", 60); require(params["schedule"] in setOf("daily", "weekdays")) }
        "app_policy.propose", "app_policy.adjust" -> {
            text("package_ref", 120); int("daily_limit_minutes", 5..480)
            require(params["mode"] in setOf("soft_reminder", "friction"))
            text("valid_until", 40); parseUtc(params.getValue("valid_until") as String)
            val window = params["window"]
            if (window != null) {
                val map = window as? Map<*, *> ?: error("window must be an object")
                require(map.keys == setOf("start", "end", "timezone"))
                LocalTime.parse(map["start"] as? String ?: error("Invalid window start"))
                LocalTime.parse(map["end"] as? String ?: error("Invalid window end"))
                ZoneId.of(map["timezone"] as? String ?: error("Invalid window timezone"))
            }
        }
        "content.recommend" -> {
            require((params["content_id"] is String) xor (params["url"] is String))
            if (params["content_id"] != null) text("content_id", 120)
            if (params["url"] != null) {
                text("url", 2_000)
                require(ContentUrlValidator.isAllowedSearchOrVideo(params.getValue("url") as String))
            }
            text("title", 120)
        }
    }
}

private fun allowedForPayload(action: String, params: Map<String, Any?>, allowed: Set<String>): Set<String> {
    val optional = when (action) {
        "home.set_directive" -> setOf("focus_proposal_id")
        "focus.propose", "focus.plan" -> setOf("label")
        "app_policy.propose", "app_policy.adjust" -> setOf("window")
        else -> emptySet()
    }
    if (action == "content.recommend") {
        return setOf("title") + if (params.containsKey("content_id")) setOf("content_id") else setOf("url")
    }
    return (allowed - optional) + (optional intersect params.keys)
}

data class AgentVerificationContext(
    val nowUtcMillis: Long,
    val agentEnabled: Boolean = true,
    val emergencyPaused: Boolean = false,
    val capabilities: Set<String> = setOf("focus", "habit", "content", "coach"),
    val observedPackages: Set<String> = emptySet(),
    val vitalPackages: Set<String> = emptySet(),
    val changesToday: Int = 0,
    val focusSessionsToday: Int = 0,
    val lastActionAtUtcByType: Map<String, Long> = emptyMap(),
    val existingFocusProposalIds: Set<String> = emptySet(),
    val cancelledFocusProposalIds: Set<String> = emptySet(),
    val runningFocusSessionRef: String? = null,
    val focusMinutesByProposal: Map<String, Int> = emptyMap(),
)

data class MandateDecision(
    val accepted: Boolean,
    val reason: String,
    val requiresConfirmation: Boolean = true,
) {
    companion object { fun reject(reason: String) = MandateDecision(false, reason) }
}

/** The single V2 policy validator used both by dispatch and its JVM tests. */
class MandateVerifier {
    fun verify(proposal: ProposalV2, mandate: AgentMandate, context: AgentVerificationContext): MandateDecision {
        if (!context.agentEnabled) return MandateDecision.reject("Agent désactivé localement.")
        if (mandate.revoked) return MandateDecision.reject("Mandat révoqué.")
        if (mandate.suspended) return MandateDecision.reject("Mandat suspendu.")
        if (context.nowUtcMillis >= mandate.expiresAtUtcMillis) return MandateDecision.reject("Mandat expiré.")
        if (proposal.mandateId != mandate.id || proposal.mandateVersion != mandate.version) return MandateDecision.reject("Version du mandat différente.")
        if (proposal.state.terminal) return MandateDecision.reject("Proposition déjà terminée.")
        if (proposal.createdAtUtcMillis > context.nowUtcMillis + CLOCK_SKEW_MILLIS) return MandateDecision.reject("Proposition datée dans le futur.")
        if (context.nowUtcMillis >= proposal.expiresAtUtcMillis) return MandateDecision.reject("Proposition expirée.")
        if (proposal.expiresAtUtcMillis - proposal.createdAtUtcMillis > ProposalV2.MAX_TTL_MILLIS) return MandateDecision.reject("TTL de proposition hors limites.")
        val type = AgentActionType.parse(proposal.actionType) ?: return MandateDecision.reject("Type d'action inconnu.")
        if (context.emergencyPaused && type != AgentActionType.FOCUS_PAUSE) {
            return MandateDecision.reject("Pause d'urgence active.")
        }
        if (type.wireValue !in mandate.allowedActions) return MandateDecision.reject("Action absente du mandat.")
        if (context.changesToday >= mandate.maxChangesPerDay) return MandateDecision.reject("Quota quotidien du mandat atteint.")
        val cooldown = context.lastActionAtUtcByType[type.wireValue]
        if (type != AgentActionType.FOCUS_PAUSE && cooldown != null && context.nowUtcMillis - cooldown < ACTION_COOLDOWN_MILLIS) {
            return MandateDecision.reject("Action en période de refroidissement.")
        }
        runCatching { validateParameterShape(proposal.actionType, proposal.parameters) }
            .getOrElse { return MandateDecision.reject("Paramètres invalides pour cette action.") }
        val gate = when (type) {
            AgentActionType.HOME_SET_DIRECTIVE, AgentActionType.HOME_REORDER_CARDS -> {
                if ("coach" !in context.capabilities) "Coach local désactivé." else null
            }
            AgentActionType.FOCUS_PROPOSE, AgentActionType.FOCUS_PLAN, AgentActionType.FOCUS_START, AgentActionType.FOCUS_PAUSE -> {
                if ("focus" !in context.capabilities) "Focus local désactivé." else null
            }
            AgentActionType.HABIT_SUGGEST -> if ("habit" !in context.capabilities) "Habitudes désactivées." else null
            AgentActionType.CONTENT_RECOMMEND -> if ("content" !in context.capabilities) "Bibliothèque désactivée." else null
            AgentActionType.APP_POLICY_PROPOSE, AgentActionType.APP_POLICY_ADJUST -> {
                val packageRef = proposal.parameters["package_ref"] as String
                when {
                    packageRef in context.vitalPackages || SafetyDenylist.isProtected(packageRef) -> "Application vitale protégée."
                    packageRef !in context.observedPackages -> "Application non consentie à l'observation."
                    else -> null
                }
            }
        }
        if (gate != null) return MandateDecision.reject(gate)
        if (type in setOf(AgentActionType.FOCUS_PROPOSE, AgentActionType.FOCUS_PLAN, AgentActionType.FOCUS_START)) {
            if (type == AgentActionType.FOCUS_PLAN && "focusRoutines" !in context.capabilities) {
                return MandateDecision.reject("Planification inexacte désactivée dans les capacités locales.")
            }
            val ref = proposal.parameters["session_ref"] as? String
            val minutes = (proposal.parameters["focus_minutes"] as? Number)?.toInt()
                ?: ref?.let(context.focusMinutesByProposal::get)
                ?: mandate.focusWindow.maxMinutesPerSession
            val sessionMinutes = if (type == AgentActionType.FOCUS_PROPOSE || type == AgentActionType.FOCUS_PLAN) {
                val breakMinutes = (proposal.parameters["break_minutes"] as Number).toLong()
                val cycles = (proposal.parameters["cycles"] as Number).toLong()
                val longEvery = minOf(4L, cycles)
                val longBreaks = (cycles - 1L) / longEvery
                val shortBreaks = (cycles - 1L) - longBreaks
                // Match AtlasController's FocusPlan: each long break replaces a short break.
                minutes.toLong() * cycles + shortBreaks * breakMinutes + longBreaks * 15L
            } else {
                minutes.toLong()
            }
            if (type == AgentActionType.FOCUS_PROPOSE || type == AgentActionType.FOCUS_PLAN) {
                if (sessionMinutes > mandate.focusWindow.maxMinutesPerSession.toLong()) {
                    return MandateDecision.reject("Durée totale supérieure à la borne du mandat.")
                }
            } else if (minutes > mandate.focusWindow.maxMinutesPerSession) {
                return MandateDecision.reject("Durée supérieure à la borne du mandat.")
            }
            if (context.focusSessionsToday >= mandate.focusWindow.maxSessionsPerDay) return MandateDecision.reject("Quota quotidien de sessions Focus atteint.")
            val zone = ZoneId.of(mandate.focusWindow.timezone)
            val localNow = Instant.ofEpochMilli(context.nowUtcMillis).atZone(zone)
            val scheduled = if (type == AgentActionType.FOCUS_PLAN) {
                val planAt = parseUtc(proposal.parameters["start_at"] as String)
                val planZone = ZoneId.of(proposal.parameters["timezone"] as String)
                if (planZone != zone) return MandateDecision.reject("Fuseau différent de celui du mandat.")
                if (planAt <= context.nowUtcMillis || planAt - context.nowUtcMillis > SEVEN_DAYS_MILLIS) {
                    return MandateDecision.reject("Date de planification hors limites.")
                }
                Instant.ofEpochMilli(planAt).atZone(zone)
            } else localNow
            if (scheduled.dayOfWeek.value !in mandate.focusWindow.days) return MandateDecision.reject("Jour hors fenêtre du mandat.")
            val time = scheduled.toLocalTime()
            val start = LocalTime.parse(mandate.focusWindow.start)
            val end = LocalTime.parse(mandate.focusWindow.end)
            fun inWindow(time: LocalTime): Boolean =
                if (start <= end) {
                    !time.isBefore(start) && time.isBefore(end)
                } else {
                    !time.isBefore(start) || time.isBefore(end)
                }
            if (!inWindow(time)) return MandateDecision.reject("Heure hors fenêtre du mandat.")
            if (type == AgentActionType.FOCUS_PLAN) {
                val endDate = if (start <= end) {
                    scheduled.toLocalDate()
                } else if (!scheduled.toLocalTime().isBefore(start)) {
                    scheduled.toLocalDate().plusDays(1)
                } else {
                    scheduled.toLocalDate()
                }
                val windowEndLocal = endDate.atTime(end)
                val planEndLocal = scheduled.plusSeconds(sessionMinutes * 60L).toLocalDateTime()
                if (planEndLocal.isAfter(windowEndLocal)) {
                    return MandateDecision.reject("Fin du plan hors fenêtre du mandat.")
                }
            }
        }
        if (type == AgentActionType.FOCUS_START) {
            val ref = proposal.parameters["session_ref"] as String
            if (ref !in context.existingFocusProposalIds || ref in context.cancelledFocusProposalIds) return MandateDecision.reject("Session absente ou annulée.")
        }
        if (type == AgentActionType.FOCUS_PAUSE && context.runningFocusSessionRef != proposal.parameters["session_ref"]) {
            return MandateDecision.reject("Session Focus inactive.")
        }
        if (type in setOf(AgentActionType.APP_POLICY_PROPOSE, AgentActionType.APP_POLICY_ADJUST)) {
            val packageRef = proposal.parameters["package_ref"] as String
            val validUntil = parseUtc(proposal.parameters["valid_until"] as String)
            if (validUntil <= context.nowUtcMillis || validUntil > context.nowUtcMillis + 14L * DAY_MILLIS) {
                return MandateDecision.reject("Durée de la politique hors limites.")
            }
        }
        if (type == AgentActionType.APP_POLICY_ADJUST) {
            val packageRef = proposal.parameters["package_ref"] as String
            val bounds = mandate.appPolicyBounds[packageRef] ?: return MandateDecision.reject("Aucune borne de politique pour cette application.")
            val minutes = proposal.parameters["daily_limit_minutes"] as Int
            val mode = proposal.parameters["mode"] as String
            val validUntil = parseUtc(proposal.parameters["valid_until"] as String)
            if (minutes < bounds.minLimitMinutes || mode == "friction" && bounds.maxMode != "friction" ||
                validUntil > context.nowUtcMillis + bounds.maxValidDays * DAY_MILLIS) {
                return MandateDecision.reject("Modification hors bornes du mandat.")
            }
        }
        // required_level is advisory from the backend; local mandate and bounds decide autonomy.
        val automatic = mandate.mode == AgentMode.PREAUTHORIZED && type in PREAUTHORIZED_ACTIONS
        val confirmationRequired = proposal.state == ProposalState.PENDING_CONFIRMATION || !automatic
        return MandateDecision(
            true,
            if (confirmationRequired) "Confirmation locale requise." else "Préautorisé par le mandat local.",
            confirmationRequired,
        )
    }

    /** Source-compatible V1 bridge; all checks still flow through the V2 verifier above. */
    fun verify(proposal: ProposedAction, mandate: AgentMandate): MandateDecision {
        val now = System.currentTimeMillis()
        val action = legacyActionName(proposal.type) ?: proposal.type
        val params = when (proposal.type) {
            "focus_session" -> mapOf("focus_minutes" to (proposal.parameters["duration_minutes"]?.toIntOrNull() ?: -1), "break_minutes" to 0, "cycles" to 1)
            "suggest_break" -> mapOf("focus_minutes" to (proposal.parameters["duration_minutes"]?.toIntOrNull() ?: -1), "break_minutes" to 0, "cycles" to 1)
            "open_content" -> mapOf("content_id" to proposal.parameters["content_id"].orEmpty(), "title" to "Contenu")
            else -> proposal.parameters
        }
        val v2 = runCatching {
            ProposalV2(2, UUID.randomUUID().toString(), "legacy-check", null, mandate.id, mandate.version,
                "legacy-v1", now, now + 60_000, action, params, proposal.justification.take(240).ifBlank { "Proposition locale" },
                ProposalLevel.CONFIRM)
        }.getOrElse { return MandateDecision.reject("Paramètres invalides pour cette action.") }
        return verify(v2, mandate, AgentVerificationContext(nowUtcMillis = now))
    }

    companion object {
        private const val CLOCK_SKEW_MILLIS = 5 * 60 * 1_000L
        internal const val ACTION_COOLDOWN_MILLIS = 30_000L
        private const val SEVEN_DAYS_MILLIS = 7L * 24L * 60L * 60L * 1_000L
        private const val DAY_MILLIS = 24L * 60L * 60L * 1_000L
        val PREAUTHORIZED_ACTIONS = setOf(
            AgentActionType.HOME_SET_DIRECTIVE, AgentActionType.HOME_REORDER_CARDS,
            AgentActionType.FOCUS_PLAN, AgentActionType.FOCUS_START, AgentActionType.FOCUS_PAUSE,
            AgentActionType.APP_POLICY_ADJUST, AgentActionType.CONTENT_RECOMMEND,
        )
    }
}

private fun parseUtc(value: String): Long {
    require(value.endsWith("Z") && value.length <= 40) { "Timestamp must be RFC3339 UTC" }
    return try { Instant.parse(value).toEpochMilli() } catch (error: DateTimeParseException) {
        throw IllegalArgumentException("Invalid RFC3339 timestamp", error)
    }
}

private fun isoUtc(value: Long): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(value))

private fun JSONObject.requireKeys(expected: Set<String>) {
    val actual = keys().asSequence().toSet()
    require(actual == expected) { "JSON object contains missing or unknown fields" }
}

private fun JSONObject.requiredString(key: String): String = get(key) as? String ?: error("$key must be a string")
private fun JSONObject.requiredBoolean(key: String): Boolean = get(key) as? Boolean ?: error("$key must be a boolean")
private fun JSONObject.requiredLong(key: String): Long = (get(key) as? Number)?.let(::exactLong)
    ?: throw IllegalArgumentException("$key must be an integer")
private fun JSONObject.requiredInt(key: String): Int = requiredLong(key).also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()
private fun JSONArray.toStringList(): List<String> = (0 until length()).map {
    get(it) as? String ?: throw IllegalArgumentException("Array values must be strings")
}
private fun JSONObject.toScalarValueMap(): Map<String, Any?> = buildMap {
    val source = this@toScalarValueMap
    val names = source.keys()
    while (names.hasNext()) {
        val key = names.next()
        put(key, jsonValue(source.get(key)))
    }
}
private fun jsonValue(value: Any?): Any? = when (value) {
    JSONObject.NULL -> null
    is JSONObject -> value.toScalarValueMap()
    is JSONArray -> (0 until value.length()).map { jsonValue(value.get(it)) }
    is Number -> exactLong(value)?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it } ?: value
    else -> value
}

private fun containsNullValue(value: Any?): Boolean = when (value) {
    null -> true
    is Map<*, *> -> value.values.any(::containsNullValue)
    is List<*> -> value.any(::containsNullValue)
    else -> false
}

private fun exactLong(value: Number): Long? = runCatching {
    BigDecimal(value.toString()).longValueExact()
}.getOrNull()

interface MandateStore {
    fun current(): AgentMandate
    fun revoke()
    fun replace(mandate: AgentMandate)
}

class LocalMandateStore(initial: AgentMandate) : MandateStore {
    @Volatile private var mandate = initial

    override fun current(): AgentMandate = mandate

    @Synchronized
    override fun revoke() {
        mandate = mandate.copy(revoked = true, version = mandate.version + 1)
    }

    @Synchronized
    override fun replace(mandate: AgentMandate) {
        require(mandate.id.isNotBlank())
        this.mandate = mandate.copy(version = this.mandate.version + 1)
    }
}

data class ProposalRecord(
    val id: String,
    val createdAtUtcMillis: Long,
    val mandateId: String,
    val mandateVersion: Long,
    val proposal: ProposedAction,
    val validation: MandateDecision,
    val simulationMode: Boolean,
)

interface ProposalJournal {
    fun append(record: ProposalRecord)
    fun entries(): List<ProposalRecord>
}

class InMemoryProposalJournal : ProposalJournal {
    private val records = mutableListOf<ProposalRecord>()

    @Synchronized
    override fun append(record: ProposalRecord) { records += record }

    @Synchronized
    override fun entries(): List<ProposalRecord> = records.toList()
}

/** Durable local journal for proposals and their deterministic validation result. */
class JsonProposalJournal(private val storage: OutboxStorage) : ProposalJournal {
    private val records = mutableListOf<ProposalRecord>()

    init {
        val raw = storage.read()
        if (!raw.isNullOrBlank()) {
            val root = JSONObject(raw)
            require(root.getInt("schema_version") == SCHEMA_VERSION)
            val values = root.getJSONArray("records")
            for (index in 0 until values.length()) records += values.getJSONObject(index).toRecord()
        }
    }

    @Synchronized
    override fun append(record: ProposalRecord) {
        records += record
        persist()
    }

    @Synchronized
    override fun entries(): List<ProposalRecord> = records.toList()

    private fun persist() {
        storage.write(JSONObject()
            .put("schema_version", SCHEMA_VERSION)
            .put("records", JSONArray().also { array -> records.forEach { array.put(it.toJson()) } })
            .toString())
    }

    companion object { private const val SCHEMA_VERSION = 1 }
}

class ProposalWorkflow(
    private val provider: CoachDecisionProvider,
    private val mandates: MandateStore,
    private val verifier: MandateVerifier,
    private val journal: ProposalJournal,
    private val nowUtcMillis: () -> Long = System::currentTimeMillis,
    private val simulationMode: Boolean = true,
) {
    fun propose(context: String): ProposalRecord {
        require(context.length <= 4_000) { "Coach context is too long" }
        val mandate = mandates.current()
        val proposal = provider.propose(context, mandate)
        val record = ProposalRecord(
            id = UUID.randomUUID().toString(),
            createdAtUtcMillis = nowUtcMillis(),
            mandateId = mandate.id,
            mandateVersion = mandate.version,
            proposal = proposal,
            validation = verifier.verify(proposal, mandate),
            simulationMode = simulationMode,
        )
        journal.append(record)
        return record
    }
}

/** Stable mock for UI and contract tests; it ignores free-form text and only proposes focus. */
class DeterministicMockDecisionProvider : AgentAdapter {
    override fun propose(context: String, mandate: AgentMandate): ProposedAction = ProposedAction(
        type = ProposedActionType.FOCUS_SESSION.wireValue,
        parameters = mapOf("duration_minutes" to "10"),
        justification = "Une courte session est une prochaine étape simple.",
    )
}

private fun ProposalRecord.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("created_at_utc_ms", createdAtUtcMillis)
    .put("mandate_id", mandateId)
    .put("mandate_version", mandateVersion)
    .put("action", proposal.type)
    .put("parameters", JSONObject(proposal.parameters))
    .put("justification", proposal.justification)
    .put("accepted", validation.accepted)
    .put("validation_reason", validation.reason)
    .put("simulation_mode", simulationMode)

private fun JSONObject.toRecord(): ProposalRecord {
    val parametersJson = getJSONObject("parameters")
    val parameters = buildMap {
        val keys = parametersJson.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            put(key, parametersJson.getString(key))
        }
    }
    return ProposalRecord(
        id = getString("id"),
        createdAtUtcMillis = getLong("created_at_utc_ms"),
        mandateId = getString("mandate_id"),
        mandateVersion = getLong("mandate_version"),
        proposal = ProposedAction(getString("action"), parameters, getString("justification")),
        validation = MandateDecision(getBoolean("accepted"), getString("validation_reason")),
        simulationMode = getBoolean("simulation_mode"),
    )
}
