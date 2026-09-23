package dev.pocketdisco.phase0

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Timer
import java.util.TimerTask
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class CoordinatorTime(
    val serverReceiveUnixMs: Long,
    val serverSendUnixMs: Long,
)

data class CoordinatorTrialRequest(
    val assetId: String,
    val assetSha256: String,
    val requestedPositionMs: Long,
    val effectiveAtUnixMs: Long,
)

data class CoordinatorTrial(
    val id: String,
    val assetId: String,
    val assetSha256: String,
    val requestedPositionMs: Long,
    val effectiveAtUnixMs: Long,
    val createdAtUnixMs: Long,
)

class CoordinatorClient(
    baseUrl: String,
    private val bearerToken: String,
) {
    private val baseUrl = ProbeInput.coordinatorBaseUrl(baseUrl)

    init {
        require(BEARER_TOKEN.matches(bearerToken)) { "Coordinator bearer token is invalid" }
    }

    fun getTime(): CoordinatorTime {
        val body = request(method = "GET", path = "/v1/time")
        return CoordinatorTime(
            serverReceiveUnixMs = body.getLong("server_receive_unix_ms"),
            serverSendUnixMs = body.getLong("server_send_unix_ms"),
        )
    }

    fun createTrial(request: CoordinatorTrialRequest, idempotencyKey: String): CoordinatorTrial {
        require(IDEMPOTENCY_KEY.matches(idempotencyKey)) { "Invalid idempotency key" }
        val requestBody = JSONObject()
            .put("asset_id", request.assetId)
            .put("asset_sha256", request.assetSha256)
            .put("requested_position_ms", request.requestedPositionMs)
            .put("effective_at_unix_ms", request.effectiveAtUnixMs)
            .toString()
        val body = request(
            method = "POST",
            path = "/v1/trials",
            body = requestBody,
            extraHeaders = mapOf("Idempotency-Key" to idempotencyKey),
        )
        return parseTrial(
            trial = body.getJSONObject("trial"),
            expectedTrialId = null,
            expectedRequest = request,
        )
    }

    fun getTrial(trialId: String): CoordinatorTrial {
        val normalizedId = try {
            UUID.fromString(trialId.trim()).toString()
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Enter a valid coordinator trial UUID")
        }
        val body = request(method = "GET", path = "/v1/trials/$normalizedId")
        return parseTrial(
            trial = body.getJSONObject("trial"),
            expectedTrialId = normalizedId,
            expectedRequest = null,
        )
    }

    fun createYouTubeTrial(
        trial: YouTubeControlTrialRequest,
        idempotencyKey: String,
    ): YouTubeControlTrial {
        require(IDEMPOTENCY_KEY.matches(idempotencyKey)) { "Invalid idempotency key" }
        val requestBody = JSONObject()
            .put("item_type", trial.itemType.wireValue)
            .put("item_id", trial.itemId)
            .put("requested_position_ms", trial.requestedPositionMs)
            .put("effective_at_unix_ms", trial.effectiveAtUnixMs)
            .toString()
        val body = request(
            method = "POST",
            path = "/v1/youtube-trials",
            body = requestBody,
            extraHeaders = mapOf("Idempotency-Key" to idempotencyKey),
        )
        return parseYouTubeTrial(body.getJSONObject("trial"))
    }

    fun getYouTubeTrial(trialId: String): YouTubeControlTrial {
        val normalizedId = try {
            UUID.fromString(trialId.trim()).toString()
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Enter a valid YouTube trial UUID")
        }
        val body = request(method = "GET", path = "/v1/youtube-trials/$normalizedId")
        return parseYouTubeTrial(body.getJSONObject("trial"))
    }

    private fun request(
        method: String,
        path: String,
        body: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): JSONObject {
        val connection = URL("$baseUrl$path").openConnection() as HttpURLConnection
        val timedOut = AtomicBoolean(false)
        val timeoutTask = object : TimerTask() {
            override fun run() {
                timedOut.set(true)
                connection.disconnect()
            }
        }
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $bearerToken")
            extraHeaders.forEach(connection::setRequestProperty)
            REQUEST_TIMEOUT_TIMER.schedule(timeoutTask, REQUEST_TIMEOUT_MS.toLong())
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            }

            val status = connection.responseCode
            if (connection.contentLengthLong > MAXIMUM_RESPONSE_BYTES) {
                throw CoordinatorException("Coordinator response exceeded the size limit")
            }
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = readResponseBody(stream, timedOut)
            if (timedOut.get()) throw CoordinatorException("Coordinator request timed out")
            val response = try {
                JSONObject(responseBody)
            } catch (_: Exception) {
                throw CoordinatorException("Coordinator returned invalid JSON with HTTP $status")
            }
            if (status !in 200..299) {
                val error = response.optJSONObject("error")
                val code = error?.optString("code").orEmpty().ifBlank { "http_$status" }
                val message = error?.optString("message").orEmpty().ifBlank { "Coordinator request failed" }
                throw CoordinatorException("$code: $message")
            }
            if (timedOut.get()) throw CoordinatorException("Coordinator request timed out")
            return response
        } catch (_: IOException) {
            if (timedOut.get()) throw CoordinatorException("Coordinator request timed out")
            throw CoordinatorException("Coordinator request failed")
        } finally {
            timeoutTask.cancel()
            connection.disconnect()
        }
    }

    private fun readResponseBody(stream: InputStream?, timedOut: AtomicBoolean): String {
        if (stream == null) return ""
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(RESPONSE_CHUNK_BYTES)
        stream.use {
            while (true) {
                if (timedOut.get()) throw CoordinatorException("Coordinator request timed out")
                val read = it.read(buffer)
                if (read == -1) break
                if (output.size() + read > MAXIMUM_RESPONSE_BYTES) {
                    throw CoordinatorException("Coordinator response exceeded the size limit")
                }
                output.write(buffer, 0, read)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun parseTrial(
        trial: JSONObject,
        expectedTrialId: String?,
        expectedRequest: CoordinatorTrialRequest?,
    ): CoordinatorTrial {
        val parsed = CoordinatorTrial(
            id = try {
                UUID.fromString(trial.getString("id")).toString()
            } catch (_: IllegalArgumentException) {
                throw CoordinatorException("Coordinator returned invalid trial data")
            },
            assetId = trial.getString("asset_id"),
            assetSha256 = ProbeInput.assetSha256(trial.getString("asset_sha256")),
            requestedPositionMs = trial.getLong("requested_position_ms"),
            effectiveAtUnixMs = trial.getLong("effective_at_unix_ms"),
            createdAtUnixMs = trial.getLong("created_at_unix_ms"),
        )
        val creationLeadMs = try {
            Math.subtractExact(parsed.effectiveAtUnixMs, parsed.createdAtUnixMs)
        } catch (_: ArithmeticException) {
            throw CoordinatorException("Coordinator returned invalid trial data")
        }
        if (creationLeadMs !in MINIMUM_TRIAL_LEAD_MS..MAXIMUM_TRIAL_LEAD_MS) {
            throw CoordinatorException("Coordinator returned invalid trial data")
        }
        if (expectedTrialId != null && parsed.id != expectedTrialId) {
            throw CoordinatorException("Coordinator returned a different trial")
        }
        if (
            expectedRequest != null && (
                parsed.assetId != expectedRequest.assetId ||
                    !parsed.assetSha256.equals(expectedRequest.assetSha256, ignoreCase = true) ||
                    parsed.requestedPositionMs != expectedRequest.requestedPositionMs ||
                    parsed.effectiveAtUnixMs != expectedRequest.effectiveAtUnixMs
                )
        ) {
            throw CoordinatorException("Coordinator trial does not match the request")
        }
        return parsed
    }

    private fun parseYouTubeTrial(trial: JSONObject): YouTubeControlTrial = YouTubeControlTrial.parse(
        id = trial.getString("id"),
        itemType = trial.getString("item_type"),
        itemId = trial.getString("item_id"),
        requestedPositionMs = trial.getLong("requested_position_ms"),
        effectiveAtUnixMs = trial.getLong("effective_at_unix_ms"),
        createdAtUnixMs = trial.getLong("created_at_unix_ms"),
    )

    companion object {
        private val IDEMPOTENCY_KEY = Regex("^[A-Za-z0-9._:-]{1,128}$")
        private val BEARER_TOKEN = Regex("^[A-Za-z0-9_-]{1,128}$")
        private val REQUEST_TIMEOUT_TIMER = Timer("pocketdisco-coordinator-timeout", true)
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val READ_TIMEOUT_MS = 3_000
        private const val REQUEST_TIMEOUT_MS = 3_000
        private const val MAXIMUM_RESPONSE_BYTES = 4_096
        private const val RESPONSE_CHUNK_BYTES = 1_024
        private const val MINIMUM_TRIAL_LEAD_MS = 2_000L
        private const val MAXIMUM_TRIAL_LEAD_MS = 30_000L
    }
}

class CoordinatorException(message: String) : Exception(message)
