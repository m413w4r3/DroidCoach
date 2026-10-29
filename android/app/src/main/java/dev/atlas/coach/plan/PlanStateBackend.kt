package dev.atlas.coach.plan

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class PlanQuarantineMetadata(
    val reason: String,
    val quarantinedAtUtcMillis: Long,
    val valueLength: Int,
    val sha256: String,
) {
    init {
        require(reason.isNotBlank())
        require(quarantinedAtUtcMillis >= 0L)
        require(valueLength >= 0)
        require(SHA256_PATTERN.matches(sha256))
    }
}

/** Storage boundary intentionally has no Android framework dependency. */
interface PlanStateBackend {
    /**
     * In-process read/modify/write lock shared by repositories addressing this document.
     * This does not coordinate independent processes. Backends with multiple instances for
     * one store must return the same lock object from each instance.
     */
    val transactionLock: Any get() = this

    fun read(): String?
    fun write(value: String)

    /** Preserve diagnostics only; implementations must never persist [rawValue]. */
    fun quarantine(rawValue: String, reason: String, atUtcMillis: Long): PlanQuarantineMetadata
}

/** Stores one plan document and diagnostic-only quarantine sidecars beside [file]. */
class FilePlanBackend(private val file: File) : PlanStateBackend {
    override val transactionLock: Any = FilePlanBackendLockRegistry.forFile(file)

    override fun read(): String? = if (file.isFile) file.readText(Charsets.UTF_8) else null

    override fun write(value: String) {
        writeAtomically(file, value)
    }

    override fun quarantine(rawValue: String, reason: String, atUtcMillis: Long): PlanQuarantineMetadata {
        val bytes = rawValue.toByteArray(StandardCharsets.UTF_8)
        val metadata = PlanQuarantineMetadata(
            reason = reason,
            quarantinedAtUtcMillis = atUtcMillis,
            valueLength = bytes.size,
            sha256 = sha256(bytes),
        )
        val sidecar = File(
            file.absoluteFile.parentFile ?: File("."),
            "${file.name}.quarantine-${atUtcMillis}-${UUID.randomUUID()}.json",
        )
        val json = JSONObject().apply {
            put("reason", metadata.reason)
            put("quarantined_at_utc_millis", metadata.quarantinedAtUtcMillis)
            put("value_length", metadata.valueLength)
            put("sha256", metadata.sha256)
        }.toString()
        writeAtomically(sidecar, json)
        return metadata
    }

    private fun writeAtomically(target: File, value: String) {
        val absolute = target.absoluteFile
        val parent = absolute.parentFile ?: File(".").absoluteFile
        check(parent.exists() || parent.mkdirs()) { "unable to create plan storage directory" }
        val temporary = File(parent, "${absolute.name}.tmp-${UUID.randomUUID()}")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(value.toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), absolute.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (error: AtomicMoveNotSupportedException) {
                throw IllegalStateException("atomic plan storage is not supported", error)
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private object FilePlanBackendLockRegistry {
    private val locks = ConcurrentHashMap<String, Any>()

    fun forFile(file: File): Any {
        val identity = runCatching { file.canonicalFile.toPath().normalize().toString() }
            .getOrElse { file.absoluteFile.toPath().normalize().toString() }
        return locks.computeIfAbsent(identity) { Any() }
    }
}
