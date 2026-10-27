package dev.atlas.coach.sync.relay

import java.net.UnknownHostException
import javax.crypto.AEADBadTagException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RelayFailureTest {
    @Test
    fun classifiesTransportSecurityIdentityAuthenticationNetworkAndPermissionFailures() {
        val failures = listOf(
            RelayFailure.fromThrowable(SSLHandshakeException("certificate validation failed")),
            RelayFailure.fromThrowable(SSLPeerUnverifiedException("Hostname relay.example.org not verified")),
            RelayFailure.fromThrowable(
                NoiseProtocolException(
                    "authentication failed",
                    AEADBadTagException("token=secret wss://user:password@relay.example.org"),
                ),
            ),
            RelayFailure.fromRemoteCode("AUTH_FAILED"),
            RelayFailure.fromRemoteCode("DEVICE_REVOKED"),
            RelayFailure.fromThrowable(UnknownHostException("relay.example.org")),
            RelayFailure.fromThrowable(SecurityException("permission denied")),
        )

        assertEquals(
            listOf(
                RelayFailureKind.TLS_CERTIFICATE,
                RelayFailureKind.TLS_HOSTNAME_MISMATCH,
                RelayFailureKind.NOISE_IDENTITY_MISMATCH,
                RelayFailureKind.AUTHENTICATION,
                RelayFailureKind.REVOKED,
                RelayFailureKind.NETWORK_UNREACHABLE,
                RelayFailureKind.PERMISSION_DENIED,
            ),
            failures.map(RelayFailure::kind),
        )
        assertEquals(failures.size, failures.map(RelayFailure::message).distinct().size)
        failures.forEach { failure ->
            assertFalse(failure.message.contains("secret", ignoreCase = true))
            assertFalse(failure.message.contains("password", ignoreCase = true))
            assertFalse(failure.message.contains("wss://", ignoreCase = true))
        }
    }

    @Test
    fun certificatePeerErrorsRemainCertificateFailuresWhenTheyAreNotHostnameMismatches() {
        val failure = RelayFailure.fromThrowable(SSLPeerUnverifiedException("Certificate pinning failure"))

        assertEquals(RelayFailureKind.TLS_CERTIFICATE, failure.kind)
        assertNotEquals(RelayFailureKind.TLS_HOSTNAME_MISMATCH, failure.kind)
    }
}
