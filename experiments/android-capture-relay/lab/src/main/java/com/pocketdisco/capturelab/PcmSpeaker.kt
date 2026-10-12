package com.pocketdisco.capturelab

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import com.pocketdisco.capturelab.protocol.PcmFormat
import com.pocketdisco.capturelab.protocol.TimedAudioFrame
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

class PcmSpeaker(context: Context, private val event: (String) -> Unit) : Closeable {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val planner = PlayoutPlanner()
    private val running = AtomicBoolean(false)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
        .build()
    private var focus: AudioFocusRequest? = null
    private var lifecycle = 0L
    @Volatile private var track: AudioTrack? = null
    private var worker: Thread? = null
    val writtenBytes = AtomicLong()
    val droppedFrames = AtomicLong()
    @Volatile var playbackHeadFrames = 0L
        private set

    @Synchronized
    fun start() {
        if (running.get()) return
        val currentLifecycle = ++lifecycle
        try {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { change ->
                    if (change < 0) {
                        val stopped = synchronized(this) {
                            if (lifecycle != currentLifecycle || !running.get()) false
                            else { close(); true }
                        }
                        if (stopped) event("Receiver stopped after audio focus loss")
                    }
                }.build()
            focus = request
            check(audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                "Android denied receiver audio focus"
            }
            val minimum = AudioTrack.getMinBufferSize(
                PcmFormat.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            check(minimum > 0) { "PCM output is unavailable" }
            val bytes = maxOf(minimum, PcmFormat.FRAME_BYTES * 8)
            val output = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(PcmFormat.SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(((bytes + PcmFormat.FRAME_BYTES - 1) / PcmFormat.FRAME_BYTES) * PcmFormat.FRAME_BYTES)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = output
            check(output.state == AudioTrack.STATE_INITIALIZED) { "PCM output failed to initialize" }
            val prefillBytes = output.bufferSizeInFrames * PcmFormat.BYTES_PER_SAMPLE
            planner.start((prefillBytes + PcmFormat.FRAME_BYTES - 1) / PcmFormat.FRAME_BYTES)
            writtenBytes.set(0)
            droppedFrames.set(0)
            playbackHeadFrames = 0
            running.set(true)
            worker = Thread({ play(output, prefillBytes) }, "lab-speaker").apply { isDaemon = true; start() }
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    @Synchronized
    fun offer(frame: TimedAudioFrame) {
        val epoch = planner.epoch()
        planner.offer(frame)
        if (planner.epoch() != epoch) track?.let { runCatching { it.pause() } }
        updateDrops()
    }

    @Synchronized
    fun invalidateTiming() {
        planner.invalidateTiming()
        track?.let { runCatching { it.pause() } }
        updateDrops()
    }

    fun timingSnapshot() = planner.snapshot()

    private fun play(output: AudioTrack, prefillBytes: Int) {
        var epoch = planner.epoch()
        var pending: PlayoutChunk? = null
        var offset = 0
        var totalBytes = 0L
        var playing = false
        var underruns = 0
        var nextObservation = 0L
        val timestamp = AudioTimestamp()
        try {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
            while (isCurrent(output) && !Thread.currentThread().isInterrupted) {
                if (planner.failed()) {
                    event(if (planner.snapshot().timingState == "timestamp_unavailable") {
                        "Receiver timing unavailable: Android supplied no fresh audio timestamps. Stop and reconnect, or try a physical phone."
                    } else {
                        "Receiver timing could not settle. Stop and reconnect after checking the audio route."
                    })
                    break
                }
                if (epoch != planner.epoch()) {
                    synchronized(this) {
                        if (isCurrent(output)) {
                            output.pause()
                            val head = output.playbackHeadPosition.toLong() and 0xffffffffL
                            val remaining = (totalBytes / PcmFormat.BYTES_PER_SAMPLE - head).coerceAtLeast(0)
                            planner.discardOutput((remaining + SAMPLES_PER_FRAME - 1) / SAMPLES_PER_FRAME)
                            output.flush()
                            playbackHeadFrames = 0
                        }
                    }
                    epoch = planner.epoch()
                    pending = null
                    offset = 0
                    totalBytes = 0
                    playing = false
                    nextObservation = 0
                    continue
                }
                val now = System.nanoTime()
                if (!playing && totalBytes >= prefillBytes) {
                    val deadline = planner.startDeadline(epoch)
                    if (deadline != null && now >= deadline) {
                        synchronized(this) {
                            if (isCurrent(output) && planner.beginPlayback(epoch)) {
                                underruns = output.underrunCount
                                output.play()
                                playing = true
                            }
                        }
                    } else {
                        LockSupport.parkNanos(minOf((deadline ?: now + POLL_NANOS) - now, POLL_NANOS).coerceAtLeast(1))
                    }
                } else {
                    if (pending == null) pending = planner.next()
                    val chunk = pending
                    if (chunk != null) {
                        val count = synchronized(this) {
                            if (!isCurrent(output) || chunk.epoch != planner.epoch()) 0
                            else output.write(chunk.pcm, offset, chunk.pcm.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                                .also { if (it > 0) writtenBytes.addAndGet(it.toLong()) }
                        }
                        check(count >= 0) { "Receiver audio output failed" }
                        offset += count
                        totalBytes += count
                        if (offset == chunk.pcm.size) { pending = null; offset = 0 }
                        if (count == 0) LockSupport.parkNanos(POLL_NANOS)
                    } else LockSupport.parkNanos(POLL_NANOS)
                }
                if (playing && now >= nextObservation) {
                    synchronized(this) {
                        if (isCurrent(output) && epoch == planner.epoch()) {
                            playbackHeadFrames = output.playbackHeadPosition.toLong() and 0xffffffffL
                            if (output.underrunCount > underruns) {
                                planner.rebuffer()
                                output.pause()
                            } else if (output.getTimestamp(timestamp) && timestamp.framePosition <= totalBytes / PcmFormat.BYTES_PER_SAMPLE) {
                                planner.timestamp(epoch, timestamp.framePosition, timestamp.nanoTime)
                                if (planner.epoch() != epoch) output.pause()
                            }
                            planner.checkTimestamp()
                            if (planner.failed()) output.pause()
                        }
                    }
                    nextObservation = now + 20_000_000L
                }
                updateDrops()
            }
        } catch (error: Exception) {
            if (isCurrent(output)) event(error.message ?: "Receiver output stopped")
        } finally {
            synchronized(this) {
                if (track === output) close()
            }
        }
    }

    private fun updateDrops() { droppedFrames.set(planner.snapshot().droppedFrames) }

    private fun isCurrent(output: AudioTrack) = running.get() && track === output

    @Synchronized
    override fun close() {
        running.set(false)
        lifecycle++
        planner.stop()
        worker?.interrupt()
        worker = null
        track?.let { output ->
            runCatching { playbackHeadFrames = output.playbackHeadPosition.toLong() and 0xffffffffL }
            runCatching { output.pause() }
            runCatching { output.flush() }
            runCatching { output.release() }
        }
        track = null
        updateDrops()
        val request = focus
        focus = null
        request?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
    }

    companion object {
        private const val POLL_NANOS = 2_000_000L
        private const val SAMPLES_PER_FRAME = PcmFormat.FRAME_BYTES / PcmFormat.BYTES_PER_SAMPLE
    }
}
