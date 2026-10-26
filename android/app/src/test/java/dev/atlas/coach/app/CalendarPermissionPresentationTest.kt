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
    fun redactsEmailUsedAsCalendarName() {
        val row = descriptor(displayName = "user@example.invalid")

        assertEquals("Calendrier expurgé", row.displayName)
        assertFalse(row.toString().contains("@"))
    }

    @Test
    fun redactsEmailEmbeddedInCalendarName() {
        val row = descriptor(displayName = "Calendrier user@example.invalid")

        assertEquals("Calendrier expurgé", row.displayName)
        assertFalse(row.toString().contains("user@example.invalid"))
    }

    @Test
    fun keepsNormalCalendarNameAndOmitsAccountIdentifiers() {
        val row = descriptor(
            displayName = "Personal calendar",
            accountName = "user@example.invalid",
            ownerAccount = "owner@example.invalid",
        )

        assertEquals("Personal calendar", row.displayName)
        assertEquals("Compte expurgé", row.accountLabel)
        assertFalse(row.toString().contains("user@example.invalid"))
        assertFalse(row.toString().contains("owner@example.invalid"))
        assertFalse(row.toString().contains("private.account.type"))
        assertFalse(row.toString().contains("@"))
    }

    @Test
    fun redactsCalendarNameMatchingAccountNameIgnoringCaseAndWhitespace() {
        val accountNameRow = descriptor(
            displayName = "  Shared-Account  ",
            accountName = "shared-account",
        )
        val ownerAccountRow = descriptor(
            displayName = "SHARED-OWNER",
            ownerAccount = " shared-owner ",
        )

        assertEquals("Calendrier expurgé", accountNameRow.displayName)
        assertEquals("Calendrier expurgé", ownerAccountRow.displayName)
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

    private fun descriptor(
        displayName: String,
        accountName: String? = null,
        ownerAccount: String? = null,
    ) = CalendarDescriptor(
        id = 10,
        displayName = displayName,
        accountName = accountName,
        accountType = "private.account.type",
        ownerAccount = ownerAccount,
        accessLevel = 600,
        visible = true,
        syncEvents = true,
        primary = false,
        timezone = "Europe/Paris",
        color = 0x112233,
    ).toDiagnosticRow()
}
