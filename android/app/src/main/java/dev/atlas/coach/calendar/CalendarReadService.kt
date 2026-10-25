package dev.atlas.coach.calendar

import java.net.URI

/** Pure policy layer over an injected read-only calendar provider. */
class CalendarReadService(
    private val provider: CalendarProvider,
    private val etarProbe: EtarProbe = EtarProbe(provider),
) {
    fun inspectCalendars(): CalendarCatalog {
        val (permission, permissionError) = readPermissionState()
        if (permission != CalendarPermissionState.GRANTED) {
            return CalendarCatalog(
                permission = permission,
                discoveredCalendarsCount = 0,
                readableCalendars = emptyList(),
                writableCalendars = emptyList(),
                providerAvailable = false,
                errors = listOfNotNull(permissionError).boundedDistinct(),
            )
        }

        return try {
            val discovered = provider.listCalendars()
            val readable = discovered.filter(::isReadable)
            CalendarCatalog(
                permission = permission,
                discoveredCalendarsCount = discovered.size,
                readableCalendars = readable,
                writableCalendars = readable.filter(::isWritable),
                providerAvailable = true,
                errors = emptyList(),
            )
        } catch (_: SecurityException) {
            CalendarCatalog(
                permission = CalendarPermissionState.DENIED,
                discoveredCalendarsCount = 0,
                readableCalendars = emptyList(),
                writableCalendars = emptyList(),
                providerAvailable = false,
                errors = listOf(CalendarProbeError.CALENDAR_ACCESS_DENIED),
            )
        } catch (_: Exception) {
            CalendarCatalog(
                permission = permission,
                discoveredCalendarsCount = 0,
                readableCalendars = emptyList(),
                writableCalendars = emptyList(),
                providerAvailable = false,
                errors = listOf(CalendarProbeError.CALENDAR_READ_FAILED),
            )
        }
    }

    fun probe(): CalendarProbeReport {
        val catalog = inspectCalendars()
        val etar = etarProbe.probe()
        val errors = (catalog.errors + etar.errors).boundedDistinct()
        return CalendarProbeReport(
            permission = catalog.permission,
            calendarsCount = catalog.discoveredCalendarsCount,
            readableCalendarsCount = catalog.readableCalendars.size,
            writableCalendarsCount = catalog.writableCalendars.size,
            etarInstalled = etar.installed,
            etarPackagesChecked = etar.packagesChecked,
            providerAvailable = catalog.providerAvailable,
            errors = errors,
        )
    }

    fun queryEvents(
        calendarIds: Set<Long>,
        startMillis: Long,
        endMillis: Long,
    ): CalendarEventQueryResult {
        require(startMillis < endMillis) { "startMillis must be before endMillis" }
        val catalog = inspectCalendars()
        if (catalog.permission != CalendarPermissionState.GRANTED || !catalog.providerAvailable) {
            return CalendarEventQueryResult(
                permission = catalog.permission,
                events = emptyList(),
                providerAvailable = catalog.providerAvailable,
                errors = catalog.errors,
            )
        }

        val readableIds = catalog.readableCalendars.asSequence()
            .map(CalendarDescriptor::id)
            .filter(calendarIds::contains)
            .toSet()
        if (readableIds.isEmpty()) {
            return CalendarEventQueryResult(
                permission = catalog.permission,
                events = emptyList(),
                providerAvailable = true,
                errors = emptyList(),
            )
        }

        return try {
            val events = provider.queryEvents(readableIds, startMillis, endMillis)
                .asSequence()
                .filter { it.calendarId in readableIds }
                .map(::sanitizeAndClassify)
                .toList()
            CalendarEventQueryResult(
                permission = catalog.permission,
                events = events,
                providerAvailable = true,
                errors = emptyList(),
            )
        } catch (_: SecurityException) {
            CalendarEventQueryResult(
                permission = CalendarPermissionState.DENIED,
                events = emptyList(),
                providerAvailable = false,
                errors = listOf(CalendarProbeError.CALENDAR_ACCESS_DENIED),
            )
        } catch (_: Exception) {
            CalendarEventQueryResult(
                permission = catalog.permission,
                events = emptyList(),
                providerAvailable = false,
                errors = listOf(CalendarProbeError.EVENT_QUERY_FAILED),
            )
        }
    }

    fun toBusyWindows(events: Iterable<CalendarEventSnapshot>): List<BusyWindow> = events.mapNotNull { event ->
        if (event.beginMillis >= event.endMillis) return@mapNotNull null
        BusyWindow(
            beginMillis = event.beginMillis,
            endMillis = event.endMillis,
            allDay = event.allDay,
            eventId = event.eventId,
            calendarId = event.calendarId,
        )
    }

    /** Returns one conflict per overlapping event and candidate window. Touching edges do not overlap. */
    fun findConflicts(
        events: Iterable<CalendarEventSnapshot>,
        candidateWindows: List<BusyWindow>,
    ): List<EventConflict> {
        val eventWindows = toBusyWindows(events)
        return buildList {
            eventWindows.forEach { event ->
                candidateWindows.forEachIndexed { index, candidate ->
                    val overlapBegin = maxOf(event.beginMillis, candidate.beginMillis)
                    val overlapEnd = minOf(event.endMillis, candidate.endMillis)
                    if (overlapBegin < overlapEnd) {
                        add(
                            EventConflict(
                                eventId = requireNotNull(event.eventId),
                                calendarId = requireNotNull(event.calendarId),
                                windowIndex = index,
                                overlapBeginMillis = overlapBegin,
                                overlapEndMillis = overlapEnd,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun readPermissionState(): Pair<CalendarPermissionState, CalendarProbeError?> = try {
        provider.permissionState() to null
    } catch (_: SecurityException) {
        CalendarPermissionState.DENIED to CalendarProbeError.CALENDAR_ACCESS_DENIED
    } catch (_: Exception) {
        CalendarPermissionState.UNKNOWN to CalendarProbeError.PERMISSION_CHECK_FAILED
    }

    private fun isReadable(calendar: CalendarDescriptor): Boolean =
        calendar.visible && calendar.accessLevel >= CALENDAR_ACCESS_READ

    private fun isWritable(calendar: CalendarDescriptor): Boolean =
        calendar.accessLevel >= CALENDAR_ACCESS_CONTRIBUTOR

    private fun sanitizeAndClassify(event: CalendarEventSnapshot): CalendarEventSnapshot {
        val packageMarker = event.customAppPackage?.takeIf(String::isNotBlank)
        val uriMarker = event.customAppUri?.takeIf(String::isNotBlank)
        val atlasUri = when {
            !event.markerMetadataAvailable -> null
            packageMarker == null && uriMarker == null -> null
            packageMarker == ATLAS_PACKAGE && isValidAtlasUri(uriMarker) -> uriMarker
            else -> null
        }
        val provenance = when {
            !event.markerMetadataAvailable -> CalendarEventProvenance.AMBIGUOUS
            packageMarker == null && uriMarker == null -> CalendarEventProvenance.FOREIGN
            atlasUri != null -> CalendarEventProvenance.ATLAS_OWNED
            else -> CalendarEventProvenance.AMBIGUOUS
        }
        return event.copy(
            provenance = provenance,
            atlasUri = atlasUri,
            customAppPackage = null,
            customAppUri = null,
            markerMetadataAvailable = true,
        )
    }

    private fun isValidAtlasUri(value: String?): Boolean {
        if (value == null || !value.startsWith(ATLAS_URI_PREFIX)) return false
        return try {
            val uri = URI(value)
            val segments = uri.path.orEmpty().split('/').filter(String::isNotEmpty)
            uri.scheme == "atlas" && uri.host == "plan" && uri.query == null && uri.fragment == null &&
                segments.size == 3 && segments[1] == "activity" &&
                segments[0].isNotBlank() && segments[2].isNotBlank()
        } catch (_: Exception) {
            false
        }
    }
}
