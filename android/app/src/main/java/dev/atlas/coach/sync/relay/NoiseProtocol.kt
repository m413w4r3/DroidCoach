package dev.atlas.coach.sync.relay

import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Noise Protocol Framework rev. 34 ChaChaPoly state. */
class NoiseCipherState {
    private var key: ByteArray? = null
    private var nonce: BigInteger = BigInteger.ZERO

    @Synchronized
    fun initializeKey(key: ByteArray?, nonce: BigInteger = BigInteger.ZERO) {
        require(nonce.signum() >= 0 && nonce <= MAX_NONCE)
        require(key == null || key.size == NOISE_KEY_BYTES)
        this.key?.fill(0)
        this.key = key?.copyOf()
        this.nonce = nonce
    }

    @Synchronized
    fun hasKey(): Boolean = key != null

    @Synchronized
    fun nonce(): BigInteger = nonce

    @Synchronized
    @Throws(NoiseProtocolException::class)
    fun encryptWithAd(ad: ByteArray, plaintext: ByteArray): ByteArray {
        val activeKey = key ?: return plaintext.copyOf()
        ensureNonceAvailable()
        val currentNonce = encodeNonce(nonce)
        nonce = nonce.add(BigInteger.ONE)
        return try {
            chachaPoly(activeKey, currentNonce, ad, plaintext, encrypt = true)
        } catch (error: Exception) {
            throw NoiseProtocolException("ChaChaPoly encryption failed", error)
        }
    }

    @Synchronized
    @Throws(NoiseProtocolException::class)
    fun decryptWithAd(ad: ByteArray, ciphertext: ByteArray): ByteArray {
        val activeKey = key ?: return ciphertext.copyOf()
        ensureNonceAvailable()
        return try {
            chachaPoly(activeKey, encodeNonce(nonce), ad, ciphertext, encrypt = false).also { nonce = nonce.add(BigInteger.ONE) }
        } catch (error: Exception) {
            throw NoiseProtocolException("authentication failed", error)
        }
    }

    @Synchronized
    fun clear() {
        key?.fill(0)
        key = null
        nonce = MAX_NONCE
    }

    private fun ensureNonceAvailable() {
        if (nonce == MAX_NONCE) throw NoiseProtocolException("nonce exhausted")
    }

    companion object {
        const val NOISE_TAG_BYTES = 16
        const val NOISE_MAX_MESSAGE_BYTES = 65_535
        private const val NOISE_KEY_BYTES = 32
        private val MAX_NONCE: BigInteger = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)

        private fun chachaPoly(
            key: ByteArray,
            nonce: ByteArray,
            associatedData: ByteArray,
            input: ByteArray,
            encrypt: Boolean,
        ): ByteArray {
            val cipher = ChaCha20Poly1305()
            cipher.init(encrypt, AEADParameters(KeyParameter(key), NOISE_TAG_BYTES * 8, nonce, associatedData))
            val output = ByteArray(cipher.getOutputSize(input.size))
            var written = cipher.processBytes(input, 0, input.size, output, 0)
            written += cipher.doFinal(output, written)
            return if (written == output.size) output else output.copyOf(written)
        }

        /** Noise nonce = four zero bytes followed by the u64 counter little-endian. */
        fun encodeNonce(nonce: BigInteger): ByteArray {
            require(nonce.signum() >= 0 && nonce <= MAX_NONCE)
            val result = ByteArray(12)
            var value = nonce
            for (index in 4 until 12) {
                result[index] = value.and(BigInteger.valueOf(0xff)).toByte()
                value = value.shiftRight(8)
            }
            return result
        }
    }
}

class NoiseProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Noise SymmetricState for SHA-256, including the two-output HKDF used by NK. */
class SymmetricState(protocolName: String = NOISE_NK_PROTOCOL_NAME) {
    val cipherState = NoiseCipherState()
    private var chainingKey: ByteArray
    private var handshakeHash: ByteArray

    init {
        val name = protocolName.toByteArray(Charsets.UTF_8)
        handshakeHash = if (name.size <= HASH_BYTES) ByteArray(HASH_BYTES).also { it.putBytes(0, name) }
        else sha256(name)
        chainingKey = handshakeHash.copyOf()
        cipherState.initializeKey(null)
    }

    @Synchronized
    fun mixKey(inputKeyMaterial: ByteArray) {
        val (newCk, tempK) = hkdf2(chainingKey, inputKeyMaterial)
        chainingKey.fill(0)
        chainingKey = newCk
        cipherState.initializeKey(tempK)
        tempK.fill(0)
    }

    @Synchronized
    fun mixHash(data: ByteArray) {
        handshakeHash = sha256(concat(handshakeHash, data))
    }

    @Synchronized
    fun handshakeHash(): ByteArray = handshakeHash.copyOf()

    @Synchronized
    @Throws(NoiseProtocolException::class)
    fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val ciphertext = cipherState.encryptWithAd(handshakeHash, plaintext)
        mixHash(ciphertext)
        return ciphertext
    }

    @Synchronized
    @Throws(NoiseProtocolException::class)
    fun decryptAndHash(ciphertext: ByteArray): ByteArray {
        val plaintext = cipherState.decryptWithAd(handshakeHash, ciphertext)
        mixHash(ciphertext)
        return plaintext
    }

    @Synchronized
    fun split(): Pair<NoiseCipherState, NoiseCipherState> {
        val (k1, k2) = hkdf2(chainingKey, ByteArray(0))
        val first = NoiseCipherState().also { it.initializeKey(k1) }
        val second = NoiseCipherState().also { it.initializeKey(k2) }
        k1.fill(0)
        k2.fill(0)
        chainingKey.fill(0)
        cipherState.clear()
        return first to second
    }

    @Synchronized
    fun clear() {
        chainingKey.fill(0)
        handshakeHash.fill(0)
        cipherState.clear()
    }

    private fun hkdf2(ck: ByteArray, input: ByteArray): Pair<ByteArray, ByteArray> {
        val tempKey = hmacSha256(ck, input)
        val output1 = hmacSha256(tempKey, byteArrayOf(0x01))
        val output2 = hmacSha256(tempKey, concat(output1, byteArrayOf(0x02)))
        tempKey.fill(0)
        return output1 to output2
    }

    companion object {
        private const val HASH_BYTES = 32
        private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
        private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data)
        }
    }
}

data class NoiseKeyPair(val publicKey: ByteArray, val privateKey: ByteArray) {
    init { require(publicKey.size == NOISE_DH_BYTES && privateKey.size == NOISE_DH_BYTES) }

    companion object {
        fun generate(): NoiseKeyPair {
            val privateKey = ByteArray(NOISE_DH_BYTES)
            X25519.generatePrivateKey(SecureRandom(), privateKey)
            return fromPrivateKey(privateKey).also { privateKey.fill(0) }
        }

        fun fromPrivateKey(privateKey: ByteArray): NoiseKeyPair {
            require(privateKey.size == NOISE_DH_BYTES)
            val copied = privateKey.copyOf()
            val public = ByteArray(NOISE_DH_BYTES)
            X25519.generatePublicKey(copied, 0, public, 0)
            return NoiseKeyPair(public, copied)
        }
    }
}

data class NoiseTransportKeys(val send: NoiseCipherState, val receive: NoiseCipherState)

/**
 * Noise_NK_25519_ChaChaPoly_SHA256 initiator (Noise revision 34), bound to the
 * pinned responder static key and the frozen Paseo prologue.
 */
class NoiseNkInitiator(
    remoteStaticPublicKey: ByteArray,
    prologue: ByteArray = PASEO_NOISE_PROLOGUE,
    ephemeral: NoiseKeyPair = NoiseKeyPair.generate(),
) {
    private val remoteStatic = remoteStaticPublicKey.copyOf()
    private val ephemeralKey = ephemeral
    private val symmetric = SymmetricState()
    private var wroteFirst = false
    private var readSecond = false
    private var split = false

    init {
        require(remoteStatic.size == NOISE_DH_BYTES)
        symmetric.mixHash(prologue)
        symmetric.mixHash(remoteStatic)
    }

    @Synchronized
    @Throws(NoiseProtocolException::class)
    fun writeMessage(payload: ByteArray): ByteArray {
        if (wroteFirst || readSecond || split) throw NoiseProtocolException("handshake message out of turn")
        symmetric.mixHash(ephemeralKey.publicKey)
        val shared = dh(ephemeralKey.privateKey, remoteStatic)
        symmetric.mixKey(shared)
        shared.fill(0)
        val encryptedPayload = symmetric.encryptAndHash(payload)
        val message = concat(ephemeralKey.publicKey, encryptedPayload)
        if (message.size > NoiseCipherState.NOISE_MAX_MESSAGE_BYTES) {
            throw NoiseProtocolException("handshake message too large")
        }
        wroteFirst = true
        return message
    }

    @Synchronized
    @Throws(NoiseProtocolException::class)
    fun readMessage(message: ByteArray): ByteArray {
        if (!wroteFirst || readSecond || split) throw NoiseProtocolException("handshake message out of turn")
        if (message.size > NoiseCipherState.NOISE_MAX_MESSAGE_BYTES || message.size < NOISE_DH_BYTES + NoiseCipherState.NOISE_TAG_BYTES) {
            throw NoiseProtocolException("handshake message truncated or too large")
        }
        val remoteEphemeral = message.copyOfRange(0, NOISE_DH_BYTES)
        symmetric.mixHash(remoteEphemeral)
        val shared = dh(ephemeralKey.privateKey, remoteEphemeral)
        symmetric.mixKey(shared)
        shared.fill(0)
        val payload = symmetric.decryptAndHash(message.copyOfRange(NOISE_DH_BYTES, message.size))
        readSecond = true
        return payload
    }

    @Synchronized
    fun handshakeHash(): ByteArray = symmetric.handshakeHash()

    @Synchronized
    @Throws(NoiseProtocolException::class)
    fun split(): NoiseTransportKeys {
        if (!readSecond || split) throw NoiseProtocolException("handshake incomplete or already split")
        split = true
        ephemeralKey.privateKey.fill(0)
        val (send, receive) = symmetric.split()
        return NoiseTransportKeys(send, receive)
    }

    @Synchronized
    fun destroy() {
        ephemeralKey.privateKey.fill(0)
        remoteStatic.fill(0)
        symmetric.clear()
        split = true
    }

    private fun dh(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        val shared = ByteArray(NOISE_DH_BYTES)
        if (!X25519.calculateAgreement(privateKey, 0, publicKey, 0, shared, 0)) {
            shared.fill(0)
            throw NoiseProtocolException("invalid DH public key")
        }
        var nonZero = 0
        shared.forEach { nonZero = nonZero or (it.toInt() and 0xff) }
        if (nonZero == 0) {
            shared.fill(0)
            throw NoiseProtocolException("invalid DH public key")
        }
        return shared
    }

    companion object {
        const val NOISE_NK_PROTOCOL_NAME = "Noise_NK_25519_ChaChaPoly_SHA256"
        val PASEO_NOISE_PROLOGUE: ByteArray = "paseo-relay-e2ee/1".toByteArray(Charsets.US_ASCII)
        const val PARAMS_VERSION: Int = 0x01
        const val PARAM_FLAG_BINARY_CIPHERTEXT: Int = 0x01

        fun encodeParameters(binaryCiphertext: Boolean): ByteArray = byteArrayOf(
            PARAMS_VERSION.toByte(),
            if (binaryCiphertext) PARAM_FLAG_BINARY_CIPHERTEXT.toByte() else 0,
        )

        fun validateParameters(payload: ByteArray): Boolean {
            if (payload.size != 2 || payload[0].toInt() and 0xff != PARAMS_VERSION) {
                throw NoiseProtocolException("invalid parameter payload")
            }
            val flags = payload[1].toInt() and 0xff
            if (flags and PARAM_FLAG_BINARY_CIPHERTEXT.inv() != 0) {
                throw NoiseProtocolException("reserved parameter flag set")
            }
            return flags and PARAM_FLAG_BINARY_CIPHERTEXT != 0
        }
    }
}

private const val NOISE_KEY_BYTES = 32
private const val NOISE_NK_PROTOCOL_NAME = "Noise_NK_25519_ChaChaPoly_SHA256"

private fun ByteArray.putBytes(offset: Int, bytes: ByteArray) { System.arraycopy(bytes, 0, this, offset, bytes.size) }
private fun concat(vararg values: ByteArray): ByteArray {
    val length = values.sumOf(ByteArray::size)
    val output = ByteArray(length)
    var offset = 0
    values.forEach { System.arraycopy(it, 0, output, offset, it.size); offset += it.size }
    return output
}
