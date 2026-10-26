package dev.atlas.coach.app

import dev.atlas.coach.calendar.CalendarPermissionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarPermissionFlowTest {
    @Test
    fun readRequestRequiresConsentAndAnExplicitUserAction() {
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
        assertTrue(shouldLaunchCalendarPermissionRequest(
            CalendarPermissionState.DENIED,
            consentGranted = true,
            userExplicitlyRequested = true,
        ))
    }

    @Test
    fun writeRequestWaitsForReadPermissionAndBothIndependentConsents() {
        assertFalse(shouldLaunchCalendarWritePermissionRequest(
            readPermission = CalendarPermissionState.DENIED,
            writePermission = CalendarPermissionState.DENIED,
            readConsentGranted = true,
            writeConsentGranted = true,
            userExplicitlyRequested = true,
        ))
        assertFalse(shouldLaunchCalendarWritePermissionRequest(
            readPermission = CalendarPermissionState.GRANTED,
            writePermission = CalendarPermissionState.DENIED,
            readConsentGranted = false,
            writeConsentGranted = true,
            userExplicitlyRequested = true,
        ))
        assertFalse(shouldLaunchCalendarWritePermissionRequest(
            readPermission = CalendarPermissionState.GRANTED,
            writePermission = CalendarPermissionState.DENIED,
            readConsentGranted = true,
            writeConsentGranted = false,
            userExplicitlyRequested = true,
        ))
        assertFalse(shouldLaunchCalendarWritePermissionRequest(
            readPermission = CalendarPermissionState.GRANTED,
            writePermission = CalendarPermissionState.NOT_DECLARED,
            readConsentGranted = true,
            writeConsentGranted = true,
            userExplicitlyRequested = true,
        ))
        assertTrue(shouldLaunchCalendarWritePermissionRequest(
            readPermission = CalendarPermissionState.GRANTED,
            writePermission = CalendarPermissionState.DENIED,
            readConsentGranted = true,
            writeConsentGranted = true,
            userExplicitlyRequested = true,
        ))
    }
}
