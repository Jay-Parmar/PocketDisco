package com.pocketdisco.capturelab.source

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

object GeneratedTone {
    const val SAMPLE_RATE = 16_000

    fun pcm(seconds: Int): ByteArray {
        require(seconds in 1..60)
        val samples = ByteBuffer.allocate(SAMPLE_RATE * seconds * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until SAMPLE_RATE * seconds) {
            val beat = index % SAMPLE_RATE
            val frequency = when ((index / SAMPLE_RATE) % 4) {
                0 -> 220.0
                1 -> 330.0
                2 -> 440.0
                else -> 550.0
            }
            val envelope = minOf(beat / 160.0, (SAMPLE_RATE - beat) / 160.0, 1.0)
            val value = if (beat < 800) 0 else (sin(2 * PI * frequency * index / SAMPLE_RATE) * 1800 * envelope).toInt()
            samples.putShort(value.toShort())
        }
        return samples.array()
    }

    fun wav(seconds: Int): ByteArray {
        val pcm = pcm(seconds)
        val file = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        file.put("RIFF".toByteArray(Charsets.US_ASCII))
        file.putInt(36 + pcm.size)
        file.put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        file.putInt(16)
        file.putShort(1)
        file.putShort(1)
        file.putInt(SAMPLE_RATE)
        file.putInt(SAMPLE_RATE * 2)
        file.putShort(2)
        file.putShort(16)
        file.put("data".toByteArray(Charsets.US_ASCII))
        file.putInt(pcm.size)
        file.put(pcm)
        return file.array()
    }
}
