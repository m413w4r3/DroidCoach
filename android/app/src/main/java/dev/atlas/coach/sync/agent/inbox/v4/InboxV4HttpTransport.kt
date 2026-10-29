package dev.atlas.coach.sync.agent.inbox.v4

import dev.atlas.coach.sync.v4.CoreV4HttpRequest
import dev.atlas.coach.sync.v4.CoreV4HttpResponse
import dev.atlas.coach.sync.v4.CoreV4SyncClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class InboxV4RawPullPage(
    val proposals: List<String>,
    val nextCursor: String?,
) {
    val truncated: Boolean get() = nextCursor != null
}

enum class InboxV4HttpFailureKind {
    AUTHENTICATION,
    CONSENT,
    CONFLICT,
    REJECTED,
    UNAVAILABLE,
    OFFLINE,
    PROTOCOL,
    CONFIGURATION,
}

class InboxV4HttpException(
    val kind: InboxV4HttpFailureKind,
    val retryable: Boolean,
    val statusCode: Int? = null,
    val errorCode: String? = null,
) : IOException(
    when (kind) {
        InboxV4HttpFailureKind.AUTHENTICATION -> "Authentification inbox V4 requise."
        InboxV4HttpFailureKind.CONSENT -> "Le consentement inbox V4 ne permet pas cet appel."
        InboxV4HttpFailureKind.CONFLICT -> "Le Core a refusé une opération inbox V4 conflictuelle."
        InboxV4HttpFailureKind.REJECTED -> "Le Core a refusé la requête inbox V4."
        InboxV4HttpFailureKind.UNAVAILABLE -> "Le service inbox V4 est temporairement indisponible."
        InboxV4HttpFailureKind.OFFLINE -> "Le Core inbox V4 est hors ligne."
        InboxV4HttpFailureKind.PROTOCOL -> "La réponse inbox V4 ne respecte pas le contrat."
        InboxV4HttpFailureKind.CONFIGURATION -> "La configuration inbox V4 n'est pas autorisée."
    } + (statusCode?.let { " HTTP $it." } ?: "") +
        (errorCode?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,64}")) }?.let { " ($it)" } ?: ""),
)

fun interface InboxV4HttpPoster {
    fun post(request: CoreV4HttpRequest): CoreV4HttpResponse
}

/** Android initiated V4 pull and ACK over two fixed routes and the configured backend origin. */
class InboxV4HttpTransport(
    baseUrl: String,
    private val bearerToken: String,
    private val consentVersion: Long,
    debuggable: Boolean,
    private val poster: InboxV4HttpPoster? = null,
    private val requestAllowed: (CoreV4HttpRequest) -> Boolean = { true },
    private val requestStart: ((CoreV4HttpRequest, () -> Unit) -> Boolean)? = null,
) : InboxV4Transport {
    private val base: String
    private val connectionLock = Any()
    private val failureLock = Any()
    private val observedFailures = mutableListOf<InboxV4HttpException>()
    private var cancelled = false
    private var activeConnection: HttpURLConnection? = null

    init {
        if (!CoreV4SyncClient.validBackendUrl(baseUrl, debuggable)) {
            throw InboxV4HttpException(InboxV4HttpFailureKind.CONFIGURATION, retryable = false)
        }
        if (bearerToken.isBlank() || bearerToken.length > MAX_TOKEN_LENGTH) {
            throw InboxV4HttpException(
                InboxV4HttpFailureKind.AUTHENTICATION,
                retryable = false,
                statusCode = 401,
            )
        }
        if (consentVersion < 1L) {
            throw InboxV4HttpException(InboxV4HttpFailureKind.CONSENT, retryable = false, statusCode = 403)
        }
        if (CONNECT_TIMEOUT_MILLIS !in 1..10_000 || READ_TIMEOUT_MILLIS !in 1..10_000) {
            throw InboxV4HttpException(InboxV4HttpFailureKind.CONFIGURATION, retryable = false)
        }
        base = baseUrl.trimEnd('/')
    }

    /** Keeps malformed and future proposal objects intact for the local quarantine path. */
    fun pullRaw(cursor: String?, pageSize: Int): InboxV4RawPullPage {
        val request = InboxV4PullRequest(cursor, pageSize)
        val requestId = UUID.randomUUID().toString()
        val body = JSONObject()
            .put("contract_version", CONTRACT_VERSION)
            .put("schema_version", SCHEMA_VERSION)
            .put("request_id", requestId)
            .put("capability", ACK_CAPABILITY)
            .put("consent_version", consentVersion)
            .put("page_size", request.pageSize)
            .put("cursor", request.cursor ?: JSONObject.NULL)
            .toString()
        val response = execute(PULL_ROUTE, body)
        val root = parseObject(response.body)
        if (root.keys().asSequence().toSet() != PULL_RESPONSE_FIELDS ||
            root.integralInt("contract_version") != CONTRACT_VERSION ||
            root.integralInt("schema_version") != SCHEMA_VERSION ||
            root.optString("request_id") != requestId ||
            root.optString("capability") != ACK_CAPABILITY
        ) protocolFailure()
        val proposals = root.opt("proposals") as? JSONArray ?: protocolFailure()
        if (proposals.length() > request.pageSize) protocolFailure()
        val nextCursor = when (val value = root.opt("next_cursor")) {
            null, JSONObject.NULL -> null
            is String -> value.takeIf { it.isNotBlank() && it.length <= MAX_CURSOR_LENGTH }
                ?: protocolFailure()
            else -> protocolFailure()
        }
        return InboxV4RawPullPage(
            proposals = (0 until proposals.length()).map { index ->
                when (val value = proposals.get(index)) {
                    is JSONObject -> value.toString()
                    else -> value.toString()
                }
            },
            nextCursor = nextCursor,
        )
    }

    override fun pull(cursor: String?, pageSize: Int): InboxV4PullPage {
        val page = pullRaw(cursor, pageSize)
        val proposals = page.proposals.map { raw ->
            when (val parsed = InboxV4ProposalParser.parse(raw)) {
                is InboxV4ProposalParseResult.Parsed -> parsed.proposal
                is InboxV4ProposalParseResult.Rejected,
                is InboxV4ProposalParseResult.Quarantined,
                -> throw InboxV4HttpException(InboxV4HttpFailureKind.PROTOCOL, retryable = false)
            }
        }
        return InboxV4PullPage(proposals, page.nextCursor, page.truncated)
    }

    override fun ack(ack: InboxV4Ack): InboxV4DeliveryResult {
        val route = ACK_ROUTE_PREFIX + ack.proposalId + ACK_ROUTE_SUFFIX
        return try {
            val response = execute(route, ack.canonicalBody())
            val echoed = runCatching { InboxV4AckParser.parse(response.body) }.getOrElse { protocolFailure() }
            if (echoed != ack) protocolFailure()
            InboxV4DeliveryResult.Delivered
        } catch (error: InboxV4HttpException) {
            synchronized(failureLock) { observedFailures += error }
            if (error.kind == InboxV4HttpFailureKind.OFFLINE) return InboxV4DeliveryResult.Offline
            throw error
        }
    }

    /** Typed failures consumed by InboxV4Repository while it preserves the pending outbox entry. */
    fun drainObservedFailures(): List<InboxV4HttpException> = synchronized(failureLock) {
        observedFailures.toList().also { observedFailures.clear() }
    }

    fun cancel() {
        val connection = synchronized(connectionLock) {
            cancelled = true
            activeConnection.also { activeConnection = null }
        }
        connection?.disconnect()
    }

    override fun toString(): String = "InboxV4HttpTransport(base=[configured], bearerToken=[redacted])"

    private fun execute(path: String, body: String): CoreV4HttpResponse {
        if (!isAllowedPath(path)) throw InboxV4HttpException(InboxV4HttpFailureKind.CONFIGURATION, false)
        if (body.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BYTES) protocolFailure()
        val request = CoreV4HttpRequest(path, bearerToken, body)
        if (synchronized(connectionLock) { cancelled } || !requestAllowed(request)) {
            throw InboxV4HttpException(InboxV4HttpFailureKind.CONSENT, retryable = false, statusCode = 403)
        }
        val response = try {
            (poster ?: InboxV4HttpPoster(::postConfigured)).post(request)
        } catch (error: InboxV4HttpException) {
            throw error
        } catch (_: IOException) {
            throw InboxV4HttpException(InboxV4HttpFailureKind.OFFLINE, retryable = true)
        } catch (_: Exception) {
            protocolFailure()
        }
        if (response.body.toByteArray(Charsets.UTF_8).size > MAX_RESPONSE_BYTES) protocolFailure()
        if (response.statusCode !in 200..299) {
            val code = runCatching {
                JSONObject(response.body).optJSONObject("error")?.optString("code")
            }.getOrNull()?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,64}")) }
            val status = response.statusCode
            val kind = when (status) {
                401 -> InboxV4HttpFailureKind.AUTHENTICATION
                403 -> InboxV4HttpFailureKind.CONSENT
                409 -> InboxV4HttpFailureKind.CONFLICT
                422 -> InboxV4HttpFailureKind.REJECTED
                408, 429, 503 -> InboxV4HttpFailureKind.UNAVAILABLE
                in 500..599 -> InboxV4HttpFailureKind.UNAVAILABLE
                else -> InboxV4HttpFailureKind.REJECTED
            }
            throw InboxV4HttpException(
                kind = kind,
                retryable = status in setOf(408, 429) || status in 500..599,
                statusCode = status,
                errorCode = code,
            )
        }
        return response
    }

    private fun postConfigured(request: CoreV4HttpRequest): CoreV4HttpResponse {
        val connection = try {
            URL(base + request.path).openConnection() as HttpURLConnection
        } catch (_: Exception) {
            throw InboxV4HttpException(InboxV4HttpFailureKind.OFFLINE, retryable = true)
        }
        val registered = synchronized(connectionLock) {
            if (cancelled) false else {
                activeConnection = connection
                true
            }
        }
        if (!registered) {
            connection.disconnect()
            throw InboxV4HttpException(InboxV4HttpFailureKind.CONSENT, retryable = false, statusCode = 403)
        }
        try {
            if (!requestAllowed(request)) {
                throw InboxV4HttpException(InboxV4HttpFailureKind.CONSENT, retryable = false, statusCode = 403)
            }
            connection.instanceFollowRedirects = false
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.setRequestProperty("Authorization", "Bearer " + request.bearerToken)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.doOutput = true
            var outputStream: java.io.OutputStream? = null
            val started = requestStart?.invoke(request) { outputStream = connection.outputStream } ?: run {
                if (requestAllowed(request) && synchronized(connectionLock) { !cancelled }) {
                    outputStream = connection.outputStream
                    true
                } else {
                    false
                }
            }
            if (!started) throw InboxV4HttpException(InboxV4HttpFailureKind.CONSENT, false, 403)
            checkNotNull(outputStream).use { stream -> stream.write(request.body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val source = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = source?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8_192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_RESPONSE_BYTES) protocolFailure()
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }.orEmpty()
            return CoreV4HttpResponse(status, text)
        } catch (error: InboxV4HttpException) {
            throw error
        } catch (_: IOException) {
            throw InboxV4HttpException(InboxV4HttpFailureKind.OFFLINE, retryable = true)
        } finally {
            synchronized(connectionLock) {
                if (activeConnection === connection) activeConnection = null
            }
            connection.disconnect()
        }
    }

    private fun isAllowedPath(path: String): Boolean =
        path == PULL_ROUTE || Regex("^${Regex.escape(ACK_ROUTE_PREFIX)}[0-9a-f-]{36}${Regex.escape(ACK_ROUTE_SUFFIX)}$")
            .matches(path)

    private fun parseObject(body: String): JSONObject = try {
        JSONObject(body)
    } catch (_: Exception) {
        protocolFailure()
    }

    private fun JSONObject.integralInt(key: String): Int? {
        val number = opt(key) as? Number ?: return null
        val value = number.toLong()
        return value.takeIf {
            number.toDouble() == value.toDouble() && it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
        }?.toInt()
    }

    private fun protocolFailure(): Nothing =
        throw InboxV4HttpException(InboxV4HttpFailureKind.PROTOCOL, retryable = false)

    companion object {
        const val PULL_ROUTE = "/v4/agent/proposals/pull"
        private const val ACK_ROUTE_PREFIX = "/v4/agent/proposals/"
        private const val ACK_ROUTE_SUFFIX = "/ack"
        const val PAGE_SIZE = 32
        const val MAX_REQUEST_BYTES = 65_536
        const val MAX_RESPONSE_BYTES = 262_144
        private const val MAX_TOKEN_LENGTH = 4_096
        private const val CONNECT_TIMEOUT_MILLIS = 4_000
        private const val READ_TIMEOUT_MILLIS = 4_000
        private val PULL_RESPONSE_FIELDS = setOf(
            "contract_version", "schema_version", "request_id", "capability", "proposals", "next_cursor",
        )
    }
}

/** One process-wide repository per app-private file, shared by the worker and plan UI. */
object InboxV4LocalStore {
    const val FILE_NAME = "agent-proposal-inbox-v4.json"
    private val repositories = ConcurrentHashMap<String, InboxV4Repository>()

    fun repository(file: java.io.File): InboxV4Repository = repositories.computeIfAbsent(file.absolutePath) {
        InboxV4Repository(FileInboxV4Storage(file))
    }
}
