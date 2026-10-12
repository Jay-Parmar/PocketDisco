package com.pocketdisco.capturelab

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.pocketdisco.capturelab.protocol.PcmFormat
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class PcmSpeaker(context: Context, private val event: (String) -> Unit) : Closeable {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val queue = ArrayBlockingQueue<ByteArray>(25)
    private val running = AtomicBoolean(false)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change ->
            if (change < 0) {
                close()
                event("Receiver stopped after audio focus loss")
            }
        }.build()
    @Volatile private var track: AudioTrack? = null
    private var worker: Thread? = null
    val writtenBytes = AtomicLong()
    val droppedFrames = AtomicLong()
    @Volatile var playbackHeadFrames = 0L
        private set

    @Synchronized
    fun start() {
        if (running.get()) return
        check(audioManager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            "Android denied receiver audio focus"
        }
        try {
            val minimum = AudioTrack.getMinBufferSize(
                PcmFormat.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            check(minimum > 0) { "PCM output is unavailable" }
            val output = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(PcmFormat.SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(minimum, PcmFormat.FRAME_BYTES * 5))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = output
            check(output.state == AudioTrack.STATE_INITIALIZED) { "PCM output failed to initialize" }
            running.set(true)
            output.play()
            worker = Thread({ play(output) }, "lab-speaker").apply { isDaemon = true; start() }
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    fun offer(pcm: ByteArray) {
        if (!running.get() || !queue.offer(pcm.copyOf())) droppedFrames.incrementAndGet()
    }

    private fun play(output: AudioTrack) {
        try {
            while (isPlaying(output)) {
                val pcm = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                var offset = 0
                while (offset < pcm.size && isPlaying(output)) {
                    val count = output.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
                    check(count > 0) { "AudioTrack write failed: $count" }
                    offset += count
                    writtenBytes.addAndGet(count.toLong())
                }
                if (isPlaying(output)) {
                    playbackHeadFrames = output.playbackHeadPosition.toLong() and 0xffffffffL
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            if (isPlaying(output)) event(error.message ?: "Receiver output stopped")
        } finally {
            synchronized(this) {
                if (track === output) close()
            }
        }
    }

    private fun isPlaying(output: AudioTrack) = running.get() && track === output

    @Synchronized
    override fun close() {
        running.set(false)
        worker?.interrupt()
        worker = null
        track?.let { output ->
            runCatching { playbackHeadFrames = output.playbackHeadPosition.toLong() and 0xffffffffL }
            runCatching { output.pause() }
            runCatching { output.flush() }
            runCatching { output.release() }
        }
        track = null
        queue.clear()
        audioManager.abandonAudioFocusRequest(focus)
    }
}
