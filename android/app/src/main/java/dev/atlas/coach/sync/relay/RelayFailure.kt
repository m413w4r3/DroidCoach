package dev.atlas.coach.sync.relay

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathBuilderException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.crypto.AEADBadTagException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

enum class RelayFailureKind {
    TLS_CERTIFICATE,
    TLS_HOSTNAME_MISMATCH,
    NOISE_IDENTITY_MISMATCH,
    AUTHENTICATION,
    REVOKED,
    NETWORK_UNREACHABLE,
    PERMISSION_DENIED,
    UNKNOWN,
}

internal class PinnedNoiseIdentityMismatchException(cause: Throwable) :
    Exception("Pinned Noise identity mismatch", cause)

/** Safe, typed relay failure. Messages are fixed and never include exception or server text. */
data class RelayFailure(
    val kind: RelayFailureKind,
    val message: String,
) {
    companion object {
        fun fromThrowable(error: Throwable): RelayFailure {
            val causes = causeChain(error)
            val kind = when {
                causes.any(::isPermissionFailure) -> RelayFailureKind.PERMISSION_DENIED
                causes.any(::isHostnameMismatch) -> RelayFailureKind.TLS_HOSTNAME_MISMATCH
                causes.any { it is PinnedNoiseIdentityMismatchException } ||
                    (causes.any { it is NoiseProtocolException } && causes.any { it is AEADBadTagException }) ->
                    RelayFailureKind.NOISE_IDENTITY_MISMATCH
                causes.any(::isTlsFailure) -> RelayFailureKind.TLS_CERTIFICATE
                causes.any(::isNetworkFailure) -> RelayFailureKind.NETWORK_UNREACHABLE
                else -> RelayFailureKind.UNKNOWN
            }
            return of(kind)
        }

        fun fromRemoteCode(code: String): RelayFailure = when (code) {
            "AUTH_REQUIRED", "AUTH_FAILED" -> of(RelayFailureKind.AUTHENTICATION)
            "DEVICE_REVOKED" -> of(RelayFailureKind.REVOKED)
            else -> of(RelayFailureKind.UNKNOWN)
        }

        fun of(kind: RelayFailureKind): RelayFailure = RelayFailure(kind, kind.userMessage)

        private fun isPermissionFailure(error: Throwable): Boolean =
            error is SecurityException ||
                (error is SocketException && error.message.orEmpty().containsAny(
                    "permission denied",
                    "operation not permitted",
                    "eacces",
                ))

        private fun isHostnameMismatch(error: Throwable): Boolean {
            if (error !is SSLPeerUnverifiedException && error !is SSLHandshakeException) return false
            return error.message.orEmpty().containsAny(
                "hostname",
                "host name",
                "subject alternative",
                "doesn't match",
                "does not match",
                "not verified",
            )
        }

        private fun isTlsFailure(error: Throwable): Boolean =
            error is SSLException ||
                error is CertificateException ||
                error is CertPathValidatorException ||
                error is CertPathBuilderException

        private fun isNetworkFailure(error: Throwable): Boolean =
            error is UnknownHostException ||
                error is ConnectException ||
                error is NoRouteToHostException ||
                error is SocketTimeoutException ||
                error is SocketException ||
                error is IOException

        private fun causeChain(error: Throwable): List<Throwable> {
            val causes = mutableListOf<Throwable>()
            var current: Throwable? = error
            repeat(MAX_CAUSE_DEPTH) {
                val cause = current ?: return causes
                if (cause in causes) return causes
                causes += cause
                current = cause.cause
            }
            return causes
        }

        private fun String.containsAny(vararg values: String): Boolean {
            val normalized = lowercase()
            return values.any(normalized::contains)
        }

        private const val MAX_CAUSE_DEPTH = 16
    }
}

private val RelayFailureKind.userMessage: String
    get() = when (this) {
        RelayFailureKind.TLS_CERTIFICATE -> "Le certificat TLS du relais est invalide, expiré ou non approuvé."
        RelayFailureKind.TLS_HOSTNAME_MISMATCH -> "Le nom d’hôte du certificat TLS ne correspond pas à l’adresse du relais."
        RelayFailureKind.NOISE_IDENTITY_MISMATCH -> "L’identité épinglée du PC ne correspond pas. Vérifiez l’appairage avant de réessayer."
        RelayFailureKind.AUTHENTICATION -> "L’authentification de l’appareil a été refusée. Vérifiez le jeton d’appairage."
        RelayFailureKind.REVOKED -> "Cet appareil a été révoqué sur le PC. Réappairez-le pour vous reconnecter."
        RelayFailureKind.NETWORK_UNREACHABLE -> "Le relais est injoignable. Vérifiez la connexion réseau et l’adresse du relais."
        RelayFailureKind.PERMISSION_DENIED -> "Android a refusé l’autorisation nécessaire à la connexion au relais."
        RelayFailureKind.UNKNOWN -> "La connexion relayée a échoué. Réessayez ou vérifiez la configuration."
    }
