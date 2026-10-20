package dev.atlas.coach.sync.agent.context

import org.json.JSONArray
import org.json.JSONObject

enum class LastContextDisclosureStatus {
    SUCCESS,
    ATTEMPTED,
}

/** Durable disclosure metadata. This deliberately has no field capable of holding payload values. */
data class LastContextDisclosureAudit(
    val sentAtUtcMillis: Long,
    val categoryNames: List<String>,
    val status: LastContextDisclosureStatus,
) {
    init {
        require(sentAtUtcMillis > 0L)
        require(categoryNames.all { it in AgentContextCategory.wireValueSet })
        require(categoryNames.distinct().size == categoryNames.size)
    }

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "sentAtUtcMillis" to sentAtUtcMillis,
        "categoryNames" to categoryNames.toList(),
        "status" to status.name,
    )

    companion object {
        fun fromMap(values: Map<String, Any?>?): LastContextDisclosureAudit? {
            if (values == null) return null
            val sentAt = (values["sentAtUtcMillis"] as? Number)?.toLong()?.takeIf { it > 0L } ?: return null
            val status = (values["status"] as? String)?.let {
                runCatching { LastContextDisclosureStatus.valueOf(it) }.getOrNull()
            } ?: return null
            val categories = (values["categoryNames"] as? List<*>)
                ?.filterIsInstance<String>()
                ?.filter { it in AgentContextCategory.wireValueSet }
                ?.distinct()
                ?: return null
            return runCatching { LastContextDisclosureAudit(sentAt, categories, status) }.getOrNull()
        }

        /** Converts a V6.2 payload to metadata only. Legacy values are never returned or cached. */
        fun fromLegacyPayload(payloadJson: String?, sentAtUtcMillis: Long?): LastContextDisclosureAudit? {
            val sentAt = sentAtUtcMillis?.takeIf { it > 0L } ?: return null
            val payload = payloadJson?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
            val categoryObject = payload.optJSONObject("categories")
            val valueCategories = categoryObject?.let { categories ->
                AgentContextCategory.wireValues.filter(categories::has)
            }.orEmpty()
            val consentedCategories = payload.optJSONArray("consented_categories")
                ?.toKnownCategoryNames()
                .orEmpty()
            return LastContextDisclosureAudit(
                sentAtUtcMillis = sentAt,
                categoryNames = (valueCategories.ifEmpty { consentedCategories }).distinct(),
                status = LastContextDisclosureStatus.ATTEMPTED,
            )
        }

        /** Removes one category from the session-only preview and fails closed on malformed JSON. */
        fun purgePayloadCategory(payloadJson: String?, category: String): String? {
            if (payloadJson == null) return null
            require(category in AgentContextCategory.wireValueSet)
            return runCatching {
                val payload = JSONObject(payloadJson)
                val categories = payload.optJSONObject("categories") ?: JSONObject()
                categories.remove(category)
                payload.put("categories", categories)
                val oldConsent = payload.optJSONArray("consented_categories") ?: JSONArray()
                val remainingConsent = JSONArray().apply {
                    for (index in 0 until oldConsent.length()) {
                        val value = oldConsent.optString(index)
                        if (value != category) put(value)
                    }
                }
                payload.put("consented_categories", remainingConsent).toString()
            }.getOrNull()
        }

        fun purgeAllPayloadValues(@Suppress("UNUSED_PARAMETER") payloadJson: String?): String? = null

        private fun JSONArray.toKnownCategoryNames(): List<String> =
            (0 until length()).mapNotNull { optString(it).takeIf(AgentContextCategory.wireValueSet::contains) }
    }
}
