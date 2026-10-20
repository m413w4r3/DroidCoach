package dev.atlas.coach.data

/** Result of one local state write. [NotWritable] means recovery deliberately forbids overwriting it. */
sealed interface PersistResult {
    data object Stored : PersistResult
    data object NotWritable : PersistResult
    data class Failed(val reason: PersistFailureKind) : PersistResult
}

enum class PersistFailureKind {
    SERIALIZATION,
    STORAGE_IO,
    INVALID_STATE,
    UNKNOWN,
}

/** Deliberately carries a stable code only; exception messages may contain private state. */
class PersistWriteException(val reason: PersistFailureKind) : RuntimeException()
