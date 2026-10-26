package dev.atlas.coach.app

import android.content.Context
import android.annotation.SuppressLint
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Write authorization is a separate, local choice from calendar read consent and Android grants. */
internal interface CalendarWriteConsentStore {
    fun isGranted(): Boolean
    fun grant(): Boolean
    fun revoke(): Boolean
}

/** Serializes provider mutations with consent revocation for immediate in-process shutdown. */
internal object CalendarWriteConsentGate {
    val lock = Any()
}

@SuppressLint("UseKtx") // Synchronous commit success is required to report persisted consent accurately.
internal class SharedPreferencesCalendarWriteConsentStore(context: Context) : CalendarWriteConsentStore {
    private val appContext = context.applicationContext
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val grantState = grantStates.computeIfAbsent(appContext.packageName) {
        AtomicBoolean(preferences.getBoolean(CONSENT_KEY, false))
    }

    override fun isGranted(): Boolean = grantState.get() && preferences.getBoolean(CONSENT_KEY, false)

    override fun grant(): Boolean {
        synchronized(CalendarWriteConsentGate.lock) {
            val saved = preferences.edit().putBoolean(CONSENT_KEY, true).commit()
            if (saved) grantState.set(true)
            return saved
        }
    }

    override fun revoke(): Boolean {
        // Close the in-process gate before touching disk so concurrent sync work fails closed now.
        synchronized(CalendarWriteConsentGate.lock) {
            grantState.set(false)
            return preferences.edit().remove(CONSENT_KEY).commit()
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "atlas_calendar_write_consent"
        const val CONSENT_KEY = "calendar_write_approved"
        val grantStates = ConcurrentHashMap<String, AtomicBoolean>()
    }
}

/** The selected provider row is only an ID; account identifiers and provider URLs are never saved here. */
internal interface CalendarWriteSelectionStore {
    fun selectedCalendarId(): Long?
    fun select(calendarId: Long): Boolean
    fun clear(): Boolean
}

@SuppressLint("UseKtx") // The caller must know whether the selected calendar ID reached disk.
internal class SharedPreferencesCalendarWriteSelectionStore(context: Context) : CalendarWriteSelectionStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun selectedCalendarId(): Long? = if (preferences.contains(CALENDAR_ID_KEY)) {
        preferences.getLong(CALENDAR_ID_KEY, -1L).takeIf { it >= 0L }
    } else {
        null
    }

    override fun select(calendarId: Long): Boolean =
        calendarId >= 0L && preferences.edit().putLong(CALENDAR_ID_KEY, calendarId).commit()

    override fun clear(): Boolean = preferences.edit().remove(CALENDAR_ID_KEY).commit()

    private companion object {
        const val PREFERENCES_NAME = "atlas_calendar_write_selection"
        const val CALENDAR_ID_KEY = "selected_local_calendar_id"
    }
}

/** Persist only a fixed status code, time, and counts for the diagnostic screen. */
internal data class CalendarEffectLastStatus(
    val code: String,
    val atMillis: Long,
    val effectCount: Int,
    val completedCount: Int,
)

internal interface CalendarEffectStatusStore {
    fun read(): CalendarEffectLastStatus?
    fun write(status: CalendarEffectLastStatus): Boolean
}

@SuppressLint("UseKtx") // Diagnostic status persistence is reported only after a successful commit.
internal class SharedPreferencesCalendarEffectStatusStore(context: Context) : CalendarEffectStatusStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun read(): CalendarEffectLastStatus? {
        if (!preferences.contains(CODE_KEY)) return null
        return CalendarEffectLastStatus(
            code = preferences.getString(CODE_KEY, "UNKNOWN") ?: "UNKNOWN",
            atMillis = preferences.getLong(AT_KEY, 0L),
            effectCount = preferences.getInt(COUNT_KEY, 0),
            completedCount = preferences.getInt(COMPLETED_KEY, 0),
        )
    }

    override fun write(status: CalendarEffectLastStatus): Boolean = preferences.edit()
        .putString(CODE_KEY, status.code)
        .putLong(AT_KEY, status.atMillis)
        .putInt(COUNT_KEY, status.effectCount)
        .putInt(COMPLETED_KEY, status.completedCount)
        .commit()

    private companion object {
        const val PREFERENCES_NAME = "atlas_calendar_effect_status"
        const val CODE_KEY = "last_status_code"
        const val AT_KEY = "last_status_at"
        const val COUNT_KEY = "last_effect_count"
        const val COMPLETED_KEY = "last_completed_count"
    }
}
