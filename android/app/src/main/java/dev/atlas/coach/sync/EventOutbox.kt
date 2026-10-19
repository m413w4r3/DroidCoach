package dev.atlas.coach.sync

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class QueuedEvent(
    val event: SyncEvent,
    val attempts: Int = 0,
    val nextAttemptAtUtcMillis: Long = 0L,
)

interface OutboxStorage {
    fun read(): String?
    fun write(value: String)
}

interface EventOutbox {
    fun enqueue(event: SyncEvent, consent: SyncConsent): Boolean
    fun pending(nowUtcMillis: Long): List<QueuedEvent>
    fun acknowledge(clientEventIds: Set<String>)
    fun defer(clientEventId: String, nextAttemptAtUtcMillis: Long)
    fun discardDisallowed(consent: SyncConsent): Int
    fun clear(): Int
    fun makeDueNow(): Int
    fun size(): Int
    fun nextAttemptAtUtcMillis(): Long?
}

/** Private app-files storage with fsync + atomic rename so a crash cannot truncate the queue. */
class FileOutboxStorage(private val file: File) : OutboxStorage {
    override fun read(): String? = if (file.isFile) file.readText(StandardCharsets.UTF_8) else null

    @Synchronized
    override fun write(value: String) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile ?: File("."), "${file.name}.tmp")
        FileOutputStream(temporary).use { stream ->
            stream.write(value.toByteArray(StandardCharsets.UTF_8))
            stream.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: Exception) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

/** Bounded durable outbox. Consent is checked both when enqueuing and immediately before sync. */
class DurableEventOutbox(private val storage: OutboxStorage) : EventOutbox {
    private val entries = linkedMapOf<String, QueuedEvent>()

    init {
        decode(storage.read())
    }

    @Synchronized
    override fun enqueue(event: SyncEvent, consent: SyncConsent): Boolean {
        if (!consent.allows(event)) return false
        val existing = entries[event.clientEventId]
        if (existing != null) {
            require(existing.event == event) { "clientEventId reused for a different event" }
            return false
        }
        entries[event.clientEventId] = QueuedEvent(event)
        persist()
        return true
    }

    @Synchronized
    override fun pending(nowUtcMillis: Long): List<QueuedEvent> = entries.values
        .filter { it.nextAttemptAtUtcMillis <= nowUtcMillis }
        .sortedWith(compareBy<QueuedEvent> { it.event.occurredAtUtcMillis }.thenBy { it.event.clientEventId })

    @Synchronized
    override fun acknowledge(clientEventIds: Set<String>) {
        if (entries.keys.removeAll(clientEventIds)) persist()
    }

    @Synchronized
    override fun defer(clientEventId: String, nextAttemptAtUtcMillis: Long) {
        val old = entries[clientEventId] ?: return
        entries[clientEventId] = old.copy(
            attempts = old.attempts + 1,
            nextAttemptAtUtcMillis = nextAttemptAtUtcMillis,
        )
        persist()
    }

    @Synchronized
    override fun discardDisallowed(consent: SyncConsent): Int {
        val before = entries.size
        entries.entries.removeAll { (_, queued) -> !consent.allows(queued.event) }
        val removed = before - entries.size
        if (removed > 0) persist()
        return removed
    }

    @Synchronized
    override fun clear(): Int {
        val removed = entries.size
        if (removed > 0) {
            entries.clear()
            persist()
        }
        return removed
    }

    @Synchronized
    override fun makeDueNow(): Int {
        val ready = entries.values.count { it.nextAttemptAtUtcMillis > 0L }
        if (ready > 0) {
            entries.replaceAll { _, queued -> queued.copy(nextAttemptAtUtcMillis = 0L) }
            persist()
        }
        return ready
    }

    @Synchronized
    override fun size(): Int = entries.size

    @Synchronized
    override fun nextAttemptAtUtcMillis(): Long? = entries.values.minOfOrNull(QueuedEvent::nextAttemptAtUtcMillis)

    @Synchronized
    fun all(): List<QueuedEvent> = entries.values.toList()

    private fun decode(raw: String?) {
        if (raw.isNullOrBlank()) return
        val root = JSONObject(raw)
        require(root.getInt("schema_version") == OUTBOX_SCHEMA_VERSION) { "Unknown outbox schema" }
        val items = root.getJSONArray("events")
        for (index in 0 until items.length()) {
            val row = items.getJSONObject(index)
            val event = syncEventFromStoredJson(row.getJSONObject("event"))
            entries[event.clientEventId] = QueuedEvent(
                event = event,
                attempts = row.optInt("attempts", 0),
                nextAttemptAtUtcMillis = row.optLong("next_attempt_at_utc_ms", 0L),
            )
        }
    }

    private fun persist() {
        val events = JSONArray()
        entries.values.forEach { queued ->
            events.put(
                JSONObject()
                    .put("event", queued.event.toStoredJson())
                    .put("attempts", queued.attempts)
                    .put("next_attempt_at_utc_ms", queued.nextAttemptAtUtcMillis),
            )
        }
        storage.write(JSONObject().put("schema_version", OUTBOX_SCHEMA_VERSION).put("events", events).toString())
    }

    companion object {
        const val OUTBOX_SCHEMA_VERSION = 1
    }
}

class ExponentialBackoff(
    private val baseDelayMillis: Long = 1_000L,
    private val maxDelayMillis: Long = 6 * 60 * 60 * 1_000L,
    private val jitter: () -> Double = { Math.random() },
) {
    init {
        require(baseDelayMillis > 0 && maxDelayMillis >= baseDelayMillis)
    }

    fun delayMillis(failedAttempt: Int): Long {
        require(failedAttempt >= 1)
        val exponent = (failedAttempt - 1).coerceAtMost(62)
        val exponential = (baseDelayMillis.toDouble() * Math.pow(2.0, exponent.toDouble()))
            .coerceAtMost(maxDelayMillis.toDouble())
        val jitterFactor = 0.5 + jitter().coerceIn(0.0, 1.0)
        return (exponential * jitterFactor).toLong().coerceIn(1L, maxDelayMillis)
    }
}
