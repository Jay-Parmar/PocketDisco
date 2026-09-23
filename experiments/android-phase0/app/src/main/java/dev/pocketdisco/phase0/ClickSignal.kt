package dev.pocketdisco.phase0

class ClickSignal private constructor(
    val sampleRateHz: Int,
    val samples: ShortArray,
) {
    fun pcm16LittleEndian(): ByteArray = ByteArray(samples.size * Short.SIZE_BYTES).also { bytes ->
        samples.forEachIndexed { index, sample ->
            val value = sample.toInt()
            bytes[index * Short.SIZE_BYTES] = (value and 0xff).toByte()
            bytes[index * Short.SIZE_BYTES + 1] = ((value ushr 8) and 0xff).toByte()
        }
    }

    companion object {
        const val SIGNAL_ID = "generated-click-v1"
        const val PCM_SHA256 = "e3c4db9cce24fdeb8cfc9131f0240665c54afde2519d99a59b91db98602274f0"
        const val SAMPLE_RATE_HZ = 48_000

        private const val CLICK_SAMPLES = SAMPLE_RATE_HZ / 50
        private const val HALF_WAVE_SAMPLES = SAMPLE_RATE_HZ / 2_000
        private const val PEAK_AMPLITUDE = 8_192

        fun create(): ClickSignal {
            val samples = ShortArray(SAMPLE_RATE_HZ)
            repeat(CLICK_SAMPLES) { index ->
                val level = PEAK_AMPLITUDE * (CLICK_SAMPLES - index) / CLICK_SAMPLES
                val polarity = if ((index / HALF_WAVE_SAMPLES) % 2 == 0) 1 else -1
                samples[index] = (level * polarity).toShort()
            }
            return ClickSignal(SAMPLE_RATE_HZ, samples)
        }
    }
}
