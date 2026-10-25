package dev.atlas.coach.plan

import org.json.JSONArray
import org.json.JSONObject

/** Strict schema-version-3 JSON codec. Unknown fields and coercible-but-wrong JSON types are rejected. */
object PlanJsonCodec {
    const val SCHEMA_VERSION = 3

    fun encodeDayPlan(plan: DayPlan): String = plan.toJsonObject()
        .put("schema_version", SCHEMA_VERSION)
        .toString()

    fun decodeDayPlan(json: String): DayPlan {
        val root = JSONObject(json)
        root.requireKeys(
            required = setOf(
                "schema_version", "plan_id", "local_date", "timezone", "version", "current_revision_id",
                "state", "activities", "created_at", "updated_at", "origin",
            ),
            optional = setOf("approved_at"),
        )
        root.requireSchemaVersion()
        root.remove("schema_version")
        return root.toDayPlan()
    }

    fun encodeRevision(revision: PlanRevision): String = revision.toJsonObject()
        .put("schema_version", SCHEMA_VERSION)
        .toString()

    fun decodeRevision(json: String): PlanRevision {
        val root = JSONObject(json)
        root.requireKeys(
            required = setOf(
                "schema_version", "revision_id", "plan_id", "base_version", "new_version", "origin",
                "changes", "summary", "created_at", "expires_at", "status",
            ),
        )
        root.requireSchemaVersion()
        root.remove("schema_version")
        return root.toPlanRevision()
    }

    fun encodeConfirmation(confirmation: PlanConfirmation): String = confirmation.toJsonObject()
        .put("schema_version", SCHEMA_VERSION)
        .toString()

    fun decodeConfirmation(json: String): PlanConfirmation {
        val root = JSONObject(json)
        root.requireKeys(
            required = setOf(
                "schema_version", "confirmation_id", "revision_id", "effect_ids", "scope_hash",
                "approved_at", "expires_at",
            ),
            optional = setOf("consumed_at"),
        )
        root.requireSchemaVersion()
        root.remove("schema_version")
        return root.toPlanConfirmation()
    }

    internal fun encodeStore(document: PlanStoreDocument): String = JSONObject().apply {
        put("schema_version", SCHEMA_VERSION)
        put("day_plan", document.dayPlan.toJsonObject())
        put("applied_revisions", JSONArray().also { array ->
            document.appliedRevisions.forEach { array.put(it.toJsonObject()) }
        })
        put("confirmations", JSONArray().also { array ->
            document.confirmations.forEach { array.put(it.toJsonObject()) }
        })
    }.toString()

    internal fun decodeStore(json: String): PlanStoreDocument {
        val root = JSONObject(json)
        root.requireKeys(
            required = setOf("schema_version", "day_plan", "applied_revisions", "confirmations"),
        )
        root.requireSchemaVersion()
        val plan = root.requiredObject("day_plan").toDayPlan()
        val revisions = root.requiredArray("applied_revisions").objects().map { it.toPlanRevision() }
        val confirmations = root.requiredArray("confirmations").objects().map { it.toPlanConfirmation() }
        require(revisions.all { it.planId == plan.planId && it.status == RevisionStatus.APPLIED }) {
            "invalid applied revision history"
        }
        require(revisions.map { it.revisionId }.toSet().size == revisions.size) {
            "duplicate applied revision"
        }
        require(confirmations.all { confirmation ->
            confirmation.consumedAt != null && revisions.any { it.revisionId == confirmation.revisionId }
        }) { "invalid confirmation history" }
        require(confirmations.map { it.confirmationId }.toSet().size == confirmations.size) {
            "duplicate confirmation"
        }
        require(confirmations.map { it.revisionId }.toSet().size == confirmations.size) {
            "duplicate revision confirmation"
        }
        require(confirmations.map { it.revisionId }.toSet() == revisions.map { it.revisionId }.toSet()) {
            "applied revision is missing its confirmation"
        }
        return PlanStoreDocument(plan, revisions, confirmations)
    }
}

private fun DayPlan.toJsonObject(): JSONObject = JSONObject().apply {
    put("plan_id", planId)
    put("local_date", localDate)
    put("timezone", timezone)
    put("version", version)
    put("current_revision_id", currentRevisionId)
    put("state", state.name)
    put("activities", JSONArray().also { array -> activities.forEach { array.put(it.toJsonObject()) } })
    put("created_at", createdAt)
    put("updated_at", updatedAt)
    approvedAt?.let { put("approved_at", it) }
    put("origin", origin.name)
}

private fun JSONObject.toDayPlan(): DayPlan {
    requireKeys(
        required = setOf(
            "plan_id", "local_date", "timezone", "version", "current_revision_id", "state",
            "activities", "created_at", "updated_at", "origin",
        ),
        optional = setOf("approved_at"),
    )
    return DayPlan(
        planId = requiredString("plan_id"),
        localDate = requiredString("local_date"),
        timezone = requiredString("timezone"),
        version = requiredInt("version"),
        currentRevisionId = requiredString("current_revision_id"),
        state = requiredEnum("state"),
        activities = requiredArray("activities").objects().map { it.toPlanActivity() },
        createdAt = requiredLong("created_at"),
        updatedAt = requiredLong("updated_at"),
        approvedAt = optionalLong("approved_at"),
        origin = requiredEnum("origin"),
    )
}

private fun PlanActivity.toJsonObject(): JSONObject = JSONObject().apply {
    put("activity_id", activityId)
    put("plan_id", planId)
    put("kind", kind.name)
    put("title", title)
    put("status", status.name)
    put("scheduled_local", scheduledLocal.toJsonObject())
    put("priority", priority.name)
    goalId?.let { put("goal_id", it) }
    subGoalId?.let { put("sub_goal_id", it) }
    estimateMinutes?.let { put("estimate_minutes", it) }
    actualMinutes?.let { put("actual_minutes", it) }
    put("created_revision_id", createdRevisionId)
    put("last_revision_id", lastRevisionId)
    notes?.let { put("notes", it) }
}

private fun JSONObject.toPlanActivity(): PlanActivity {
    requireKeys(
        required = setOf(
            "activity_id", "plan_id", "kind", "title", "status", "scheduled_local", "priority",
            "created_revision_id", "last_revision_id",
        ),
        optional = setOf("goal_id", "sub_goal_id", "estimate_minutes", "actual_minutes", "notes"),
    )
    return PlanActivity(
        activityId = requiredString("activity_id"),
        planId = requiredString("plan_id"),
        kind = requiredEnum("kind"),
        title = requiredString("title"),
        status = requiredEnum("status"),
        scheduledLocal = requiredObject("scheduled_local").toLocalSchedule(),
        priority = requiredEnum("priority"),
        goalId = optionalString("goal_id"),
        subGoalId = optionalString("sub_goal_id"),
        estimateMinutes = optionalInt("estimate_minutes"),
        actualMinutes = optionalInt("actual_minutes"),
        createdRevisionId = requiredString("created_revision_id"),
        lastRevisionId = requiredString("last_revision_id"),
        notes = optionalString("notes"),
    )
}

private fun LocalSchedule.toJsonObject(): JSONObject = JSONObject().apply {
    put("local_date", localDate)
    put("start_minute", startMinute)
    put("duration_minutes", durationMinutes)
    put("timezone", timezone)
}

private fun JSONObject.toLocalSchedule(): LocalSchedule {
    requireKeys(required = setOf("local_date", "start_minute", "duration_minutes", "timezone"))
    return LocalSchedule(
        localDate = requiredString("local_date"),
        startMinute = requiredInt("start_minute"),
        durationMinutes = requiredInt("duration_minutes"),
        timezone = requiredString("timezone"),
    )
}

private fun PlanRevision.toJsonObject(): JSONObject = JSONObject().apply {
    put("revision_id", revisionId)
    put("plan_id", planId)
    put("base_version", baseVersion)
    put("new_version", newVersion)
    put("origin", origin.name)
    put("changes", JSONArray().also { array -> changes.forEach { array.put(it.toJsonObject()) } })
    put("summary", summary)
    put("created_at", createdAt)
    put("expires_at", expiresAt)
    put("status", status.name)
}

private fun JSONObject.toPlanRevision(): PlanRevision {
    requireKeys(
        required = setOf(
            "revision_id", "plan_id", "base_version", "new_version", "origin", "changes", "summary",
            "created_at", "expires_at", "status",
        ),
    )
    return PlanRevision(
        revisionId = requiredString("revision_id"),
        planId = requiredString("plan_id"),
        baseVersion = requiredInt("base_version"),
        newVersion = requiredInt("new_version"),
        origin = requiredEnum("origin"),
        changes = requiredArray("changes").objects().map { it.toPlanChange() },
        summary = requiredString("summary"),
        createdAt = requiredLong("created_at"),
        expiresAt = requiredLong("expires_at"),
        status = requiredEnum("status"),
    )
}

private fun PlanChange.toJsonObject(): JSONObject = when (this) {
    is PlanChange.SlotAdded -> JSONObject().apply {
        put("type", "SlotAdded")
        put("activity", activity.toJsonObject())
    }
    is PlanChange.SlotRemoved -> JSONObject().apply {
        put("type", "SlotRemoved")
        put("activity_id", activityId)
        put("before", before.toJsonObject())
    }
    is PlanChange.SlotMoved -> JSONObject().apply {
        put("type", "SlotMoved")
        put("activity_id", activityId)
        put("from", from.toJsonObject())
        put("to", to.toJsonObject())
    }
    is PlanChange.SlotEdited -> JSONObject().apply {
        put("type", "SlotEdited")
        put("activity_id", activityId)
        put("before", before.toJsonObject())
        put("after", after.toJsonObject())
    }
    is PlanChange.StatusChanged -> JSONObject().apply {
        put("type", "StatusChanged")
        put("activity_id", activityId)
        put("from", from.name)
        put("to", to.name)
    }
}

private fun JSONObject.toPlanChange(): PlanChange = when (requiredString("type")) {
    "SlotAdded" -> {
        requireKeys(required = setOf("type", "activity"))
        PlanChange.SlotAdded(requiredObject("activity").toPlanActivity())
    }
    "SlotRemoved" -> {
        requireKeys(required = setOf("type", "activity_id", "before"))
        PlanChange.SlotRemoved(requiredString("activity_id"), requiredObject("before").toPlanActivity())
    }
    "SlotMoved" -> {
        requireKeys(required = setOf("type", "activity_id", "from", "to"))
        PlanChange.SlotMoved(
            activityId = requiredString("activity_id"),
            from = requiredObject("from").toLocalSchedule(),
            to = requiredObject("to").toLocalSchedule(),
        )
    }
    "SlotEdited" -> {
        requireKeys(required = setOf("type", "activity_id", "before", "after"))
        PlanChange.SlotEdited(
            activityId = requiredString("activity_id"),
            before = requiredObject("before").toPlanActivity(),
            after = requiredObject("after").toPlanActivity(),
        )
    }
    "StatusChanged" -> {
        requireKeys(required = setOf("type", "activity_id", "from", "to"))
        PlanChange.StatusChanged(
            activityId = requiredString("activity_id"),
            from = requiredEnum("from"),
            to = requiredEnum("to"),
        )
    }
    else -> throw IllegalArgumentException("unknown plan change type")
}

private fun PlanConfirmation.toJsonObject(): JSONObject = JSONObject().apply {
    put("confirmation_id", confirmationId)
    put("revision_id", revisionId)
    put("effect_ids", JSONArray(effectIds))
    put("scope_hash", scopeHash)
    put("approved_at", approvedAt)
    put("expires_at", expiresAt)
    consumedAt?.let { put("consumed_at", it) }
}

private fun JSONObject.toPlanConfirmation(): PlanConfirmation {
    requireKeys(
        required = setOf(
            "confirmation_id", "revision_id", "effect_ids", "scope_hash", "approved_at", "expires_at",
        ),
        optional = setOf("consumed_at"),
    )
    return PlanConfirmation(
        confirmationId = requiredString("confirmation_id"),
        revisionId = requiredString("revision_id"),
        effectIds = requiredArray("effect_ids").strings(),
        scopeHash = requiredString("scope_hash"),
        approvedAt = requiredLong("approved_at"),
        expiresAt = requiredLong("expires_at"),
        consumedAt = optionalLong("consumed_at"),
    )
}

private fun JSONObject.requireSchemaVersion() {
    val version = requiredInt("schema_version")
    require(version == PlanJsonCodec.SCHEMA_VERSION) { "unsupported plan schema version" }
}

private fun JSONObject.requireKeys(required: Set<String>, optional: Set<String> = emptySet()) {
    val actual = keys().asSequence().toSet()
    require(actual.containsAll(required)) { "missing required JSON field" }
    require(actual.all { it in required || it in optional }) { "unknown JSON field" }
}

private fun JSONObject.requiredObject(key: String): JSONObject =
    opt(key) as? JSONObject ?: throw IllegalArgumentException("$key must be an object")

private fun JSONObject.requiredArray(key: String): JSONArray =
    opt(key) as? JSONArray ?: throw IllegalArgumentException("$key must be an array")

private fun JSONObject.requiredString(key: String): String =
    opt(key) as? String ?: throw IllegalArgumentException("$key must be a string")

private fun JSONObject.optionalString(key: String): String? = if (has(key)) requiredString(key) else null

private fun JSONObject.requiredInt(key: String): Int {
    val value = opt(key)
    require(value is Int) { "$key must be an integer" }
    return value
}

private fun JSONObject.optionalInt(key: String): Int? = if (has(key)) requiredInt(key) else null

private fun JSONObject.requiredLong(key: String): Long {
    val value = opt(key)
    require(value is Int || value is Long) { "$key must be an integer" }
    return (value as Number).toLong()
}

private fun JSONObject.optionalLong(key: String): Long? = if (has(key)) requiredLong(key) else null

private inline fun <reified T : Enum<T>> JSONObject.requiredEnum(key: String): T {
    val value = requiredString(key)
    return enumValues<T>().firstOrNull { it.name == value }
        ?: throw IllegalArgumentException("$key has an unknown enum value")
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { index ->
    opt(index) as? JSONObject ?: throw IllegalArgumentException("array item must be an object")
}

private fun JSONArray.strings(): List<String> = (0 until length()).map { index ->
    opt(index) as? String ?: throw IllegalArgumentException("array item must be a string")
}
