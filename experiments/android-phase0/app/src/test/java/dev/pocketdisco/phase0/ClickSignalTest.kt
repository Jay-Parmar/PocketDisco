package dev.pocketdisco.phase0

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClickSignalTest {
    @Test
    fun createsOneSecondMonoSignal() {
        val signal = ClickSignal.create(sampleRateHz = 48_000)

        assertEquals(48_000, signal.samples.size)
        assertEquals(48_000, signal.sampleRateHz)
        assertTrue(signal.samples.take(960).any { it.toInt() != 0 })
        assertTrue(signal.samples.drop(960).all { it.toInt() == 0 })
    }

    @Test
    fun producesDeterministicSamples() {
        assertTrue(
            ClickSignal.create(sampleRateHz = 44_100).samples.contentEquals(
                ClickSignal.create(sampleRateHz = 44_100).samples,
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedSampleRates() {
        ClickSignal.create(sampleRateHz = 0)
    }
}
