package dev.atlas.coach.usage.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.atlas.coach.rules.LocalPolicy

/** A notification tap stores a local global pause; integration reads it before RuleEvaluator.poll(). */
class EmergencyPauseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_PAUSE_30_MINUTES) return
        AndroidEmergencyPauseStore.pauseForThirtyMinutes(context, System.currentTimeMillis())
    }

    companion object {
        const val ACTION_PAUSE_30_MINUTES = "dev.atlas.coach.usage.action.PAUSE_30_MINUTES"
    }
}

object AndroidEmergencyPauseStore {
    private const val PREFERENCES = "atlas_usage_observation_v1"
    private const val KEY_PAUSE_UNTIL = "emergency_pause_until_utc"
    private const val THIRTY_MINUTES = 30L * 60L * 1_000L

    fun pauseForThirtyMinutes(context: Context, nowUtc: Long) {
        val until = if (nowUtc > Long.MAX_VALUE - THIRTY_MINUTES) Long.MAX_VALUE else nowUtc + THIRTY_MINUTES
        check(
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putLong(KEY_PAUSE_UNTIL, until).commit(),
        ) { "Could not store the emergency pause" }
    }

    fun clear(context: Context) {
        check(
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().remove(KEY_PAUSE_UNTIL).commit(),
        ) { "Could not clear the emergency pause" }
    }

    fun apply(context: Context, policy: LocalPolicy, nowUtc: Long): LocalPolicy {
        val persistedUntil = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getLong(KEY_PAUSE_UNTIL, 0L)
            .takeIf { it > nowUtc }
        val until = listOfNotNull(policy.pauseUntil, persistedUntil).maxOrNull()
        return policy.copy(pauseUntil = until)
    }
}
