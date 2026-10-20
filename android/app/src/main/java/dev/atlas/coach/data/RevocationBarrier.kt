package dev.atlas.coach.data

import android.content.Context

/** A separate durable deny latch checked before every remote agent request and local dispatch. */
interface RevocationBarrierStorage {
    fun readState(): RevocationBarrierState
    fun writeState(state: RevocationBarrierState): Boolean
}

data class RevocationBarrierState(
    val agentBlocked: Boolean = false,
    val blockedCategories: Set<String> = emptySet(),
) {
    val isBlocked: Boolean get() = agentBlocked || blockedCategories.isNotEmpty()
}

class RevocationBarrier(private val storage: RevocationBarrierStorage) {
    @Volatile
    private var state = runCatching(storage::readState).getOrDefault(RevocationBarrierState(agentBlocked = true))

    fun isBlocked(): Boolean = state.isBlocked

    /** Latch in memory before I/O so a failed marker write still disables this process. */
    @Synchronized
    fun block(): Boolean = blockAgent()

    @Synchronized
    fun blockAgent(): Boolean = writeBlockedState(state.copy(agentBlocked = true), latchBeforeWrite = true)

    @Synchronized
    fun blockCategory(category: String): Boolean {
        require(category.isNotBlank())
        return writeBlockedState(
            state.copy(blockedCategories = state.blockedCategories + category),
            latchBeforeWrite = true,
        )
    }

    /** Only a completed, explicit agent opt-in may clear the global agent deny bit. */
    @Synchronized
    fun clearAfterDurableOptIn(durablyStored: Boolean): Boolean {
        if (!durablyStored) return false
        if (!state.agentBlocked) return true
        return writeBlockedState(state.copy(agentBlocked = false), latchBeforeWrite = false)
    }

    /** Category revocations require a fresh opt-in for the same category. */
    @Synchronized
    fun clearCategoryAfterDurableOptIn(category: String, durablyStored: Boolean): Boolean {
        if (!durablyStored || category !in state.blockedCategories) return durablyStored
        return writeBlockedState(
            state.copy(blockedCategories = state.blockedCategories - category),
            latchBeforeWrite = false,
        )
    }

    private fun writeBlockedState(next: RevocationBarrierState, latchBeforeWrite: Boolean): Boolean {
        if (latchBeforeWrite) state = next
        val written = runCatching { storage.writeState(next) }.getOrDefault(false)
        if (written) state = next
        return written
    }
}

class AndroidRevocationBarrierStorage(context: Context) : RevocationBarrierStorage {
    private val preferences = context.applicationContext
        .getSharedPreferences("atlas_agent_revocation_barrier", Context.MODE_PRIVATE)

    override fun readState(): RevocationBarrierState = RevocationBarrierState(
        agentBlocked = preferences.getBoolean(KEY_BLOCKED, false),
        blockedCategories = preferences.getStringSet(KEY_BLOCKED_CATEGORIES, emptySet()).orEmpty().toSet(),
    )

    override fun writeState(state: RevocationBarrierState): Boolean = preferences.edit()
        .putBoolean(KEY_BLOCKED, state.agentBlocked)
        .putStringSet(KEY_BLOCKED_CATEGORIES, state.blockedCategories.toSet())
        .commit()

    private companion object {
        const val KEY_BLOCKED = "agent_blocked_v1"
        const val KEY_BLOCKED_CATEGORIES = "blocked_categories_v1"
    }
}
