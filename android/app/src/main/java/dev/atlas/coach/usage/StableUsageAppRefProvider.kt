package dev.atlas.coach.usage

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Creates stable, local-only opaque references using an installation-scoped secret. */
class StableUsageAppRefProvider(salt: ByteArray) : UsageAppRefProvider {
    private val key = salt.copyOf()

    init {
        require(key.size >= 16) { "App reference salt must contain at least 128 bits" }
    }

    override fun appRef(packageName: String): String {
        require(packageName.isNotBlank())
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val digest = mac.doFinal(packageName.toByteArray(Charsets.UTF_8)).copyOf(12)
        val hex = digest.joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        return "app:$hex"
    }
}
