package dev.pocketdisco.phase0

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetSocketAddress
import java.util.UUID

class CoordinatorClientTrialValidationTest {
    @Test
    fun rejectsFetchedTrialWithAnotherId() {
        val requestedId = UUID.fromString("11111111-2222-3333-4444-555555555555")
        val returnedId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        withCoordinator(trialResponse(returnedId, createdAtMs = 10_000, effectiveAtMs = 12_000)) { client ->
            assertThrows(CoordinatorException::class.java) {
                client.getTrial(requestedId.toString())
            }
        }
    }

    @Test
    fun rejectsCreatedTrialThatDoesNotMatchTheRequest() {
        val request = request(effectiveAtMs = 35_000)
        val responses = listOf(
            trialResponse(assetId = "other-signal", createdAtMs = 10_000, effectiveAtMs = 35_000),
            trialResponse(assetSha256 = "a".repeat(64), createdAtMs = 10_000, effectiveAtMs = 35_000),
            trialResponse(requestedPositionMs = 1, createdAtMs = 10_000, effectiveAtMs = 35_000),
            trialResponse(createdAtMs = 10_000, effectiveAtMs = 34_999),
        )

        responses.forEach { response ->
            withCoordinator(response) { client ->
                assertThrows(CoordinatorException::class.java) {
                    client.createTrial(request, "android-trial-1")
                }
            }
        }
    }

    @Test
    fun enforcesCoordinatorCreationLeadBoundaries() {
        val trialId = UUID.fromString("11111111-2222-3333-4444-555555555555")
        listOf(1_999L, 30_001L).forEach { leadMs ->
            withCoordinator(trialResponse(trialId, createdAtMs = 10_000, effectiveAtMs = 10_000 + leadMs)) { client ->
                assertThrows(CoordinatorException::class.java) {
                    client.getTrial(trialId.toString())
                }
            }
        }

        listOf(2_000L, 30_000L).forEach { leadMs ->
            withCoordinator(trialResponse(trialId, createdAtMs = 10_000, effectiveAtMs = 10_000 + leadMs)) { client ->
                val trial = client.getTrial(trialId.toString())
                assertEquals(10_000 + leadMs, trial.effectiveAtUnixMs)
            }
        }
    }

    private fun request(effectiveAtMs: Long) = CoordinatorTrialRequest(
        assetId = ClickSignal.SIGNAL_ID,
        assetSha256 = ClickSignal.PCM_SHA256,
        requestedPositionMs = 0,
        effectiveAtUnixMs = effectiveAtMs,
    )

    private fun trialResponse(
        id: UUID = UUID.fromString("11111111-2222-3333-4444-555555555555"),
        assetId: String = ClickSignal.SIGNAL_ID,
        assetSha256: String = ClickSignal.PCM_SHA256,
        requestedPositionMs: Long = 0,
        createdAtMs: Long,
        effectiveAtMs: Long,
    ): String = """
        {
          "trial": {
            "id": "$id",
            "asset_id": "$assetId",
            "asset_sha256": "$assetSha256",
            "requested_position_ms": $requestedPositionMs,
            "effective_at_unix_ms": $effectiveAtMs,
            "created_at_unix_ms": $createdAtMs
          }
        }
    """.trimIndent()

    private fun withCoordinator(response: String, action: (CoordinatorClient) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> exchange.respond(response) }
        server.start()
        try {
            CoordinatorClient(
                "http://127.0.0.1:${server.address.port}",
                "test-token",
            ).let(action)
        } finally {
            server.stop(0)
        }
    }

    private fun HttpExchange.respond(body: String) {
        requestBody.use { it.readBytes() }
        val bytes = body.toByteArray(Charsets.UTF_8)
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
