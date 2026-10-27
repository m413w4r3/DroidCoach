package dev.atlas.coach.memory

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Safe, non-content metadata exposed while a corrupt document awaits an explicit recovery. */
data class MemoryQuarantineState(
    val quarantinedAt: Instant,
    val copyAvailable: Boolean,
    val quarantineFileName: String?,
)

/**
 * One-document durable memory store. If the existing document cannot be read,
 * its original bytes are retained and copied beside it before any operation is
 * allowed. Starting with an empty document then requires [recoverByStartingEmpty].
 */
class FileMemoryStore(
    private val file: File,
    private val clock: MemoryClock = SystemMemoryClock,
    private val retentionPolicy: MemoryRetentionPolicy = MemoryRetentionPolicy(),
    private val codec: MemoryJsonCodec = MemoryJsonCodec(),
) : MemoryStore {
    private val lock = Any()
    private var delegate: InMemoryMemoryStore? = null
    private var quarantineCopy: File? = null

    @Volatile
    var quarantineState: MemoryQuarantineState? = null
        private set

    init {
        loadOrQuarantine()
    }

    override fun add(fact: MemoryFact): MemoryFact = activeStore().add(fact)

    override fun get(factId: String): MemoryFact? = activeStore().get(factId)

    override fun list(includeRevoked: Boolean): List<MemoryFact> = activeStore().list(includeRevoked)

    override fun confirm(factId: String, targetCategory: MemoryCategory?): MemoryFact =
        activeStore().confirm(factId, targetCategory)

    override fun correct(factId: String, statement: String): MemoryFact =
        activeStore().correct(factId, statement)

    override fun delete(factId: String): Boolean = activeStore().delete(factId)

    override fun revoke(factId: String): MemoryFact = activeStore().revoke(factId)

    override fun purgeExpired(now: Instant?): Int = activeStore().purgeExpired(now)

    override fun markUsed(factId: String): MemoryFact = activeStore().markUsed(factId)

    /**
     * Explicitly replace a quarantined document with an empty versioned store.
     * The unreadable original is copied first and that copy remains beside the
     * replacement. Returns false when the store is not quarantined.
     */
    fun recoverByStartingEmpty(): Boolean = synchronized(lock) {
        val state = quarantineState ?: return@synchronized false
        val preservedCopy = quarantineCopy?.takeIf(File::isFile)
            ?: runCatching { copyToQuarantine(state.quarantinedAt) }.getOrNull()
        if (preservedCopy == null) {
            quarantineCopy = null
            quarantineState = state.copy(copyAvailable = false, quarantineFileName = null)
            return@synchronized false
        }

        val backend = AtomicFileMemoryBackend(file)
        backend.write(codec.encode(emptyList()))
        val recovered = InMemoryMemoryStore(
            clock = clock,
            retentionPolicy = retentionPolicy,
            backend = backend,
            codec = codec,
        )
        delegate = recovered
        quarantineCopy = preservedCopy
        quarantineState = null
        true
    }

    private fun activeStore(): InMemoryMemoryStore = synchronized(lock) {
        delegate ?: throw IllegalStateException(
            "Memory storage is quarantined; explicit recovery is required",
        )
    }

    private fun loadOrQuarantine() = synchronized(lock) {
        try {
            delegate = InMemoryMemoryStore(
                clock = clock,
                retentionPolicy = retentionPolicy,
                backend = AtomicFileMemoryBackend(file),
                codec = codec,
            )
        } catch (_: Exception) {
            delegate = null
            val quarantinedAt = clock.now()
            quarantineCopy = runCatching { copyToQuarantine(quarantinedAt) }.getOrNull()
            val copy = quarantineCopy
            quarantineState = MemoryQuarantineState(
                quarantinedAt = quarantinedAt,
                copyAvailable = copy?.isFile == true,
                quarantineFileName = copy?.name,
            )
        }
    }

    /** Copy the raw file, preserving the original and syncing the copy before rename. */
    private fun copyToQuarantine(at: Instant): File {
        if (!file.isFile) throw IOException("Unreadable memory source is unavailable")
        val absolute = file.absoluteFile
        val parent = absolute.parentFile ?: throw IOException("Memory directory is unavailable")
        val target = nextQuarantineTarget(parent, absolute.name, at)
        val temporary = File.createTempFile("memory-quarantine-", ".tmp", parent)
        try {
            Files.copy(absolute.toPath(), temporary.toPath(), REPLACE_EXISTING)
            FileOutputStream(temporary, true).use { output -> output.fd.sync() }
            Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE)
            return target
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun nextQuarantineTarget(parent: File, name: String, at: Instant): File {
        val timestamp = QUARANTINE_TIMESTAMP.format(at)
        for (suffix in 0..MAX_QUARANTINE_COLLISIONS) {
            val suffixText = if (suffix == 0) "" else "-$suffix"
            val candidate = File(parent, "$name.quarantine-$timestamp$suffixText")
            if (!candidate.exists()) return candidate
        }
        throw IOException("Unable to create a unique memory quarantine copy")
    }

    private class AtomicFileMemoryBackend(private val file: File) : MemoryBackend {
        override fun read(): String? {
            if (!file.exists()) return null
            if (!file.isFile) throw IOException("Memory storage path is not a file")
            return file.readText(StandardCharsets.UTF_8)
        }

        override fun write(value: String) {
            val absolute = file.absoluteFile
            val parent = absolute.parentFile ?: throw IOException("Memory directory is unavailable")
            if (!parent.exists() && !parent.mkdirs()) {
                throw IOException("Unable to create memory storage directory")
            }
            val temporary = File.createTempFile("${absolute.name.take(24)}-", ".tmp", parent)
            try {
                FileOutputStream(temporary).use { output ->
                    output.write(value.toByteArray(StandardCharsets.UTF_8))
                    output.fd.sync()
                }
                Files.move(temporary.toPath(), absolute.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } finally {
                if (temporary.exists()) temporary.delete()
            }
        }
    }

    private companion object {
        private const val MAX_QUARANTINE_COLLISIONS = 99
        private val QUARANTINE_TIMESTAMP = DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmss.SSS'Z'")
            .withZone(ZoneOffset.UTC)
    }
}
