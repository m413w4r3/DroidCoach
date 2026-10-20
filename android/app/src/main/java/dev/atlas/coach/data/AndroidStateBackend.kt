package dev.atlas.coach.data

import android.content.Context
import android.util.Log
import java.util.UUID

/** Private local storage. State transitions commit; timer checkpoints use SharedPreferences.apply. */
class AndroidStateBackend(context: Context) : StateBackend {
    private val preferences = context.applicationContext.getSharedPreferences("atlas_local_state", Context.MODE_PRIVATE)

    override fun read(): String? {
        return try {
            val value = preferences.all[KEY_STATE] ?: return null
            value as? String ?: throw IllegalStateException("Stored ATLAS state is not a string")
        } catch (error: Exception) {
            Log.e("ATLAS.Persistence", "Unable to read private local state (${error.javaClass.simpleName})")
            throw error
        }
    }

    override fun write(value: String) {
        check(preferences.edit().putString(KEY_STATE, value).commit()) { "Unable to commit local ATLAS state" }
    }

    override fun writeCheckpoint(value: String) {
        preferences.edit().putString(KEY_STATE, value).apply()
    }

    override fun quarantine(rawValue: String, reason: String): Boolean {
        val key = "state_quarantine_${System.currentTimeMillis()}_${UUID.randomUUID()}"
        return preferences.edit()
            .putString(key, rawValue)
            .putString("${key}_reason", reason)
            .commit()
    }

    companion object {
        private const val KEY_STATE = "state_v1"
    }
}
