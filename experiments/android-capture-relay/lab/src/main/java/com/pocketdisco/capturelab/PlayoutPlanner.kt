package com.pocketdisco.capturelab

import com.pocketdisco.capturelab.protocol.PcmFormat
import com.pocketdisco.capturelab.protocol.TimedAudioFrame
import java.util.TreeMap
import java.util.UUID
import kotlin.math.abs

data class SpeakerTimingSnapshot(
    val timingState: String,
    val scheduledStarts: Long,
    val lateFrames: Long,
    val resyncs: Long,
    val uncertaintyNanos: Long?,
    val lastScheduledPlayNanos: Long?,
    val startErrorNanos: Long?,
    val outputErrorNanos: Long?,
    val outputLeadNanos: Long,
    val queuedFrames: Int,
    val insertedSilenceFrames: Long,
    val droppedFrames: Long,
)

internal data class PlayoutChunk(val pcm: ByteArray, val epoch: Long)

internal class PlayoutPlanner(
    private val clock: () -> Long = System::nanoTime,
    private val capacity: Int = 100,
) {
    private val queue = TreeMap<Long, TimedAudioFrame>()
    private var active = false
    private var generation = 0L
    private var session: UUID? = null
    private var prefill = 1
    private var firstSequence = 0L
    private var nextSequence = 0L
    private var target: Long? = null
    private var startedAt: Long? = null
    private var lastTimestampAt: Long? = null
    private var gapFrames = 0
    private var calibrations = 0
    private var state = "stopped"
    private var starts = 0L
    private var late = 0L
    private var resyncs = 0L
    private var uncertainty: Long? = null
    private var scheduled: Long? = null
    private var startError: Long? = null
    private var outputError: Long? = null
    private var outputLead = 0L
    private var silence = 0L
    private var dropped = 0L

    init { require(capacity in 3..150) }

    @Synchronized
    fun start(prefillFrames: Int) {
        require(prefillFrames in 1..capacity)
        generation++
        active = true
        queue.clear()
        session = null
        prefill = prefillFrames
        target = null
        startedAt = null
        lastTimestampAt = null
        state = "buffering"
        starts = 0
        late = 0
        resyncs = 0
        silence = 0
        dropped = 0
        gapFrames = 0
        calibrations = 0
        uncertainty = null
        scheduled = null
        startError = null
        outputError = null
        outputLead = 0
    }

    @Synchronized
    fun offer(value: TimedAudioFrame): Boolean {
        val now = clock()
        val frame = value.frame
        if (!active || frame.sequence < 0 || frame.pcm.size != PcmFormat.FRAME_BYTES ||
            (session != null && session != frame.sessionId)
        ) return reject()
        if (value.uncertaintyNanos !in 0..MAX_UNCERTAINTY) {
            invalidateTiming()
            return reject()
        }
        uncertainty = value.uncertaintyNanos
        if (value.playAtNanos < now) {
            late++
            return reject()
        }
        if (value.playAtNanos - now > MAX_FUTURE) return reject()
        if ((target != null && frame.sequence < nextSequence) || queue.containsKey(frame.sequence)) {
            return reject()
        }
        if (target != null) {
            val expected = deadline(frame.sequence)
            if (expected == null || abs(value.playAtNanos - expected) > MAX_CLOCK_SHIFT) {
                reset("rebuffering")
            }
        }
        if (queue.size == capacity) reset("rebuffering")
        session = frame.sessionId
        queue[frame.sequence] = value.copy(frame = frame.copy(pcm = frame.pcm.copyOf()))
        if (state == "waiting_clock") state = "buffering"
        return true
    }

    @Synchronized
    fun next(): PlayoutChunk? {
        if (!active) return null
        val now = clock()
        if (target == null && !arm(now)) return null
        val playAt = deadline(nextSequence) ?: return resetAndWait()
        if (playAt < now - MAX_START_LATE) {
            late++
            return resetAndWait()
        }
        val value = queue[nextSequence]
        if (value != null && abs(value.playAtNanos - playAt) > MAX_CLOCK_SHIFT) return resetAndWait()
        queue.remove(nextSequence)
        val pcm = if (value != null) {
            gapFrames = 0
            value.frame.pcm
        } else {
            val next = queue.firstEntry()?.key
            if (next != null && next - nextSequence > MAX_GAPS) return resetAndWait()
            if (next == null && playAt - now > WRITE_AHEAD) return null
            if (gapFrames >= MAX_GAPS) return resetAndWait()
            gapFrames++
            silence++
            dropped++
            ByteArray(PcmFormat.FRAME_BYTES)
        }
        if (nextSequence == Long.MAX_VALUE) return resetAndWait()
        nextSequence++
        return PlayoutChunk(pcm, generation)
    }

    private fun arm(now: Long): Boolean {
        while (queue.isNotEmpty()) {
            val first = queue.firstEntry()?.value ?: return false
            if (first.playAtNanos - outputLead < now + START_LEAD) {
                queue.pollFirstEntry()
                late++
                dropped++
                continue
            }
            val end = runCatching { Math.addExact(first.frame.sequence, prefill.toLong() - 1) }.getOrNull()
                ?: return false
            if (queue.lastKey() < end) return false
            if (prefill - queue.subMap(first.frame.sequence, true, end, true).size > MAX_GAPS) {
                queue.pollFirstEntry()
                dropped++
                continue
            }
            firstSequence = first.frame.sequence
            nextSequence = firstSequence
            target = first.playAtNanos
            scheduled = target
            startedAt = null
            lastTimestampAt = null
            startError = null
            outputError = null
            gapFrames = 0
            state = "scheduled"
            return true
        }
        return false
    }

    @Synchronized
    fun startDeadline(epoch: Long): Long? = if (epoch == generation) target?.minus(outputLead) else null

    @Synchronized
    fun beginPlayback(epoch: Long): Boolean {
        val deadline = startDeadline(epoch) ?: return false
        if (!active || startedAt != null || clock() < deadline) return false
        val now = clock()
        startError = now - deadline
        if (now - deadline > MAX_START_LATE) {
            late++
            reset("rebuffering")
            return false
        }
        startedAt = now
        starts++
        state = "timestamp_wait"
        return true
    }

    @Synchronized
    fun timestamp(epoch: Long, framePosition: Long, timestampNanos: Long) {
        val started = startedAt ?: return
        if (!active || epoch != generation || framePosition < PcmFormat.SAMPLE_RATE / 10) return
        val now = clock()
        if (now - started < TIMESTAMP_WARMUP || timestampNanos < started ||
            timestampNanos > now + 2_000_000 || now - timestampNanos > TIMESTAMP_FRESHNESS
        ) return
        val expected = runCatching {
            Math.addExact(checkNotNull(target), Math.multiplyExact(framePosition, 1_000_000_000L) / PcmFormat.SAMPLE_RATE)
        }.getOrNull() ?: return
        val error = timestampNanos - expected
        outputError = error
        lastTimestampAt = now
        val corrected = (outputLead + error).coerceIn(0, MAX_OUTPUT_LEAD)
        if (abs(error) > CALIBRATE_ERROR && corrected != outputLead && calibrations < MAX_CALIBRATIONS) {
            outputLead = corrected
            calibrations++
            reset("rebuffering")
        } else if (abs(error) > MAX_OUTPUT_ERROR) {
            fail("timing_unavailable")
        } else {
            state = if (abs(error) > CALIBRATE_ERROR) "timing_degraded" else "playing"
        }
    }

    @Synchronized
    fun checkTimestamp() {
        val observed = lastTimestampAt ?: startedAt ?: return
        if (active && clock() - observed > TIMESTAMP_TIMEOUT) fail("timestamp_unavailable")
    }

    @Synchronized
    fun rebuffer() { if (active) reset("rebuffering") }

    @Synchronized
    fun invalidateTiming() {
        if (!active) return
        if (target != null || queue.isNotEmpty()) reset("waiting_clock") else state = "waiting_clock"
        uncertainty = null
    }

    @Synchronized
    fun discardOutput(frameCount: Long) { dropped += frameCount.coerceAtLeast(0) }

    @Synchronized
    fun epoch() = generation

    @Synchronized
    fun failed() = !active && state.endsWith("unavailable")

    @Synchronized
    fun stop() {
        active = false
        generation++
        queue.clear()
        target = null
        if (!state.endsWith("unavailable")) state = "stopped"
    }

    @Synchronized
    fun snapshot() = SpeakerTimingSnapshot(
        state, starts, late, resyncs, uncertainty, scheduled, startError,
        outputError, outputLead, queue.size, silence, dropped,
    )

    private fun deadline(sequence: Long): Long? = runCatching {
        Math.addExact(checkNotNull(target), Math.multiplyExact(sequence - firstSequence, FRAME_NANOS))
    }.getOrNull()

    private fun reject(): Boolean { dropped++; return false }

    private fun resetAndWait(): PlayoutChunk? { reset("rebuffering"); return null }

    private fun reset(nextState: String) {
        generation++
        resyncs++
        dropped += queue.size
        queue.clear()
        target = null
        startedAt = null
        lastTimestampAt = null
        state = nextState
    }

    private fun fail(reason: String) { reset(reason); active = false }

    companion object {
        const val FRAME_NANOS = 20_000_000L
        private const val MAX_FUTURE = 3_000_000_000L
        private const val MAX_UNCERTAINTY = 100_000_000L
        private const val START_LEAD = 100_000_000L
        private const val WRITE_AHEAD = 80_000_000L
        private const val MAX_START_LATE = 20_000_000L
        private const val MAX_CLOCK_SHIFT = 40_000_000L
        private const val MAX_GAPS = 2
        private const val CALIBRATE_ERROR = 20_000_000L
        private const val MAX_OUTPUT_ERROR = 40_000_000L
        private const val MAX_OUTPUT_LEAD = 250_000_000L
        private const val MAX_CALIBRATIONS = 3
        private const val TIMESTAMP_WARMUP = 250_000_000L
        private const val TIMESTAMP_FRESHNESS = 500_000_000L
        private const val TIMESTAMP_TIMEOUT = 3_000_000_000L
    }
}
