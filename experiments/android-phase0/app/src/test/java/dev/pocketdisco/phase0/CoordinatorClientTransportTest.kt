package dev.pocketdisco.phase0

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class CoordinatorClientTransportTest {
    @Test
    fun rejectsUnsafeBearerTokensWithoutEchoingThem() {
        val invalidTokens = listOf(
            "",
            "contains space",
            "contains,comma",
            "contains=padding",
            "secret\r\nvalue",
            "secret\u00A3value",
            "a".repeat(129),
        )

        invalidTokens.forEach { token ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                CoordinatorClient("http://127.0.0.1:8765", token)
            }
            if (token.isNotEmpty()) assertFalse(error.toString().contains(token))
        }
    }

    @Test
    fun acceptsSafeBase64UrlBearerTokens() {
        listOf("a", "test-token_value", "a".repeat(128)).forEach { token ->
            CoordinatorClient("http://127.0.0.1:8765", token)
        }
    }

    @Test
    fun doesNotFollowCoordinatorRedirects() {
        val redirectedRequests = AtomicInteger()
        val redirected = server { exchange ->
            redirectedRequests.incrementAndGet()
            exchange.respond(timeResponse())
        }
        val origin = server { exchange ->
            exchange.responseHeaders.add(
                "Location",
                "http://127.0.0.1:${redirected.address.port}/v1/time",
            )
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        try {
            assertThrows(CoordinatorException::class.java) {
                client(origin).getTime()
            }
            assertEquals(0, redirectedRequests.get())
        } finally {
            origin.stop(0)
            redirected.stop(0)
        }
    }

    @Test
    fun rejectsOversizedFixedLengthResponses() {
        val body = oversizedTimeResponse()
        withServer({ exchange -> exchange.respond(body) }) { coordinator ->
            assertThrows(CoordinatorException::class.java) {
                coordinator.getTime()
            }
        }
    }

    @Test
    fun rejectsOversizedChunkedResponses() {
        val body = oversizedTimeResponse()
        withServer({ exchange -> exchange.respond(body, chunked = true) }) { coordinator ->
            assertThrows(CoordinatorException::class.java) {
                coordinator.getTime()
            }
        }
    }

    @Test
    fun enforcesOverallRequestDeadlineDuringTrickledResponse() {
        withServer({ exchange -> exchange.respondSlowly(timeResponse()) }) { coordinator ->
            assertThrows(CoordinatorException::class.java) {
                coordinator.getTime()
            }
        }
    }

    private fun client(server: HttpServer) = CoordinatorClient(
        "http://127.0.0.1:${server.address.port}",
        "test-token",
    )

    private fun withServer(
        handler: (HttpExchange) -> Unit,
        action: (CoordinatorClient) -> Unit,
    ) {
        val server = server(handler)
        try {
            action(client(server))
        } finally {
            server.stop(0)
        }
    }

    private fun server(handler: (HttpExchange) -> Unit): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/", handler)
            start()
        }

    private fun HttpExchange.respond(body: String, chunked: Boolean = false) {
        requestBody.use { it.readBytes() }
        val bytes = body.toByteArray(Charsets.UTF_8)
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(200, if (chunked) 0 else bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun HttpExchange.respondSlowly(body: String) {
        requestBody.use { it.readBytes() }
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(200, 0)
        try {
            responseBody.use { output ->
                body.chunked(10).forEachIndexed { index, chunk ->
                    output.write(chunk.toByteArray(Charsets.UTF_8))
                    output.flush()
                    if (index < body.length / 10) Thread.sleep(700)
                }
            }
        } catch (_: Exception) {
            close()
        }
    }

    private fun timeResponse() =
        """{"server_receive_unix_ms":10000,"server_send_unix_ms":10001}"""

    private fun oversizedTimeResponse() =
        """{"server_receive_unix_ms":10000,"server_send_unix_ms":10001,"padding":"${"x".repeat(4_096)}"}"""
}
