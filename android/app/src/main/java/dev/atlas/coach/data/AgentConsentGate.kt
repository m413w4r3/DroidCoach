package dev.atlas.coach.data

import java.util.concurrent.locks.ReentrantLock

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
}
