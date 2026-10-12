package com.pocketdisco.capturelab.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PcmWireTest {
    private val frame = AudioFrame(
        UUID.fromString("649cbb30-0d08-4e88-a08f-18d1854b9e1a"),
        45,
        10_000_000,
        2,
        ByteArray(PcmFormat.FRAME_BYTES) { it.toByte() },
    )

    @Test
    fun `round trip preserves header and little endian PCM bytes`() {
        val bytes = encode(frame)
        val decoded = PcmWire.readFrame(ByteArrayInputStream(bytes))
        assertEquals(687, bytes.size)
        assertEquals(frame.sessionId, decoded.sessionId)
        assertEquals(frame.sequence, decoded.sequence)
        assertEquals(frame.capturedNanos, decoded.capturedNanos)
        assertEquals(frame.hops, decoded.hops)
        assertArrayEquals(frame.pcm, decoded.pcm)
    }

    @Test
    fun `multiple frames retain their boundaries`() {
        val bytes = ByteArrayOutputStream()
        PcmWire.writeFrame(bytes, frame)
        PcmWire.writeFrame(bytes, frame.copy(sequence = 46))
        val input = ByteArrayInputStream(bytes.toByteArray())
        assertEquals(45L, PcmWire.readFrame(input).sequence)
        assertEquals(46L, PcmWire.readFrame(input).sequence)
        assertEquals(0, input.available())
    }

    @Test
    fun `format fields are strict`() {
        val edits = listOf<Pair<Int, Byte>>(0 to 0, 4 to 2, 5 to 1, 9 to 2, 10 to 4, 11 to 10)
        for ((offset, value) in edits) {
            val bytes = encode(frame).apply { this[offset] = value }
            assertThrows(IOException::class.java) { PcmWire.readFrame(ByteArrayInputStream(bytes)) }
        }
    }

    @Test
    fun `hop counts outside one through three are rejected`() {
        for (hops in listOf(0, 4, 255)) {
            val bytes = encode(frame).apply { this[44] = hops.toByte() }
            val error = assertThrows(IOException::class.java) { PcmWire.readFrame(ByteArrayInputStream(bytes)) }
            assertEquals("Invalid hop count", error.message)
        }
    }

    @Test
    fun `negative sequence and timestamp are rejected`() {
        for ((offset, message) in listOf(28 to "Invalid frame sequence", 36 to "Invalid frame timestamp")) {
            val bytes = encode(frame)
            ByteBuffer.wrap(bytes).putLong(offset, -1)
            val error = assertThrows(IOException::class.java) { PcmWire.readFrame(ByteArrayInputStream(bytes)) }
            assertEquals(message, error.message)
        }
    }

    @Test
    fun `invalid lengths fail before reading or allocating payload`() {
        for (length in listOf(0, 1, 639, 641, 65_535)) {
            val headerOnly = encode(frame).copyOf(47)
            ByteBuffer.wrap(headerOnly).putShort(45, length.toShort())
            val error = assertThrows(IOException::class.java) { PcmWire.readFrame(ByteArrayInputStream(headerOnly)) }
            assertEquals("Invalid PCM length", error.message)
        }
    }

    @Test
    fun `truncated header or payload is not a partial frame`() {
        val bytes = encode(frame)
        for (length in listOf(0, 1, 4, 10, 27, 46, 47, 100, bytes.size - 1)) {
            assertThrows(EOFException::class.java) {
                PcmWire.readFrame(ByteArrayInputStream(bytes.copyOf(length)))
            }
        }
    }

    @Test
    fun `writer checks the same format limits`() {
        for (invalid in listOf(
            frame.copy(sequence = -1),
            frame.copy(capturedNanos = -1),
            frame.copy(hops = 0),
            frame.copy(hops = 4),
            frame.copy(pcm = ByteArray(641)),
        )) {
            val bytes = ByteArrayOutputStream()
            assertThrows(IOException::class.java) { PcmWire.writeFrame(bytes, invalid) }
            assertEquals(0, bytes.size())
        }
    }

    @Test
    fun `join codes encode exactly 128 bits`() {
        assertArrayEquals(
            PcmWire.key("0123456789abcdef0123456789abcdef"),
            PcmWire.key("0123456789ABCDEF0123456789ABCDEF"),
        )
        assertEquals(16, PcmWire.key("0123456789abcdef0123456789abcdef").size)
        for (code in listOf("", "1234", "0123456789abcdef0123456789abcdeg", "0123456789abcdef0123456789abcdef ")) {
            assertThrows(IllegalArgumentException::class.java) { PcmWire.key(code) }
        }
    }

    private fun encode(value: AudioFrame): ByteArray = ByteArrayOutputStream().also {
        PcmWire.writeFrame(it, value)
    }.toByteArray()
}
