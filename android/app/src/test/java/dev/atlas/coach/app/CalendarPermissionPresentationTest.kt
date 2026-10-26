package dev.atlas.coach.app

import dev.atlas.coach.calendar.CalendarDescriptor
import dev.atlas.coach.calendar.CalendarPermissionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarPermissionPresentationTest {
    @Test
    fun mapsEveryProviderPermissionStateToUiStatus() {
        assertEquals(CalendarPermissionUiStatus.GRANTED, CalendarPermissionState.GRANTED.toUiStatus())
        assertEquals(CalendarPermissionUiStatus.DENIED, CalendarPermissionState.DENIED.toUiStatus())
        assertEquals(CalendarPermissionUiStatus.NOT_DECLARED, CalendarPermissionState.NOT_DECLARED.toUiStatus())
        assertEquals(CalendarPermissionUiStatus.UNAVAILABLE, CalendarPermissionState.UNKNOWN.toUiStatus())
    }

    @Test
    fun calendarListRequiresBothAndroidPermissionAndAtlasConsent() {
        assertTrue(mayReadCalendarDiagnostics(CalendarPermissionState.GRANTED, consentGranted = true))
        assertFalse(mayReadCalendarDiagnostics(CalendarPermissionState.GRANTED, consentGranted = false))
        assertFalse(mayReadCalendarDiagnostics(CalendarPermissionState.DENIED, consentGranted = true))
        assertFalse(mayReadCalendarDiagnostics(CalendarPermissionState.NOT_DECLARED, consentGranted = true))
        assertFalse(mayReadCalendarDiagnostics(CalendarPermissionState.UNKNOWN, consentGranted = true))
    }

    @Test
    fun runtimePermissionIsRequestedOnlyAfterExplicitActionAndConsent() {
        assertFalse(shouldLaunchCalendarPermissionRequest(
            CalendarPermissionState.DENIED,
            consentGranted = true,
            userExplicitlyRequested = false,
        ))
        assertFalse(shouldLaunchCalendarPermissionRequest(
            CalendarPermissionState.DENIED,
            consentGranted = false,
            userExplicitlyRequested = true,
        ))
        assertFalse(shouldLaunchCalendarPermissionRequest(
            CalendarPermissionState.NOT_DECLARED,
            consentGranted = true,
            userExplicitlyRequested = true,
        ))
        assertFalse(shouldLaunchCalendarPermissionRequest(
            CalendarPermissionState.GRANTED,
            consentGranted = true,
            userExplicitlyRequested = true,
        ))
        assertTrue(shouldLaunchCalendarPermissionRequest(
            CalendarPermissionState.DENIED,
            consentGranted = true,
            userExplicitlyRequested = true,
        ))
    }

    @Test
    fun calendarProjectionKeepsRequestedFieldsAndRemovesAccountIdentifiers() {
        val accountSecret = "calendar.owner@example.invalid"
        val row = CalendarDescriptor(
            id = 8,
            displayName = "Personal calendar",
            accountName = accountSecret,
            accountType = "private.account.type",
            ownerAccount = "owner-secret@example.invalid",
            accessLevel = 600,
            visible = true,
            syncEvents = true,
            primary = false,
            timezone = "Europe/Paris",
            color = 0x112233,
        ).toDiagnosticRow()

        assertEquals("Personal calendar", row.displayName)
        assertEquals("Compte expurgé", row.accountLabel)
        assertEquals("Éditeur", row.accessLabel)
        assertEquals("Visible", row.visibilityLabel)
        assertFalse(row.toString().contains(accountSecret))
        assertFalse(row.toString().contains("private.account.type"))
        assertFalse(row.toString().contains("owner-secret@example.invalid"))
    }

    @Test
    fun emptyNamesAndHiddenVisibilityHaveSafeLabels() {
        val row = CalendarDescriptor(
            id = 9,
            displayName = "",
            accountName = null,
            accountType = null,
            ownerAccount = null,
            accessLevel = 100,
            visible = false,
            syncEvents = false,
            primary = false,
            timezone = null,
            color = null,
        ).toDiagnosticRow()

        assertEquals("Calendrier sans nom", row.displayName)
        assertEquals("Disponibilités uniquement", row.accessLabel)
        assertEquals("Masqué", row.visibilityLabel)
    }
}
