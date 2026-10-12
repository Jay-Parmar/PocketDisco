package com.pocketdisco.capturelab.source

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GeneratedToneTest {
    @Test
    fun pcmIsDeterministicAndQuiet() {
        val first = GeneratedTone.pcm(4)
        assertArrayEquals(first, GeneratedTone.pcm(4))
        assertEquals(128_000, first.size)
        val samples = ByteBuffer.wrap(first).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var peak = 0
        while (samples.hasRemaining()) peak = maxOf(peak, abs(samples.get().toInt()))
        assertTrue(peak in 1700..1800)
    }

    @Test
    fun eachSecondStartsWithFiftyMillisecondsOfSilence() {
        val bytes = GeneratedTone.pcm(4)
        for (second in 0 until 4) {
            val offset = second * GeneratedTone.SAMPLE_RATE * 2
            assertTrue(bytes.copyOfRange(offset, offset + 1600).all { it == 0.toByte() })
        }
    }

    @Test
    fun wavContainsMonoPcmWithMatchingLengths() {
        val wav = GeneratedTone.wav(4)
        val fields = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals(wav.size - 8, fields.getInt(4))
        assertEquals("WAVEfmt ", String(wav, 8, 8, Charsets.US_ASCII))
        assertEquals(16, fields.getInt(16))
        assertEquals(1, fields.getShort(20).toInt())
        assertEquals(1, fields.getShort(22).toInt())
        assertEquals(GeneratedTone.SAMPLE_RATE, fields.getInt(24))
        assertEquals(GeneratedTone.SAMPLE_RATE * 2, fields.getInt(28))
        assertEquals(2, fields.getShort(32).toInt())
        assertEquals(16, fields.getShort(34).toInt())
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        assertEquals(wav.size - 44, fields.getInt(40))
        assertArrayEquals(GeneratedTone.pcm(4), wav.copyOfRange(44, wav.size))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesEmptyTone() {
        GeneratedTone.pcm(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun boundsGeneratedFileSize() {
        GeneratedTone.wav(61)
    }
}
