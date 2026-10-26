package dev.atlas.coach.calendar

import android.content.Context

/** Separate, local ATLAS consent for calendar reads. OS permission alone is never enough. */
interface CalendarReadConsentStore {
    fun isGranted(): Boolean

    /** Returns false when the consent could not be persisted. */
    fun grant(): Boolean

    /** Returns false when revocation could not be persisted. */
    fun revoke(): Boolean
}

/** Private preferences dedicated to calendar consent, separate from other ATLAS settings. */
class SharedPreferencesCalendarReadConsentStore(context: Context) : CalendarReadConsentStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun isGranted(): Boolean = preferences.getBoolean(CONSENT_KEY, false)

    override fun grant(): Boolean = preferences.edit().putBoolean(CONSENT_KEY, true).commit()

    override fun revoke(): Boolean = preferences.edit().remove(CONSENT_KEY).commit()

    private companion object {
        const val PREFERENCES_NAME = "atlas_calendar_read_consent"
        const val CONSENT_KEY = "calendar_read_approved"
    }
}

/**
 * Consent boundary for every calendar read exposed by the existing read service.
 * With no ATLAS consent, no service/provider method is called, even if Android permission is granted.
 */
class ConsentGatedCalendarReadService(
    private val service: CalendarReadService,
    private val consentStore: CalendarReadConsentStore,
) {
    fun inspectCalendars(): CalendarCatalog? =
        if (hasConsent()) service.inspectCalendars() else null

    fun probe(): CalendarProbeReport? =
        if (hasConsent()) service.probe() else null

    fun queryEvents(
        calendarIds: Set<Long>,
        startMillis: Long,
        endMillis: Long,
    ): CalendarEventQueryResult? =
        if (hasConsent()) service.queryEvents(calendarIds, startMillis, endMillis) else null

    private fun hasConsent(): Boolean = try {
        consentStore.isGranted()
    } catch (_: Exception) {
        false
    }
}
