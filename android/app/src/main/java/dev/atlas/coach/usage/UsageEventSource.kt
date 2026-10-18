package dev.atlas.coach.usage

import dev.atlas.coach.rules.AppEventSimulator

/** Sources expose only foreground app package and event time; no screen or notification content exists here. */
interface UsageEventSource {
    fun query(cursor: UsageCursor, throughUtc: Long, maxRawEvents: Int): UsageEventBatch
}

/** Deterministic demonstration source backed by ATLAS's existing AppEventSimulator. */
class SimulatedUsageEventSource(
    private val simulator: AppEventSimulator,
) : UsageEventSource {
    private val timeline = mutableListOf<UsageSourceEvent>()

    @Synchronized
    fun simulateLaunches(
        packageName: String,
        count: Int,
        intervalSeconds: Long,
        firstTimestampUtc: Long,
    ): List<UsageSourceEvent> {
        val generated = simulator.generate(packageName, count, intervalSeconds, firstTimestampUtc)
            .map { UsageSourceEvent(it.eventId, it.packageName, it.timestampUtc) }
        timeline += generated
        timeline.sortBy(UsageSourceEvent::timestampUtc)
        if (timeline.size > UsageRetention.MAX_STORED_EVENTS) {
            timeline.subList(0, timeline.size - UsageRetention.MAX_STORED_EVENTS).clear()
        }
        return generated
    }

    @Synchronized
    override fun query(cursor: UsageCursor, throughUtc: Long, maxRawEvents: Int): UsageEventBatch {
        require(maxRawEvents > 0)
        val matching = timeline.asSequence()
            .filter { it.timestampUtc <= throughUtc }
            .filter {
                it.timestampUtc > cursor.timestampUtc ||
                    (it.timestampUtc == cursor.timestampUtc && it.eventId !in cursor.fingerprintsAtTimestamp)
            }
            .sortedBy(UsageSourceEvent::timestampUtc)
            .toList()
        val selected = matching.take(maxRawEvents)
        val hasMore = matching.size > selected.size
        val next = if (hasMore && selected.isNotEmpty()) {
            val timestamp = selected.last().timestampUtc
            UsageCursor(timestamp, selected.filter { it.timestampUtc == timestamp }.mapTo(mutableSetOf()) { it.eventId })
        } else {
            UsageCursor(throughUtc)
        }
        return UsageEventBatch(selected, next, hasMore)
    }
}

interface ObservationConsentStore {
    fun currentConsent(): ObservationConsent
    fun saveConsent(consent: ObservationConsent)
}

interface UsageCursorStore {
    fun currentCursor(): UsageCursor?
    fun saveCursor(cursor: UsageCursor)
    fun clearCursor()
}

interface UsageObserverStateStore {
    fun currentSnapshot(): UsageObserverSnapshot
    fun saveSnapshot(snapshot: UsageObserverSnapshot)
    fun clearSnapshot()
}

interface ObservationRecordStore {
    fun allRecords(): List<ObservationRecord>
    fun appendRecord(record: ObservationRecord)
    fun keepOnlyPackages(packageNames: Set<String>)
    fun pruneBefore(cutoffUtc: Long)
    fun clearRecords()
}

class InMemoryObservationConsentStore(initial: ObservationConsent = ObservationConsent.REVOKED) : ObservationConsentStore {
    private var value = initial
    @Synchronized override fun currentConsent() = value
    @Synchronized override fun saveConsent(consent: ObservationConsent) { value = consent }
}

class InMemoryUsageCursorStore : UsageCursorStore {
    private var value: UsageCursor? = null
    @Synchronized override fun currentCursor() = value
    @Synchronized override fun saveCursor(cursor: UsageCursor) { value = cursor }
    @Synchronized override fun clearCursor() { value = null }
}

class InMemoryUsageObserverStateStore : UsageObserverStateStore {
    private var value = UsageObserverSnapshot()
    @Synchronized override fun currentSnapshot() = value
    @Synchronized override fun saveSnapshot(snapshot: UsageObserverSnapshot) { value = snapshot }
    @Synchronized override fun clearSnapshot() { value = UsageObserverSnapshot() }
}

class InMemoryObservationRecordStore : ObservationRecordStore {
    private val records = mutableListOf<ObservationRecord>()
    @Synchronized override fun allRecords() = records.toList()
    @Synchronized override fun appendRecord(record: ObservationRecord) {
        if (records.none { it.eventId == record.eventId }) records += record
        if (records.size > UsageRetention.MAX_STORED_EVENTS) {
            records.subList(0, records.size - UsageRetention.MAX_STORED_EVENTS).clear()
        }
    }
    @Synchronized override fun keepOnlyPackages(packageNames: Set<String>) {
        records.removeAll { it.packageName !in packageNames }
    }
    @Synchronized override fun pruneBefore(cutoffUtc: Long) {
        records.removeAll { it.timestampUtc < cutoffUtc }
    }
    @Synchronized override fun clearRecords() = records.clear()
}

fun interface UsageAccessStatus {
    fun isAllowed(): Boolean
}

/** An extension point for optional observers. Implementations remain disabled until explicitly enabled. */
interface ObservationProvider {
    val capabilityFlag: String
    val enabledByDefault: Boolean
    fun eventSourceOrNull(): UsageEventSource?
}

/** Deliberate placeholder for Reef Accessibility/Mindful Launch; this capability is not implemented. */
class UnsupportedProvider(
    override val capabilityFlag: String = "observation.accessibility",
    val reason: String = "Unsupported: no AccessibilityService or Mindful Launch interception in R3.",
) : ObservationProvider {
    override val enabledByDefault = false
    override fun eventSourceOrNull(): UsageEventSource? = null
}
