package dev.atlas.coach.e2e

import java.util.Locale
import java.util.UUID

internal data class ToolMetadata(
    val toolState: String,
    val toolCallCount: Int,
    val proposalIds: List<String>,
)

internal object V7_06BChatTestSupport {
    private const val TOOLS_PROVENANCE_LABEL = "État des outils"

    private val toolMetadataSuffixPattern = Regex(
        "État des outils\\s*:\\s*([a-z_]+)\\s*\\((\\d+) appel\\(s\\)\\)\\.\\s*" +
            "Proposition\\(s\\)\\s*\\((\\d+)\\)\\s*—\\s*identifiants\\s*:\\s*" +
            "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?:,\\s*[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})*)\\.\\s*" +
            "Contenu à synchroniser via la boîte de réception\\.",
        RegexOption.IGNORE_CASE,
    )

    fun syntheticCoachMessage(
        localDate: String,
        planId: String,
        baseVersion: Int,
        activityId: String,
        targetStartMinute: Int,
        durationMinutes: Int,
        runId: String,
        promptIdempotencyKey: String,
    ): String = "Décale mon activité de travail de 10 minutes et présente-moi la modification à confirmer. " +
        "run_id=$runId. Appelle atlas_get_day_plan pour local_date=$localDate; vérifie exactement " +
        "plan_id=$planId et version=$baseVersion. Ensuite appelle " +
        "atlas_propose_plan_revision exactement une fois avec plan_id=$planId, " +
        "base_version=$baseVersion, activity_id=$activityId, op=reschedule, " +
        "target_start_minute=$targetStartMinute, duration_minutes=$durationMinutes (inchangée), " +
        "idempotency_key=$promptIdempotencyKey. Ne crée aucune autre proposition et n'applique rien."

    fun parseToolMetadata(text: String): ToolMetadata {
        val metadataIndex = text.lastIndexOf(TOOLS_PROVENANCE_LABEL)
        check(metadataIndex >= 0) { "V4 tool metadata is missing from the assistant response" }
        val suffix = text.substring(metadataIndex).trimEnd()
        val match = toolMetadataSuffixPattern.matchEntire(suffix)
            ?: error("V4 tool metadata is not the generated suffix of the assistant response")
        val proposalCount = match.groupValues[3].toInt()
        val proposalIds = if (proposalCount == 0) {
            emptyList()
        } else {
            match.groupValues[4].split(',').map(String::trim)
        }
        if (proposalCount != proposalIds.size) {
            throw AssertionError("metadata proposal count must match the returned ID list")
        }
        if (!proposalIds.all(::isCanonicalUuid)) {
            throw AssertionError("proposal IDs must be canonical UUIDs")
        }
        return ToolMetadata(
            toolState = match.groupValues[1],
            toolCallCount = match.groupValues[2].toInt(),
            proposalIds = proposalIds,
        )
    }

    private fun isCanonicalUuid(value: String): Boolean = runCatching {
        UUID.fromString(value).toString() == value.lowercase(Locale.ROOT)
    }.getOrDefault(false)
}
