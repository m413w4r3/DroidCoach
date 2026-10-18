package dev.atlas.coach

import org.junit.Assert.assertEquals
import org.junit.Test
import dev.atlas.coach.app.AtlasIdentity

class SmokeUnitTest {
    @Test
    fun atlasLabelIsStable() {
        assertEquals("ATLAS", AtlasIdentity.name)
    }
}
