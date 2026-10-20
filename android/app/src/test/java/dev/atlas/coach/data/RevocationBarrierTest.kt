package dev.atlas.coach.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RevocationBarrierTest {
    @Test
    fun successfulBlockSurvivesRecreationUntilDurableExplicitOptIn() {
        val storage = MemoryBarrierStorage()
        val barrier = RevocationBarrier(storage)
        assertFalse(barrier.isAgentBlocked())
        assertTrue(barrier.block())
        assertTrue(RevocationBarrier(storage).isAgentBlocked())

        assertFalse(barrier.clearAfterDurableOptIn(durablyStored = false))
        assertTrue(barrier.isAgentBlocked())
        assertTrue(barrier.clearAfterDurableOptIn(durablyStored = true))
        assertFalse(RevocationBarrier(storage).isAgentBlocked())
    }

    @Test
    fun categoryRevocationRequiresMatchingDurableCategoryOptIn() {
        val storage = MemoryBarrierStorage()
        val barrier = RevocationBarrier(storage)
        assertTrue(barrier.blockCategory("goals"))
        assertFalse(RevocationBarrier(storage).isAgentBlocked())
        assertEquals(setOf("goals"), RevocationBarrier(storage).blockedCategories())
        assertTrue(barrier.clearAfterDurableOptIn(durablyStored = true))
        assertFalse(barrier.isAgentBlocked())
        assertEquals(setOf("goals"), barrier.blockedCategories())
        assertTrue(barrier.clearCategoryAfterDurableOptIn("focus", durablyStored = true))
        assertFalse(barrier.isAgentBlocked())
        assertEquals(setOf("goals"), barrier.blockedCategories())
        assertTrue(barrier.clearCategoryAfterDurableOptIn("goals", durablyStored = true))
        assertFalse(RevocationBarrier(storage).isAgentBlocked())
        assertTrue(RevocationBarrier(storage).blockedCategories().isEmpty())
    }

    @Test
    fun failedMarkerWriteStillLatchesTheCurrentProcessFailClosed() {
        val storage = MemoryBarrierStorage().apply { failWrites = true }
        val barrier = RevocationBarrier(storage)

        assertFalse(barrier.block())
        assertTrue(barrier.isAgentBlocked())
        assertFalse(barrier.clearAfterDurableOptIn(durablyStored = true))
        assertTrue(barrier.isAgentBlocked())
    }

    @Test
    fun failedCategoryMarkerWriteEscalatesToGlobalInMemoryLatch() {
        val storage = MemoryBarrierStorage().apply { failWrites = true }
        val barrier = RevocationBarrier(storage)

        assertFalse(barrier.blockCategory("goals"))
        assertTrue(barrier.isAgentBlocked())
        assertEquals(setOf("goals"), barrier.blockedCategories())
    }

    @Test
    fun durableOptInClearsOnlyTheMatchingCategoryBarrier() {
        val storage = MemoryBarrierStorage()
        val barrier = RevocationBarrier(storage)
        assertTrue(barrier.blockCategory("goals"))
        assertTrue(barrier.blockCategory("location_summary"))

        assertTrue(barrier.clearCategoryAfterDurableOptIn("goals", durablyStored = true))

        val recreated = RevocationBarrier(storage)
        assertFalse(recreated.isAgentBlocked())
        assertEquals(setOf("location_summary"), recreated.blockedCategories())
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
