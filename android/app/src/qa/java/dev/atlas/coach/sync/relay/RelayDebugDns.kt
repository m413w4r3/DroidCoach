package dev.atlas.coach.sync.relay

import okhttp3.Dns
import java.net.InetAddress

/** Explicit loopback answers for local TLS tests; the system resolver handles all other names. */
internal object RelayDebugDns : Dns {
    private val testHosts = setOf("relay.atlas.test", "wrong-host.atlas.test", "untrusted.atlas.test")
    private val loopback = byteArrayOf(127, 0, 0, 1)

    override fun lookup(hostname: String): List<InetAddress> =
        if (hostname.lowercase() in testHosts) {
            listOf(InetAddress.getByAddress(hostname, loopback))
        } else {
            Dns.SYSTEM.lookup(hostname)
        }
}
