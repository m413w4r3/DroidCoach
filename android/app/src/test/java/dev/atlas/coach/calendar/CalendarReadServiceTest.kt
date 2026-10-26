package dev.atlas.coach.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class CalendarReadServiceTest {
    @Test
    fun notDeclaredPermissionSkipsCalendarReads() {
        val provider = FakeCalendarProvider(permission = CalendarPermissionState.NOT_DECLARED)

        val report = CalendarReadService(provider).probe()

        assertEquals(CalendarPermissionState.NOT_DECLARED, report.permission)
        assertFalse(report.providerAvailable)
        assertEquals(0, provider.listCalls)
    }

    @Test
    fun deniedPermissionSkipsCalendarReads() {
        val provider = FakeCalendarProvider(permission = CalendarPermissionState.DENIED)

        val catalog = CalendarReadService(provider).inspectCalendars()

        assertEquals(CalendarPermissionState.DENIED, catalog.permission)
        assertFalse(catalog.providerAvailable)
        assertEquals(0, provider.listCalls)
    }

    @Test
    fun unknownPermissionDoesNotProbeCalendarData() {
        val provider = FakeCalendarProvider(permission = CalendarPermissionState.UNKNOWN)

        val catalog = CalendarReadService(provider).inspectCalendars()

        assertEquals(CalendarPermissionState.UNKNOWN, catalog.permission)
        assertEquals(0, provider.listCalls)
    }

    @Test
    fun securityExceptionMapsToDeniedWithoutLeakingMessage() {
        val provider = FakeCalendarProvider().apply {
            listError = SecurityException("PRIVATE_SENTINEL")
        }

        val catalog = CalendarReadService(provider).inspectCalendars()

        assertEquals(CalendarPermissionState.DENIED, catalog.permission)
        assertEquals(listOf(CalendarProbeError.CALENDAR_ACCESS_DENIED), catalog.errors)
        assertFalse(catalog.toString().contains("PRIVATE_SENTINEL"))
    }

    @Test
    fun grantedPermissionReportsCalendarCounts() {
        val provider = FakeCalendarProvider(
            calendars = listOf(calendar(id = 1, accessLevel = 200), calendar(id = 2)),
        )

        val report = CalendarReadService(provider).probe()

        assertEquals(CalendarPermissionState.GRANTED, report.permission)
        assertEquals(2, report.calendarsCount)
        assertEquals(2, report.readableCalendarsCount)
        assertEquals(1, report.writableCalendarsCount)
        assertTrue(report.providerAvailable)
    }

    @Test
    fun emptyCalendarListIsAvailableAndEmpty() {
        val catalog = CalendarReadService(FakeCalendarProvider(calendars = emptyList())).inspectCalendars()

        assertTrue(catalog.providerAvailable)
        assertEquals(0, catalog.discoveredCalendarsCount)
        assertTrue(catalog.readableCalendars.isEmpty())
        assertTrue(catalog.writableCalendars.isEmpty())
    }

    @Test
    fun invisibleCalendarIsNotExposed() {
        val provider = FakeCalendarProvider(calendars = listOf(calendar(id = 1, visible = false)))

        val catalog = CalendarReadService(provider).inspectCalendars()

        assertEquals(1, catalog.discoveredCalendarsCount)
        assertTrue(catalog.readableCalendars.isEmpty())
    }

    @Test
    fun visibleButNonReadableCalendarIsNotExposed() {
        val provider = FakeCalendarProvider(calendars = listOf(calendar(id = 1, accessLevel = 100)))

        val catalog = CalendarReadService(provider).inspectCalendars()

        assertTrue(catalog.readableCalendars.isEmpty())
        assertTrue(catalog.writableCalendars.isEmpty())
    }

    @Test
    fun writableCalendarsAreExposedSeparately() {
        val provider = FakeCalendarProvider(
            calendars = listOf(
                calendar(id = 1, accessLevel = 200),
                calendar(id = 2, accessLevel = 500),
                calendar(id = 3, accessLevel = 800),
            ),
        )

        val catalog = CalendarReadService(provider).inspectCalendars()

        assertEquals(listOf(1L, 2L, 3L), catalog.readableCalendars.map(CalendarDescriptor::id))
        assertEquals(listOf(2L, 3L), catalog.writableCalendars.map(CalendarDescriptor::id))
    }

    @Test
    fun completeAtlasMarkersClassifyAsOwnedAndAreSanitized() {
        val uri = "atlas://plan/plan-id/activity/activity-id"
        val result = readOne(
            event(customAppPackage = ATLAS_PACKAGE, customAppUri = uri),
        )

        assertEquals(CalendarEventProvenance.ATLAS_OWNED, result.provenance)
        assertEquals(uri, result.atlasUri)
        assertNull(result.customAppPackage)
        assertNull(result.customAppUri)
    }

    @Test
    fun absentMarkersClassifyAsForeign() {
        val result = readOne(event())

        assertEquals(CalendarEventProvenance.FOREIGN, result.provenance)
        assertNull(result.atlasUri)
    }

    @Test
    fun partialOrInconsistentMarkersAreAmbiguous() {
        val partial = readOne(event(customAppPackage = ATLAS_PACKAGE))
        val inconsistent = readOne(
            event(customAppPackage = "org.example.calendar", customAppUri = "atlas://plan/p/activity/a"),
        )
        val malformedAtlasUri = readOne(
            event(customAppPackage = ATLAS_PACKAGE, customAppUri = "atlas://plan/p"),
        )

        assertEquals(CalendarEventProvenance.AMBIGUOUS, partial.provenance)
        assertEquals(CalendarEventProvenance.AMBIGUOUS, inconsistent.provenance)
        assertEquals(CalendarEventProvenance.AMBIGUOUS, malformedAtlasUri.provenance)
    }

    @Test
    fun eventWithoutReadableMarkerMetadataIsAmbiguous() {
        val result = readOne(event(markerMetadataAvailable = false))

        assertEquals(CalendarEventProvenance.AMBIGUOUS, result.provenance)
    }

    @Test
    fun eventQueryIsRestrictedToSelectedReadableCalendars() {
        val provider = FakeCalendarProvider(
            calendars = listOf(calendar(id = 1), calendar(id = 2, visible = false)),
            events = listOf(event(calendarId = 1), event(eventId = 12, calendarId = 2)),
        )

        val result = CalendarReadService(provider).queryEvents(setOf(1, 2), 0, 100)

        assertEquals(setOf(1L), provider.queriedCalendarIds)
        assertEquals(listOf(1L), result.events.map(CalendarEventSnapshot::calendarId))
    }

    @Test
    fun overlapProducesConflict() {
        val event = event(beginMillis = 10, endMillis = 30)
        val candidate = BusyWindow(beginMillis = 20, endMillis = 40)

        val conflicts = CalendarReadService(FakeCalendarProvider()).findConflicts(listOf(event), listOf(candidate))

        assertEquals(1, conflicts.size)
        assertEquals(20, conflicts.single().overlapBeginMillis)
        assertEquals(30, conflicts.single().overlapEndMillis)
    }

    @Test
    fun touchingWindowsDoNotConflict() {
        val event = event(beginMillis = 10, endMillis = 20)
        val candidate = BusyWindow(beginMillis = 20, endMillis = 30)

        assertTrue(CalendarReadService(FakeCalendarProvider()).findConflicts(listOf(event), listOf(candidate)).isEmpty())
    }

    @Test
    fun allDayEventBecomesAllDayBusyWindow() {
        val windows = CalendarReadService(FakeCalendarProvider()).toBusyWindows(
            listOf(event(beginMillis = 0, endMillis = 86_400_000, allDay = true)),
        )

        assertEquals(1, windows.size)
        assertTrue(windows.single().allDay)
        assertEquals(86_400_000, windows.single().endMillis)
    }

    @Test
    fun providerErrorsAreMappedToBoundedCodesWithoutMessages() {
        val provider = FakeCalendarProvider().apply {
            listError = IllegalStateException("PRIVATE_SENTINEL")
        }

        val report = CalendarReadService(provider).probe()

        assertEquals(listOf(CalendarProbeError.CALENDAR_READ_FAILED), report.errors)
        assertTrue(report.errors.size <= MAX_CALENDAR_PROBE_ERRORS)
        assertFalse(report.toString().contains("PRIVATE_SENTINEL"))
    }

    @Test
    fun etarProbeReportsConfiguredPackageAbsent() {
        val provider = FakeCalendarProvider()
        val probe = EtarProbe(provider, listOf("org.example.etar"))

        val result = probe.probe()

        assertFalse(result.installed)
        assertEquals(listOf("org.example.etar"), result.packagesChecked)
    }

    @Test
    fun etarProbeReportsConfiguredPackagePresent() {
        val provider = FakeCalendarProvider(installedPackages = setOf("org.example.etar"))
        val probe = EtarProbe(provider, listOf("org.example.etar", "org.example.other"))

        val result = probe.probe()

        assertTrue(result.installed)
        assertEquals(listOf("org.example.etar"), result.packagesChecked)
    }

    @Test
    fun defaultEtarPackageCandidatesIncludeReleaseAndDebugVariants() {
        assertEquals(listOf("ws.xsoh.etar", "ws.xsoh.etar.debug"), EtarProbe.DEFAULT_PACKAGE_CANDIDATES)
    }

    @Test
    fun defaultEtarProbeFindsDebugVariant() {
        val provider = FakeCalendarProvider(installedPackages = setOf("ws.xsoh.etar.debug"))

        val result = EtarProbe(provider).probe()

        assertTrue(result.installed)
        assertEquals(listOf("ws.xsoh.etar", "ws.xsoh.etar.debug"), result.packagesChecked)
    }

    @Test
    fun providerAndServiceExposeNoWriteMethods() {
        val publicApi = CalendarProvider::class.java.methods.toList() +
            CalendarReadService::class.java.methods.toList() +
            AndroidCalendarProvider::class.java.declaredMethods.filter { Modifier.isPublic(it.modifiers) }

        assertTrue(
            publicApi.none { method ->
                method.name.lowercase() in setOf("insert", "update", "delete", "applybatch")
            },
        )
    }

    private fun readOne(event: CalendarEventSnapshot): CalendarEventSnapshot {
        val provider = FakeCalendarProvider(events = listOf(event))
        return CalendarReadService(provider).queryEvents(setOf(1), 0, 100).events.single()
    }

    private fun calendar(
        id: Long,
        visible: Boolean = true,
        accessLevel: Int = 500,
    ) = CalendarDescriptor(
        id = id,
        displayName = "Calendar $id",
        accountName = null,
        accountType = null,
        ownerAccount = null,
        accessLevel = accessLevel,
        visible = visible,
        syncEvents = false,
        primary = false,
        timezone = "UTC",
        color = null,
    )

    private fun event(
        eventId: Long = 10,
        calendarId: Long = 1,
        beginMillis: Long = 10,
        endMillis: Long = 20,
        allDay: Boolean = false,
        customAppPackage: String? = null,
        customAppUri: String? = null,
        markerMetadataAvailable: Boolean = true,
    ) = CalendarEventSnapshot(
        eventId = eventId,
        calendarId = calendarId,
        title = "Event",
        beginMillis = beginMillis,
        endMillis = endMillis,
        allDay = allDay,
        eventTimezone = "UTC",
        rrule = null,
        status = null,
        customAppPackage = customAppPackage,
        customAppUri = customAppUri,
        markerMetadataAvailable = markerMetadataAvailable,
    )

    private class FakeCalendarProvider(
        private val permission: CalendarPermissionState = CalendarPermissionState.GRANTED,
        private val calendars: List<CalendarDescriptor> = listOf(defaultTestCalendar()),
        private val events: List<CalendarEventSnapshot> = emptyList(),
        private val installedPackages: Set<String> = emptySet(),
    ) : CalendarProvider {
        var listCalls = 0
        var listError: Exception? = null
        var queriedCalendarIds: Set<Long> = emptySet()

        override fun permissionState(): CalendarPermissionState = permission

        override fun listCalendars(): List<CalendarDescriptor> {
            listCalls++
            listError?.let { throw it }
            return calendars
        }

        override fun queryEvents(
            calendarIds: Set<Long>,
            startMillis: Long,
            endMillis: Long,
        ): List<CalendarEventSnapshot> {
            queriedCalendarIds = calendarIds
            return events.filter { it.calendarId in calendarIds }
        }

        override fun isPackageInstalled(packageName: String): Boolean = packageName in installedPackages
    }
}

private fun defaultTestCalendar() = CalendarDescriptor(
    id = 1,
    displayName = "Calendar 1",
    accountName = null,
    accountType = null,
    ownerAccount = null,
    accessLevel = 500,
    visible = true,
    syncEvents = false,
    primary = false,
    timezone = "UTC",
    color = null,
)
