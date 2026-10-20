package dev.atlas.coach.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RevocationBarrierTest {
    @Test
    fun successfulBlockSurvivesRecreationUntilDurableExplicitOptIn() {
        val storage = MemoryBarrierStorage()
        val barrier = RevocationBarrier(storage)
        assertFalse(barrier.isBlocked())
        assertTrue(barrier.block())
        assertTrue(RevocationBarrier(storage).isBlocked())

        assertFalse(barrier.clearAfterDurableOptIn(durablyStored = false))
        assertTrue(barrier.isBlocked())
        assertTrue(barrier.clearAfterDurableOptIn(durablyStored = true))
        assertFalse(RevocationBarrier(storage).isBlocked())
    }

    @Test
    fun categoryRevocationRequiresMatchingDurableCategoryOptIn() {
        val storage = MemoryBarrierStorage()
        val barrier = RevocationBarrier(storage)
        assertTrue(barrier.blockCategory("goals"))
        assertTrue(RevocationBarrier(storage).isBlocked())
        assertTrue(barrier.clearAfterDurableOptIn(durablyStored = true))
        assertTrue(barrier.isBlocked())
        assertTrue(barrier.clearCategoryAfterDurableOptIn("focus", durablyStored = true))
        assertTrue(barrier.isBlocked())
        assertTrue(barrier.clearCategoryAfterDurableOptIn("goals", durablyStored = true))
        assertFalse(RevocationBarrier(storage).isBlocked())
    }

    @Test
    fun failedMarkerWriteStillLatchesTheCurrentProcessFailClosed() {
        val storage = MemoryBarrierStorage().apply { failWrites = true }
        val barrier = RevocationBarrier(storage)

        assertFalse(barrier.block())
        assertTrue(barrier.isBlocked())
        assertFalse(barrier.clearAfterDurableOptIn(durablyStored = true))
        assertTrue(barrier.isBlocked())
    }

    private class MemoryBarrierStorage : RevocationBarrierStorage {
        var state = RevocationBarrierState()
        var failWrites = false
        override fun readState(): RevocationBarrierState = state
        override fun writeState(state: RevocationBarrierState): Boolean {
            if (failWrites) return false
            this.state = state
            return true
        }
    }
}
