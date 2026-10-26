package dev.atlas.coach.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarReadConsentTest {
    @Test
    fun missingAtlasConsentBlocksCalendarAndEventReadsEvenWhenAndroidPermissionIsGranted() {
        val provider = FakeCalendarProvider()
        val gatedService = ConsentGatedCalendarReadService(
            service = CalendarReadService(provider),
            consentStore = InMemoryCalendarReadConsentStore(),
        )

        assertNull(gatedService.inspectCalendars())
        assertNull(gatedService.queryEvents(setOf(1), 0, 100))
        assertEquals(0, provider.permissionChecks)
        assertEquals(0, provider.calendarReads)
        assertEquals(0, provider.eventReads)
    }

    @Test
    fun grantAllowsCatalogReadAndRevocationBlocksSubsequentReads() {
        val provider = FakeCalendarProvider()
        val consent = InMemoryCalendarReadConsentStore()
        val gatedService = ConsentGatedCalendarReadService(CalendarReadService(provider), consent)

        assertTrue(consent.grant())
        val catalog = gatedService.inspectCalendars()
        assertEquals(CalendarPermissionState.GRANTED, catalog?.permission)
        assertEquals(1, provider.calendarReads)

        assertTrue(consent.revoke())
        assertNull(gatedService.inspectCalendars())
        assertEquals(1, provider.calendarReads)
    }

    @Test
    fun revokedConsentAlsoBlocksProbe() {
        val provider = FakeCalendarProvider()
        val consent = InMemoryCalendarReadConsentStore().apply { grant() }
        val gatedService = ConsentGatedCalendarReadService(CalendarReadService(provider), consent)

        assertTrue(consent.revoke())

        assertNull(gatedService.probe())
        assertEquals(0, provider.permissionChecks)
        assertEquals(0, provider.calendarReads)
    }

    private class InMemoryCalendarReadConsentStore : CalendarReadConsentStore {
        private var granted = false

        override fun isGranted(): Boolean = granted

        override fun grant(): Boolean {
            granted = true
            return true
        }

        override fun revoke(): Boolean {
            granted = false
            return true
        }
    }

    private class FakeCalendarProvider : CalendarProvider {
        var permissionChecks = 0
        var calendarReads = 0
        var eventReads = 0

        override fun permissionState(): CalendarPermissionState {
            permissionChecks++
            return CalendarPermissionState.GRANTED
        }

        override fun listCalendars(): List<CalendarDescriptor> {
            calendarReads++
            return emptyList()
        }

        override fun queryEvents(
            calendarIds: Set<Long>,
            startMillis: Long,
            endMillis: Long,
        ): List<CalendarEventSnapshot> {
            eventReads++
            return emptyList()
        }

        override fun isPackageInstalled(packageName: String): Boolean = false
    }
}
