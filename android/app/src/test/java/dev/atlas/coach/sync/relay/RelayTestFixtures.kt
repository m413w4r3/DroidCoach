package dev.atlas.coach.sync.relay

import org.bouncycastle.math.ec.rfc7748.X25519
import org.json.JSONObject
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

internal fun hexBytes(value: String): ByteArray = ByteArray(value.length / 2) { index ->
    value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

internal fun testCredentials(staticPair: NoiseKeyPair = NoiseKeyPair.generate()) =
    RelayCredentials(
        deviceId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        deviceToken = "A".repeat(43),
        remoteStaticPublicKey = staticPair.publicKey,
    ) to staticPair

internal enum class TestHandshakeMode { PROTECTED, LEGACY_READY, HOLD_READY }

/** In-memory secure-channel peer that runs the NK responder steps for transport tests. */
internal class FakeRelaySocketFactory(
    private val staticPair: NoiseKeyPair,
    private val handshakeMode: TestHandshakeMode = TestHandshakeMode.PROTECTED,
    private val authError: Boolean = false,
    private val revokedError: Boolean = false,
    private val remoteErrorMessage: String = "device authentication required",
    private val dropRequestResponses: Boolean = false,
    private val mismatchResponseId: Boolean = false,
    private val result: JSONObject = JSONObject().put("accepted", true),
    private val respondToPings: Boolean = false,
    private val onConnected: (FakeRelaySocket, Int) -> Unit = { _, _ -> },
) : RelaySocketFactory {
    val connectCount = AtomicInteger()
    val sockets = CopyOnWriteArrayList<FakeRelaySocket>()
    val requests = CopyOnWriteArrayList<JSONObject>()
    val appHellos = CopyOnWriteArrayList<JSONObject>()
    val cancels = CopyOnWriteArrayList<JSONObject>()
    val urls = CopyOnWriteArrayList<String>()
    val requestCaptured = CountDownLatch(1)

    override fun connect(url: String, callbacks: RelaySocketCallbacks) {
        urls += url
        val index = connectCount.incrementAndGet()
        val socket = FakeRelaySocket(
            callbacks,
            this,
            staticPair,
            handshakeMode,
            authError,
            revokedError,
            remoteErrorMessage,
            dropRequestResponses,
            mismatchResponseId,
            result,
            respondToPings,
        )
        sockets += socket
        callbacks.onOpen(socket)
        onConnected(socket, index)
    }

    internal fun recordRequest(request: JSONObject) { requests += JSONObject(request.toString()); requestCaptured.countDown() }
    internal fun recordHello(hello: JSONObject) { appHellos += JSONObject(hello.toString()) }
    internal fun recordCancel(cancel: JSONObject) { cancels += JSONObject(cancel.toString()) }
}

internal class FakeRelaySocket(
    private val callbacks: RelaySocketCallbacks,
    private val factory: FakeRelaySocketFactory,
    private val staticPair: NoiseKeyPair,
    private val handshakeMode: TestHandshakeMode,
    private val authError: Boolean,
    private val revokedError: Boolean,
    private val remoteErrorMessage: String,
    private val dropRequestResponses: Boolean,
    private val mismatchResponseId: Boolean,
    private val result: JSONObject,
    private val respondToPings: Boolean,
) : RelaySocket {
    val sentText = CopyOnWriteArrayList<String>()
    val helloSends = CountDownLatch(2)
    @Volatile var closedCode: Int? = null
        private set
    private var responder: TestNoiseResponder? = null

    override fun sendText(text: String): Boolean {
        sentText += text
        if (text.startsWith("{")) {
            val handshake = runCatching { JSONObject(text) }.getOrNull()
            if (handshake?.optString("type") == "e2ee_hello") {
                helloSends.countDown()
                when (handshakeMode) {
                    TestHandshakeMode.HOLD_READY -> Unit
                    TestHandshakeMode.LEGACY_READY -> callbacks.onText("{\"type\":\"e2ee_ready\"}")
                    TestHandshakeMode.PROTECTED -> {
                        responder = TestNoiseResponder(staticPair)
                        val first = Base64.getDecoder().decode(handshake.getJSONObject("protected").getString("message"))
                        val (message, keys) = responder!!.respond(first)
                        responder!!.keys = keys
                        callbacks.onText(
                            JSONObject().put("type", "e2ee_ready")
                                .put("protected", JSONObject().put("v", 1).put("message", Base64.getEncoder().encodeToString(message)))
                                .toString(),
                        )
                    }
                }
                return true
            }
        }
        val peer = responder ?: return false
        val record = runCatching { Base64.getDecoder().decode(text) }.getOrNull() ?: return false
        val frame = ProtectedRecordCodec.decode(record, peer.keys!!.receive)
        if (frame.kind != ProtectedFrameKind.TEXT) return false
        val envelope = JSONObject(Charsets.UTF_8.decode(java.nio.ByteBuffer.wrap(frame.data)).toString())
        when (envelope.getString("kind")) {
            "hello" -> {
                factory.recordHello(envelope)
                if (authError || revokedError) {
                    sendEnvelope(
                        JSONObject().put("protocol", AtlasRemoteProtocol.PROTOCOL).put("kind", "error")
                            .put("request_id", JSONObject.NULL).put("session_id", JSONObject.NULL)
                            .put("error", JSONObject().put("code", if (revokedError) "DEVICE_REVOKED" else "AUTH_REQUIRED")
                                .put("message", remoteErrorMessage).put("retryable", false))
                            .put("completed_at", Instant.now().toString()),
                    )
                } else {
                    sendEnvelope(
                        JSONObject().put("protocol", AtlasRemoteProtocol.PROTOCOL).put("kind", "hello_ok")
                            .put("session_id", SESSION_ID).put("server_time", Instant.now().toString())
                            .put("expires_in_seconds", 3600).put("capabilities", org.json.JSONArray().put("plan.revision"))
                            .put("max_request_bytes", AtlasRemoteProtocol.MAX_REQUEST_BYTES)
                            .put("max_response_bytes", AtlasRemoteProtocol.MAX_RESPONSE_BYTES),
                    )
                }
            }
            "request" -> {
                factory.recordRequest(envelope)
                if (!dropRequestResponses) {
                    sendEnvelope(
                        JSONObject().put("protocol", AtlasRemoteProtocol.PROTOCOL).put("kind", "response")
                            .put("request_id", if (mismatchResponseId) "dddddddd-dddd-4ddd-8ddd-dddddddddddd" else envelope.getString("request_id"))
                            .put("session_id", SESSION_ID)
                            .put("operation", envelope.getString("operation")).put("status", "OK")
                            .put("result", JSONObject(result.toString())).put("completed_at", Instant.now().toString())
                            .put("replayed", false),
                    )
                }
            }
            "cancel" -> factory.recordCancel(envelope)
            "ping" -> if (respondToPings) {
                sendEnvelope(
                    JSONObject().put("protocol", AtlasRemoteProtocol.PROTOCOL)
                        .put("kind", "pong")
                        .put("nonce", envelope.getString("nonce")),
                )
            }
        }
        return true
    }

    override fun close(code: Int, reason: String) {
        if (closedCode != null) return
        closedCode = code
        callbacks.onClosed(code, reason)
    }

    fun remoteClose(code: Int = 1006, reason: String = "simulated disconnect") {
        if (closedCode != null) return
        closedCode = code
        callbacks.onClosed(code, reason)
    }

    private fun sendEnvelope(envelope: JSONObject) {
        val peer = responder ?: error("handshake not complete")
        val bytes = AtlasRemoteProtocol.encode(envelope)
        val record = ProtectedRecordCodec.encode(ProtectedFrameKind.TEXT, bytes, peer.keys!!.send)
        callbacks.onText(Base64.getEncoder().encodeToString(record))
    }

    companion object { const val SESSION_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb" }
}

private class TestNoiseResponder(private val staticPair: NoiseKeyPair) {
    private val symmetric = SymmetricState()
    private val ephemeral = NoiseKeyPair.generate()
    var keys: NoiseTransportKeys? = null

    init {
        symmetric.mixHash(NoiseNkInitiator.PASEO_NOISE_PROLOGUE)
        symmetric.mixHash(staticPair.publicKey)
    }

    fun respond(firstMessage: ByteArray): Pair<ByteArray, NoiseTransportKeys> {
        require(firstMessage.size >= NOISE_DH_BYTES + NoiseCipherState.NOISE_TAG_BYTES)
        val remoteEphemeral = firstMessage.copyOfRange(0, NOISE_DH_BYTES)
        symmetric.mixHash(remoteEphemeral)
        val es = x25519(staticPair.privateKey, remoteEphemeral)
        symmetric.mixKey(es)
        es.fill(0)
        require(symmetric.decryptAndHash(firstMessage.copyOfRange(NOISE_DH_BYTES, firstMessage.size)).contentEquals(byteArrayOf(1, 1)))
        symmetric.mixHash(ephemeral.publicKey)
        val ee = x25519(ephemeral.privateKey, remoteEphemeral)
        symmetric.mixKey(ee)
        ee.fill(0)
        val secondMessage = concatForTest(ephemeral.publicKey, symmetric.encryptAndHash(byteArrayOf(1, 1)))
        val (first, second) = symmetric.split()
        ephemeral.privateKey.fill(0)
        return secondMessage to NoiseTransportKeys(send = second, receive = first)
    }
}

private fun concatForTest(a: ByteArray, b: ByteArray): ByteArray = a + b

private fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray = ByteArray(NOISE_DH_BYTES).also {
    check(X25519.calculateAgreement(privateKey, 0, publicKey, 0, it, 0))
}
