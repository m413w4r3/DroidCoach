package dev.atlas.coach.sync.agent.inbox.v4

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Small state boundary so repositories and restart behavior can be exercised without Android APIs. */
interface InboxV4Storage {
    fun read(): String?
    fun write(serializedState: String)

    /** Copies the exact raw payload to quarantine before state is reset. */
    fun quarantine(raw: String, reason: String): String?
}

class InMemoryInboxV4Storage(initial: String? = null) : InboxV4Storage {
    @Volatile
    var raw: String? = initial
        private set

    private val quarantined = mutableListOf<Pair<String, String>>()

    @Synchronized
    override fun read(): String? = raw

    @Synchronized
    override fun write(serializedState: String) {
        raw = serializedState
    }

    @Synchronized
    override fun quarantine(raw: String, reason: String): String {
        quarantined += raw to reason
        return "memory-quarantine-${quarantined.size}"
    }

    @Synchronized
    fun quarantinedPayloads(): List<Pair<String, String>> = quarantined.toList()
}

/** File-backed, atomic snapshot storage for an app-owned file path. */
class FileInboxV4Storage(private val file: File) : InboxV4Storage {
    override fun read(): String? = if (file.isFile) file.readText(StandardCharsets.UTF_8) else null

    @Synchronized
    override fun write(serializedState: String) {
        file.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) error("Could not create inbox directory")
        }
        val temporary = File(file.parentFile ?: File("."), "${file.name}.tmp")
        FileOutputStream(temporary).use { stream ->
            stream.write(serializedState.toByteArray(StandardCharsets.UTF_8))
            stream.fd.sync()
        }
        val moved = runCatching {
            Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }.recoverCatching {
            Files.move(temporary.toPath(), file.toPath(), REPLACE_EXISTING)
        }.isSuccess
        if (!moved) {
            temporary.delete()
            error("Could not commit inbox state")
        }
    }

    @Synchronized
    override fun quarantine(raw: String, reason: String): String? = runCatching {
        val quarantineFile = File(file.parentFile ?: File("."), "${file.name}.quarantine")
        quarantineFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
        FileOutputStream(quarantineFile, true).use { stream ->
            stream.write(("reason=" + reason.replace('\n', ' ') + "\n" + raw + "\n---\n")
                .toByteArray(StandardCharsets.UTF_8))
            stream.fd.sync()
        }
        quarantineFile.absolutePath
    }.getOrNull()
}
