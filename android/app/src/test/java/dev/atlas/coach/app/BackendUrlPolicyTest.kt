package dev.atlas.coach.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendUrlPolicyTest {
    @Test
    fun releaseAllowsHttpsOnly() {
        assertTrue(BackendUrlPolicy.isAllowedAgentBackendUrl("https://atlas.example", debuggable = false))
        assertTrue(BackendUrlPolicy.isAllowedAgentBackendUrl("https://127.0.0.1:8443", debuggable = false))
        assertFalse(BackendUrlPolicy.isAllowedAgentBackendUrl("http://127.0.0.1:8010", debuggable = false))
        assertFalse(BackendUrlPolicy.isAllowedAgentBackendUrl("http://10.0.2.2:8010", debuggable = false))
    }

    @Test
    fun debugAllowsOnlyEmulatorAndLoopbackCleartext() {
        assertTrue(BackendUrlPolicy.isAllowedAgentBackendUrl("http://10.0.2.2:8010", debuggable = true))
        assertTrue(BackendUrlPolicy.isAllowedAgentBackendUrl("http://127.0.0.1:8010", debuggable = true))
        assertTrue(BackendUrlPolicy.isAllowedAgentBackendUrl("http://localhost:8010", debuggable = true))
        assertFalse(BackendUrlPolicy.isAllowedAgentBackendUrl("http://192.168.1.20:8010", debuggable = true))
        assertFalse(BackendUrlPolicy.isAllowedAgentBackendUrl("http://atlas.example:8010", debuggable = true))
    }

    @Test
    fun rejectsUserInfoQueryFragmentAndInvalidUrls() {
        assertFalse(BackendUrlPolicy.isAllowedAgentBackendUrl("https://user:pass@atlas.example", debuggable = true))
        assertFalse(BackendUrlPolicy.isAllowedAgentBackendUrl("https://atlas.example?token=secret", debuggable = true))
        assertFalse(BackendUrlPolicy.isAllowedAgentBackendUrl("https://atlas.example#fragment", debuggable = true))
        assertFalse(BackendUrlPolicy.isAllowedAgentBackendUrl("not a url", debuggable = true))
        assertFalse(BackendUrlPolicy.isAllowedAgentBackendUrl("ftp://atlas.example", debuggable = true))
    }
}
