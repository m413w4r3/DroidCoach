package dev.atlas.coach.data

import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.TimeUnit

/**
 * Serializes consent and mandate mutations with the relay's final payload write.
 *
 * The relay acquires this gate around the pre-dispatch check, payload construction and the
 * WebSocket write. Consent/revocation mutations acquire the same gate before changing the
 * authoritative state, so a withdrawal cannot interleave between the check and the write.
 * Reentrant so a mutation called from a gated path (or the pre-dispatch check itself) is safe.
 */
class AgentConsentGate {
    private val lock = ReentrantLock()

    /** Test seam for observing a thread immediately before it waits for the gate. */
    @Volatile
    internal var beforeLockAttemptForTest: (() -> Unit)? = null

    fun <T> withLock(block: () -> T): T {
        beforeLockAttemptForTest?.invoke()
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    /** Attempts the same reentrant consent barrier without outliving a caller's deadline. */
    fun <T> tryWithLock(timeoutMillis: Long, block: () -> T): T? {
        require(timeoutMillis >= 0L) { "timeoutMillis must not be negative" }
        beforeLockAttemptForTest?.invoke()
        val acquired = try {
            lock.tryLock(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!acquired) return null
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
