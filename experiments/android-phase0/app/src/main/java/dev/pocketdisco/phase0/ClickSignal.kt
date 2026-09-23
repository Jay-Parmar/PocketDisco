package dev.pocketdisco.phase0

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

class ClickSignal private constructor(
    val sampleRateHz: Int,
    val samples: ShortArray,
) {
    companion object {
        private const val DURATION_SECONDS = 1
        private const val CLICK_DURATION_MS = 20
        private const val FREQUENCY_HZ = 1_000.0
        private const val AMPLITUDE = 0.8

        fun create(sampleRateHz: Int = 48_000): ClickSignal {
            require(sampleRateHz >= 8_000) { "Sample rate must be at least 8000 Hz" }
            val samples = ShortArray(sampleRateHz * DURATION_SECONDS)
            val clickSamples = sampleRateHz * CLICK_DURATION_MS / 1_000
            repeat(clickSamples) { index ->
                val timeSeconds = index.toDouble() / sampleRateHz
                val envelope = exp(-6.0 * index / clickSamples)
                val value = sin(2.0 * PI * FREQUENCY_HZ * timeSeconds) * envelope * AMPLITUDE
                samples[index] = (value * Short.MAX_VALUE).roundToInt().toShort()
            }
            return ClickSignal(sampleRateHz, samples)
        }
    }
}
