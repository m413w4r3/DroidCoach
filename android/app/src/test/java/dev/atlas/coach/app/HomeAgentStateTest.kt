package dev.atlas.coach.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAgentStateTest {
    @Test
    fun titleAtSixtyCodePointsIsPreserved() {
        val title = "a".repeat(60)
        assertEquals(title, truncateHomeDirectiveTitle(title))
    }

    @Test
    fun longTitleIsTruncatedToSixtyCodePointsAndKeepsSurrogatePairsWhole() {
        val title = "🙂".repeat(61)
        val display = truncateHomeDirectiveTitle(title)
        assertEquals(60, display.codePointCount(0, display.length))
        assertTrue(display.endsWith("…"))
        assertFalse(display.dropLast(1).last().isHighSurrogate())
    }

    @Test
    fun aCustomLimitMustBePositive() {
        val error = runCatching { truncateHomeDirectiveTitle("titre", 0) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
