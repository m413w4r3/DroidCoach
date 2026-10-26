package dev.atlas.coach.calendar.write

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Migration seam shared by the journal and link records. Implementations return a record using the
 * current schema version; the default accepts only the current format and leaves unknown versions
 * untouched on disk so a future build can migrate them.
 */
fun interface CalendarWriteRecordMigration {
    fun migrate(fromVersion: Int, record: JSONObject): JSONObject
}

internal object CalendarWriteFileStorage {
    private val directoryLocks = ConcurrentHashMap<String, Any>()

    fun lockFor(directory: File): Any {
        val key = directory.canonicalFile.toPath().toAbsolutePath().normalize().toString()
        return directoryLocks.computeIfAbsent(key) { Any() }
    }

    fun ensureDirectory(directory: File) {
        Files.createDirectories(directory.toPath())
        require(directory.isDirectory) { "calendar write storage path is not a directory" }
    }

    fun writeAtomically(directory: File, fileName: String, json: String) {
        require(fileName.matches(Regex("[A-Za-z0-9._-]+\\.json"))) { "invalid record filename" }
        ensureDirectory(directory)
        val target = directory.toPath().resolve(fileName)
        val temporary = Files.createTempFile(directory.toPath(), "$fileName.", ".tmp")
        try {
            FileOutputStream(temporary.toFile()).use { output ->
                output.write(json.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(
                temporary,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            // Directory fsync is supported on Linux/Android filesystems but not every host JVM.
            runCatching {
                FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun jsonRecords(directory: File): List<Pair<String, String>> {
        ensureDirectory(directory)
        return Files.list(directory.toPath()).use { paths ->
            paths.iterator().asSequence()
                .filter { path -> path.fileName.toString().endsWith(".json") }
                .sortedBy { path -> path.fileName.toString() }
                .map { path ->
                    path.fileName.toString() to Files.newInputStream(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
                .toList()
        }
    }

    fun decodeCurrent(
        raw: String,
        currentVersion: Int,
        migration: CalendarWriteRecordMigration,
    ): JSONObject {
        val parsed = JSONObject(raw)
        val version = parsed.getInt("schema_version")
        val current = if (version == currentVersion) parsed else migration.migrate(version, parsed)
        require(current.getInt("schema_version") == currentVersion) { "migration did not produce current schema" }
        return current
    }

    fun migrationForCurrentVersion(currentVersion: Int): CalendarWriteRecordMigration =
        CalendarWriteRecordMigration { fromVersion, record ->
            require(fromVersion == currentVersion) { "unsupported calendar write record version" }
            record
        }
}
