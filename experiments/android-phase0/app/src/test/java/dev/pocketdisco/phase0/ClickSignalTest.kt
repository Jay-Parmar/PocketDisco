package dev.pocketdisco.phase0

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class ClickSignalTest {
    @Test
    fun createsOneSecondMonoSignal() {
        val signal = ClickSignal.create()

        assertEquals(48_000, signal.samples.size)
        assertEquals(48_000, signal.sampleRateHz)
        assertEquals(8_192, signal.samples[0].toInt())
        assertEquals(-7_987, signal.samples[24].toInt())
        assertEquals(-8, signal.samples[959].toInt())
        assertTrue(signal.samples.take(960).any { it.toInt() != 0 })
        assertTrue(signal.samples.drop(960).all { it.toInt() == 0 })
    }

    @Test
    fun producesDeterministicSamples() {
        assertTrue(
            ClickSignal.create().samples.contentEquals(
                ClickSignal.create().samples,
            ),
        )
    }

    @Test
    fun matchesGeneratedClickIdentityAndDigest() {
        val signal = ClickSignal.create()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(signal.pcm16LittleEndian())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

        assertEquals("generated-click-v1", ClickSignal.SIGNAL_ID)
        assertEquals(96_000, signal.pcm16LittleEndian().size)
        assertEquals(ClickSignal.PCM_SHA256, digest)
    }
}
