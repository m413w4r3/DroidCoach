package dev.atlas.coach.coach.proactive

/**
 * Adapted from EthereumPhone/AndyClaw's
 * `app/src/main/java/org/ethereumphone/andyclaw/heartbeat/NotificationTriggerPolicy.kt`
 * at fd4a9af917f51d7b1c10e09e6496f54a290b253b (GPL-3.0-only).
 *
 * ATLAS retains its LinkedHashSet event de-duplication and minimum-gap rule. The notification
 * listener inputs, Android notification categories, package filtering, and AndyClaw runtime hooks
 * are removed. Cooldowns are keyed by a coaching motif and included in the versioned ATLAS state.
 */
class ProactiveTriggerGate(
    val minGapMs: Long = MIN_COOLDOWN_MS,
    private val maxRecentEventIds: Int = MAX_RECENT_EVENT_IDS,
) {
    init {
        require(minGapMs >= MIN_COOLDOWN_MS) { "Proactive cooldown cannot be shorter than 60 minutes" }
        require(maxRecentEventIds >= 1)
    }

    data class Memory(
        val recentEventIds: List<String> = emptyList(),
        val lastRequestedAtUtcMillisByMotif: Map<String, Long> = emptyMap(),
    )

    fun hasSeen(eventId: String, memory: Memory): Boolean = eventId in memory.recentEventIds

    /** Returns a bounded insertion-ordered history like the adapted AndyClaw LinkedHashSet. */
    fun remember(eventId: String, memory: Memory): Memory {
        val seen = LinkedHashSet(memory.recentEventIds)
        if (!seen.add(eventId)) return memory
        while (seen.size > maxRecentEventIds) seen.remove(seen.first())
        return memory.copy(recentEventIds = seen.toList())
    }

    fun canRequest(motifKeys: Set<String>, nowUtcMillis: Long, memory: Memory): Boolean {
        require(nowUtcMillis >= 0L)
        return motifKeys.all { motif ->
            val previous = memory.lastRequestedAtUtcMillisByMotif[motif] ?: return@all true
            nowUtcMillis >= previous && nowUtcMillis - previous >= minGapMs
        }
    }

    fun markRequested(motifKeys: Set<String>, nowUtcMillis: Long, memory: Memory): Memory {
        require(nowUtcMillis >= 0L)
        val updated = memory.lastRequestedAtUtcMillisByMotif.toMutableMap()
        motifKeys.forEach { updated[it] = nowUtcMillis }
        return memory.copy(lastRequestedAtUtcMillisByMotif = updated)
    }

    companion object {
        const val MIN_COOLDOWN_MS = 60L * 60L * 1_000L
        const val MAX_RECENT_EVENT_IDS = 500
    }
}
