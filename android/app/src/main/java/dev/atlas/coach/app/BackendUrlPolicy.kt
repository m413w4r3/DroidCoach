package dev.atlas.coach.app

import java.net.URI

/**
 * Backend URL policy shared by the agent and plan-revision clients.
 *
 * Release builds keep HTTPS only. Debug builds additionally accept cleartext to
 * the Android emulator alias and the USB reverse loopback, matching the debug
 * network-security-config. No other cleartext host is allowed.
 */
internal object BackendUrlPolicy {
    fun isAllowedAgentBackendUrl(value: String, debuggable: Boolean): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (scheme !in setOf("http", "https")) return false
        if (host.isBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null) return false
        if (scheme == "https") return true
        if (!debuggable) return false
        return host == "10.0.2.2" || host == "127.0.0.1" || host == "localhost"
    }
}
