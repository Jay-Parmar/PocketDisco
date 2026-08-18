package dev.pocketdisco.phase0

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

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
        require(bearerToken.isNotBlank()) { "Coordinator bearer token is required" }
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
        return parseTrial(body.getJSONObject("trial"))
    }

    fun getTrial(trialId: String): CoordinatorTrial {
        val normalizedId = try {
            UUID.fromString(trialId.trim()).toString()
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Enter a valid coordinator trial UUID")
        }
        val body = request(method = "GET", path = "/v1/trials/$normalizedId")
        return parseTrial(body.getJSONObject("trial"))
    }

    private fun request(
        method: String,
        path: String,
        body: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): JSONObject {
        val connection = URL("$baseUrl$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $bearerToken")
            extraHeaders.forEach(connection::setRequestProperty)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
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
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun parseTrial(trial: JSONObject): CoordinatorTrial = CoordinatorTrial(
        id = UUID.fromString(trial.getString("id")).toString(),
        assetId = trial.getString("asset_id"),
        assetSha256 = ProbeInput.assetSha256(trial.getString("asset_sha256")),
        requestedPositionMs = trial.getLong("requested_position_ms"),
        effectiveAtUnixMs = trial.getLong("effective_at_unix_ms"),
        createdAtUnixMs = trial.getLong("created_at_unix_ms"),
    )

    companion object {
        private val IDEMPOTENCY_KEY = Regex("^[A-Za-z0-9._:-]{1,128}$")
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val READ_TIMEOUT_MS = 3_000
    }
}

class CoordinatorException(message: String) : Exception(message)
