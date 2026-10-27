package dev.atlas.coach.sync.relay

import okhttp3.OkHttpClient
import okhttp3.Dns
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.Executors

/** A protected-only Paseo Relay v2 WebSocket client. */
class PaseoRelayClient internal constructor(
    private val config: RelayConfig,
    private val credentialsProvider: RelayCredentialsProvider,
    private val listener: Listener,
    private val socketFactory: RelaySocketFactory = OkHttpRelaySocketFactory(config),
    private val scheduler: ScheduledExecutorService = newDaemonScheduler("atlas-relay-client"),
    private val jitter: (Long) -> Long = { bound -> if (bound <= 0) 0 else ThreadLocalRandom.current().nextLong(bound + 1) },
    private val noiseFactory: (ByteArray) -> NoiseNkInitiator = { remoteStatic -> NoiseNkInitiator(remoteStatic) },
) : AutoCloseable {
    init {
        config.requireUrlAllowedForCurrentBuild()
    }

    interface Listener {
        fun onStateChanged(state: RelayConnectionState) {}
        fun onSecureChannelReady() {}
        fun onApplicationFrame(frame: ByteArray) {}
        fun onSocketClosed(code: Int, reason: String) {}
        fun onProtocolFailure(error: Throwable) {}
        fun onRelayFailure(failure: RelayFailure) {}
    }

    private val lock = Any()
    private var state = RelayConnectionState.LOCAL
    private var stopped = true
    private var generation = 0L
    private var reconnectAttempt = 0
    private var socket: RelaySocket? = null
    private var handshake: NoiseNkInitiator? = null
    private var sendCipher: NoiseCipherState? = null
    private var receiveCipher: NoiseCipherState? = null
    private var handshakeText: String? = null
    private var handshakeRetry: ScheduledFuture<*>? = null
    private var reconnectTask: ScheduledFuture<*>? = null

    fun state(): RelayConnectionState = synchronized(lock) { state }

    fun start() {
        synchronized(lock) {
            if (!stopped) return
            stopped = false
            generation += 1
            reconnectAttempt = 0
            cancelReconnectLocked()
        }
        connect()
    }

    fun sendTextFrame(frame: ByteArray): Boolean = synchronized(lock) {
        val activeSocket = socket ?: return false
        val sender = sendCipher ?: return false
        if (state != RelayConnectionState.RELAY_CONNECTED) return false
        try {
            val record = ProtectedRecordCodec.encode(ProtectedFrameKind.TEXT, frame, sender)
            activeSocket.sendText(Base64.getEncoder().encodeToString(record))
        } catch (error: Exception) {
            failProtectedLocked(error)
            false
        }
    }

    internal fun abort(code: Int, reason: String) {
        val active: RelaySocket?
        synchronized(lock) {
            stopped = true
            generation += 1
            active = socket
            socket = null
            clearChannelLocked()
            cancelHandshakeRetryLocked()
            cancelReconnectLocked()
            setStateLocked(RelayConnectionState.ERROR)
        }
        active?.close(code, reason.take(120))
    }

    internal fun reconnect(reason: String) {
        synchronized(lock) {
            if (stopped) return
            clearChannelLocked()
            cancelHandshakeRetryLocked()
            socket?.close(4000, reason.take(120))
        }
    }

    fun stop() {
        val active: RelaySocket?
        synchronized(lock) {
            if (stopped) return
            stopped = true
            generation += 1
            active = socket
            socket = null
            clearChannelLocked()
            cancelReconnectLocked()
            cancelHandshakeRetryLocked()
            setStateLocked(RelayConnectionState.LOCAL)
        }
        active?.close(1000, "client stopped")
    }

    override fun close() = stop()

    private fun connect() {
        val currentGeneration: Long
        synchronized(lock) {
            if (stopped) return
            generation += 1
            currentGeneration = generation
            setStateLocked(RelayConnectionState.RELAY_CONNECTING)
        }
        val credentials = try { credentialsProvider.credentials() } catch (_: Exception) { null }
        if (credentials == null) {
            synchronized(lock) {
                if (currentGeneration == generation && !stopped) {
                    setStateLocked(RelayConnectionState.PAIRING_REQUIRED)
                    stopped = true
                }
            }
            return
        }
        try {
            socketFactory.connect(config.webSocketUrl(), object : RelaySocketCallbacks {
                override fun onOpen(socket: RelaySocket) {
                    synchronized(lock) {
                        if (stopped || currentGeneration != generation) {
                            socket.close(1000, "stale connection")
                            return
                        }
                        this@PaseoRelayClient.socket = socket
                        beginHandshakeLocked(credentials)
                    }
                }

                override fun onText(text: String) {
                    if (currentGeneration == generation) receiveText(text)
                }

                override fun onBinary(bytes: ByteArray) {
                    if (currentGeneration == generation) failChannel(4003, "binary relay frame rejected", RelayProtocolException("binary websocket frame rejected"))
                }

                override fun onClosed(code: Int, reason: String) {
                    if (currentGeneration == generation) connectionClosed(code, reason)
                }

                override fun onFailure(error: Throwable) {
                    if (currentGeneration == generation) connectionFailed(error)
                }
            })
        } catch (error: Exception) {
            connectionFailed(error)
        }
    }

    private fun beginHandshakeLocked(credentials: RelayCredentials) {
        clearChannelLocked()
        val noise = try {
            noiseFactory(credentials.remoteStaticPublicKey)
        } catch (error: Exception) {
            failHandshakeLocked(error)
            return
        }
        val firstMessage = try { noise.writeMessage(NoiseNkInitiator.encodeParameters(binaryCiphertext = true)) } catch (error: Exception) {
            failHandshakeLocked(error)
            return
        }
        val relayValidationPair = try { NoiseKeyPair.generate() } catch (error: Exception) {
            failHandshakeLocked(error)
            return
        }
        val relayValidationKey = relayValidationPair.publicKey
        relayValidationPair.privateKey.fill(0)
        val hello = JSONObject()
            .put("type", "e2ee_hello")
            .put("key", Base64.getEncoder().encodeToString(relayValidationKey))
            .put("capabilities", JSONObject().put("binaryCiphertext", true))
            .put("protected", JSONObject().put("v", 1).put("message", Base64.getEncoder().encodeToString(firstMessage)))
            .toString()
        handshake = noise
        handshakeText = hello
        if (!socketSendLocked(hello)) return
        cancelHandshakeRetryLocked()
        handshakeRetry = scheduler.scheduleAtFixedRate({
            synchronized(lock) {
                if (!stopped && handshake != null && socket != null && state == RelayConnectionState.RELAY_CONNECTING) {
                    socketSendLocked(handshakeText ?: return@scheduleAtFixedRate)
                }
            }
        }, HANDSHAKE_RETRY_MILLIS, HANDSHAKE_RETRY_MILLIS, TimeUnit.MILLISECONDS)
    }

    private fun receiveText(text: String) {
        synchronized(lock) {
            if (stopped || socket == null) return
            if (handshake != null) {
                if (text.length > MAX_HANDSHAKE_ENVELOPE_CHARS) {
                    failHandshakeLocked(RelayProtocolException("handshake envelope too large"))
                    return
                }
                receiveReadyLocked(text)
                return
            }
            if (text.length > MAX_BASE64_RECORD_CHARS) {
                failProtectedLocked(RelayProtocolException("protected record too large"))
                return
            }
            val receiver = receiveCipher ?: run {
                failProtectedLocked(RelayProtocolException("protected channel is not established"))
                return
            }
            try {
                val record = AtlasRemoteProtocol.decodeCanonicalBase64(text)
                val frame = ProtectedRecordCodec.decode(record, receiver)
                if (frame.kind != ProtectedFrameKind.TEXT) throw RelayProtocolException("binary application frame rejected")
                val decoder = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                val decoded = decoder.decode(ByteBuffer.wrap(frame.data)).toString().toByteArray(Charsets.UTF_8)
                if (decoded.size > AtlasRemoteProtocol.MAX_APPLICATION_FRAME_BYTES) {
                    throw RelayProtocolException("application frame too large")
                }
                listener.onApplicationFrame(decoded)
            } catch (error: Exception) {
                failProtectedLocked(error)
            }
        }
    }

    private fun receiveReadyLocked(text: String) {
        try {
            val envelope = JSONObject(text)
            if (envelope.optString("type") != "e2ee_ready") return failHandshakeLocked(RelayProtocolException("unexpected plaintext handshake frame"))
            if (!envelope.has("protected") || envelope.isNull("protected")) {
                return failHandshakeLocked(RelayProtocolException("protected mode required; legacy ready refused"))
            }
            val outerKeys = envelope.keys().asSequence().toSet()
            if (outerKeys.any { it !in setOf("type", "protected", "capabilities") }) {
                throw RelayProtocolException("unexpected e2ee_ready field")
            }
            if (envelope.has("capabilities")) {
                val capabilities = envelope.get("capabilities") as? JSONObject
                    ?: throw RelayProtocolException("invalid e2ee_ready capabilities")
                if (capabilities.keys().asSequence().any { it != "binaryCiphertext" } ||
                    (capabilities.has("binaryCiphertext") && capabilities.get("binaryCiphertext") !is Boolean)) {
                    throw RelayProtocolException("invalid e2ee_ready capabilities")
                }
            }
            val protected = envelope.get("protected") as? JSONObject
                ?: throw RelayProtocolException("invalid protected ready field")
            if (protected.keys().asSequence().toSet() != setOf("v", "message")) {
                throw RelayProtocolException("unexpected protected ready field")
            }
            val version = protected.get("v") as? Number
            val message = protected.get("message") as? String
            if (version == null || version.toDouble() != 1.0 || message.isNullOrEmpty()) {
                throw RelayProtocolException("invalid protected ready envelope")
            }
            val noise = handshake ?: throw RelayProtocolException("missing initiator handshake")
            val payload = noise.readMessage(AtlasRemoteProtocol.decodeCanonicalBase64(message))
            NoiseNkInitiator.validateParameters(payload)
            val keys = noise.split()
            sendCipher = keys.send
            receiveCipher = keys.receive
            handshake = null
            handshakeText = null
            cancelHandshakeRetryLocked()
            reconnectAttempt = 0
            setStateLocked(RelayConnectionState.RELAY_CONNECTED)
            listener.onSecureChannelReady()
        } catch (error: Exception) {
            failHandshakeLocked(error)
        }
    }

    private fun socketSendLocked(text: String): Boolean {
        val activeSocket = socket ?: return false
        return try {
            if (activeSocket.sendText(text)) true else {
                failHandshakeLocked(RelayProtocolException("websocket send rejected"))
                false
            }
        } catch (error: Exception) {
            failHandshakeLocked(error)
            false
        }
    }

    private fun failHandshakeLocked(error: Throwable) {
        val failure = if (error is NoiseProtocolException && error.message == NOISE_AUTHENTICATION_FAILURE) {
            PinnedNoiseIdentityMismatchException(error)
        } else {
            error
        }
        notifyFailure(failure)
        failChannelLocked(4002, "protected handshake failed", failure)
    }

    private fun failProtectedLocked(error: Throwable) {
        notifyFailure(error)
        failChannelLocked(4003, "protected record rejected", error)
    }

    private fun failChannel(code: Int, reason: String, error: Throwable) = synchronized(lock) {
        notifyFailure(error)
        failChannelLocked(code, reason, error)
    }

    private fun notifyFailure(error: Throwable) {
        listener.onProtocolFailure(error)
        listener.onRelayFailure(RelayFailure.fromThrowable(error))
    }

    private fun failChannelLocked(code: Int, reason: String, @Suppress("UNUSED_PARAMETER") error: Throwable) {
        cancelHandshakeRetryLocked()
        clearChannelLocked()
        socket?.close(code, reason)
    }

    private fun connectionClosed(code: Int, reason: String) {
        synchronized(lock) {
            if (stopped) return
            socket = null
            clearChannelLocked()
            cancelHandshakeRetryLocked()
            listener.onSocketClosed(code, reason.take(120))
            when (code) {
                4001 -> {
                    setStateLocked(RelayConnectionState.AUTH_REQUIRED)
                    stopped = true
                }
                else -> scheduleReconnectLocked()
            }
        }
    }

    private fun connectionFailed(error: Throwable) {
        synchronized(lock) {
            if (stopped) return
            socket = null
            clearChannelLocked()
            cancelHandshakeRetryLocked()
            notifyFailure(error)
            scheduleReconnectLocked()
        }
    }

    private fun scheduleReconnectLocked() {
        if (stopped) return
        if (reconnectAttempt >= config.maxReconnectAttempts) {
            setStateLocked(RelayConnectionState.OFFLINE)
            stopped = true
            return
        }
        val attempt = reconnectAttempt++
        val delay = RelayReconnectDelay.calculate(
            baseMillis = config.reconnectBaseMillis,
            maxMillis = config.reconnectMaxMillis,
            attempt = attempt,
            jitter = jitter,
        )
        setStateLocked(RelayConnectionState.RELAY_CONNECTING)
        cancelReconnectLocked()
        reconnectTask = scheduler.schedule({ connect() }, delay, TimeUnit.MILLISECONDS)
    }

    private fun setStateLocked(value: RelayConnectionState) {
        if (state == value) return
        state = value
        listener.onStateChanged(value)
    }

    private fun clearChannelLocked() {
        sendCipher?.clear()
        receiveCipher?.clear()
        sendCipher = null
        receiveCipher = null
        handshake?.destroy()
        handshake = null
        handshakeText = null
    }

    private fun cancelHandshakeRetryLocked() {
        handshakeRetry?.cancel(false)
        handshakeRetry = null
    }

    private fun cancelReconnectLocked() {
        reconnectTask?.cancel(false)
        reconnectTask = null
    }

    companion object {
        const val HANDSHAKE_RETRY_MILLIS = 1_000L
        private const val NOISE_AUTHENTICATION_FAILURE = "authentication failed"
        private const val MAX_HANDSHAKE_ENVELOPE_CHARS = 4_096
        private val MAX_BASE64_RECORD_CHARS = 4 * ((ProtectedRecordCodec.wireByteLength(ProtectedRecordCodec.MAX_FRAME_BYTES) + 2) / 3)
        private fun newDaemonScheduler(name: String): ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, name).apply { isDaemon = true }
        }
    }
}

internal object RelayReconnectDelay {
    fun calculate(baseMillis: Long, maxMillis: Long, attempt: Int, jitter: (Long) -> Long): Long {
        require(baseMillis > 0 && maxMillis >= baseMillis && attempt >= 0)
        val factor = 1L shl minOf(attempt, 30)
        val exponential = if (baseMillis > maxMillis / factor) maxMillis else baseMillis * factor
        val half = exponential / 2
        return (half + jitter(half)).coerceIn(0, maxMillis)
    }
}

internal interface RelaySocket {
    fun sendText(text: String): Boolean
    fun close(code: Int, reason: String)
}

internal interface RelaySocketCallbacks {
    fun onOpen(socket: RelaySocket)
    fun onText(text: String)
    fun onBinary(bytes: ByteArray)
    fun onClosed(code: Int, reason: String)
    fun onFailure(error: Throwable)
}

internal fun interface RelaySocketFactory {
    fun connect(url: String, callbacks: RelaySocketCallbacks)
}

internal class OkHttpRelaySocketFactory(config: RelayConfig, debugDnsOverride: Dns? = null) : RelaySocketFactory {
    private val client = OkHttpClient.Builder().apply {
        connectTimeout(config.connectTimeoutMillis, TimeUnit.MILLISECONDS)
        readTimeout(0, TimeUnit.MILLISECONDS)
        if (RelayBuildPolicy.isDebugBuild() && debugDnsOverride != null) dns(debugDnsOverride)
    }.build()

    override fun connect(url: String, callbacks: RelaySocketCallbacks) {
        val request = Request.Builder().url(url).build()
        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                callbacks.onOpen(object : RelaySocket {
                    override fun sendText(text: String): Boolean = webSocket.send(text)
                    override fun close(code: Int, reason: String) { webSocket.close(code, reason.take(120)) }
                })
            }
            override fun onMessage(webSocket: WebSocket, text: String) = callbacks.onText(text)
            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) = callbacks.onBinary(bytes.toByteArray())
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = callbacks.onClosed(code, reason)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = callbacks.onFailure(t)
        })
    }
}
