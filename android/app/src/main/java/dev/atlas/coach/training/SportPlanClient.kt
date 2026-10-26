package dev.atlas.coach.training

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.ZoneId

data class SportPlanHttpRequest(
    val url: String,
    val authorization: String,
    val connectTimeoutMillis: Int,
    val readTimeoutMillis: Int,
    val maxResponseBytes: Int,
) {
    override fun toString(): String =
        "SportPlanHttpRequest(url=$url, authorization=[redacted], " +
            "connectTimeoutMillis=$connectTimeoutMillis, readTimeoutMillis=$readTimeoutMillis, " +
            "maxResponseBytes=$maxResponseBytes)"
}

data class SportPlanHttpResponse(val statusCode: Int, val body: ByteArray)

fun interface SportPlanTransport {
    fun get(request: SportPlanHttpRequest): SportPlanHttpResponse
}

/** Authenticated, bounded GET client for the sport-plan-v1 endpoint. */
class SportPlanClient(
    private val transport: SportPlanTransport = UrlConnectionSportPlanTransport(),
    private val timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    private val maxResponseBytes: Int = MAX_RESPONSE_BYTES,
) {
    init {
        require(timeoutMillis in 100..30_000) { "timeoutMillis must be in 100..30000" }
        require(maxResponseBytes in 1..MAX_RESPONSE_BYTES) { "maxResponseBytes must be in 1..$MAX_RESPONSE_BYTES" }
    }

    fun fetch(
        baseUrl: String,
        bearerToken: String,
        localDate: String,
        timezone: String,
    ): SportPlanUiState {
        val requestUrl = try {
            LocalDate.parse(localDate)
            ZoneId.of(timezone)
            require(bearerToken.isNotBlank() && bearerToken.none { it.isWhitespace() || it.code < 32 || it.code == 127 })
            buildUrl(baseUrl, localDate, timezone)
        } catch (_: Exception) {
            return unavailable()
        }

        val request = SportPlanHttpRequest(
            url = requestUrl,
            authorization = "Bearer $bearerToken",
            connectTimeoutMillis = timeoutMillis,
            readTimeoutMillis = timeoutMillis,
            maxResponseBytes = maxResponseBytes,
        )
        return try {
            val response = transport.get(request)
            if (response.body.size > maxResponseBytes) return mismatch()
            if (response.statusCode !in 200..299) return stateForHttpStatus(response.statusCode)
            when (val parsed = SportPlanJson.parse(String(response.body, StandardCharsets.UTF_8))) {
                is SportPlanParseResult.Invalid -> mismatch()
                is SportPlanParseResult.Valid -> {
                    val plan = parsed.plan
                    if (plan.localDate != localDate || plan.timezone != timezone) {
                        SportPlanUiState(
                            plan = null,
                            syncState = SyncState.MISMATCH,
                            source = plan.source,
                        )
                    } else {
                        SportPlanUiState(
                            plan = plan,
                            syncState = plan.syncState,
                            lastSyncAt = plan.generatedAt,
                            source = plan.source,
                        )
                    }
                }
            }
        } catch (_: ResponseTooLargeException) {
            mismatch()
        } catch (_: SocketTimeoutException) {
            stale()
        } catch (_: Exception) {
            unavailable()
        }
    }

    private fun buildUrl(baseUrl: String, localDate: String, timezone: String): String {
        val base = URI(baseUrl)
        require(
            base.scheme in setOf("http", "https") && !base.host.isNullOrBlank() &&
                base.rawUserInfo == null && base.rawQuery == null && base.rawFragment == null,
        ) { "baseUrl must be an HTTP(S) origin or path" }
        val encodedDate = URLEncoder.encode(localDate, StandardCharsets.UTF_8.name())
        val encodedTimezone = URLEncoder.encode(timezone, StandardCharsets.UTF_8.name())
        return "${base.toASCIIString().trimEnd('/')}/v1/sport/plan" +
            "?local_date=$encodedDate&timezone=$encodedTimezone"
    }

    private fun stateForHttpStatus(statusCode: Int): SportPlanUiState = when {
        statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode in 500..599 -> stale()
        statusCode == 404 -> mismatch()
        statusCode in 400..499 -> unavailable()
        else -> mismatch()
    }

    private fun unavailable() = SportPlanUiState(plan = null, syncState = SyncState.UNAVAILABLE)

    private fun stale() = SportPlanUiState(plan = null, syncState = SyncState.STALE)

    private fun mismatch() = SportPlanUiState(plan = null, syncState = SyncState.MISMATCH)

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 5_000
        const val MAX_RESPONSE_BYTES = 131_072
    }
}

private class ResponseTooLargeException : IOException()

private class UrlConnectionSportPlanTransport : SportPlanTransport {
    override fun get(request: SportPlanHttpRequest): SportPlanHttpResponse {
        val connection = URL(request.url).openConnection() as? HttpURLConnection
            ?: throw IOException("unsupported URL connection")
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = request.connectTimeoutMillis
            connection.readTimeout = request.readTimeoutMillis
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", request.authorization)
            connection.setRequestProperty("Accept", "application/json")
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) return SportPlanHttpResponse(statusCode, ByteArray(0))
            val declaredLength = connection.getHeaderFieldLong("Content-Length", -1L)
            if (declaredLength > request.maxResponseBytes) throw ResponseTooLargeException()
            val body = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(minOf(request.maxResponseBytes, 8_192))
                val buffer = ByteArray(8_192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > request.maxResponseBytes) throw ResponseTooLargeException()
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return SportPlanHttpResponse(statusCode, body)
        } finally {
            connection.disconnect()
        }
    }
}
