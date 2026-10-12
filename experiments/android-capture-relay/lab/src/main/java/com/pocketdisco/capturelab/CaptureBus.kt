package com.pocketdisco.capturelab

import kotlin.math.abs
import kotlin.math.max

data class CaptureSnapshot(
    val running: Boolean,
    val status: String,
    val frames: Long,
    val nonSilentFrames: Long,
    val peak: Int,
)

object CaptureBus {
    private val lock = Any()
    private var state = CaptureSnapshot(false, "Capture stopped", 0, 0, 0)
    private var generation = 0L

    @Volatile
    var onPcm: ((ByteArray) -> Unit)? = null

    @Volatile
    var onStopped: (() -> Unit)? = null

    fun snapshot(): CaptureSnapshot = synchronized(lock) { state }

    internal fun begin(): Long = synchronized(lock) {
        check(!state.running) { "Capture is already starting or running" }
        generation += 1
        state = CaptureSnapshot(true, "Starting capture", 0, 0, 0)
        generation
    }

    internal fun isRunning(captureId: Long): Boolean = synchronized(lock) {
        captureId == generation && state.running
    }

    internal fun ready(captureId: Long) = synchronized(lock) {
        if (captureId == generation && state.running) state = state.copy(status = "Capturing selected app only")
    }

    internal fun record(captureId: Long, frame: ByteArray) {
        require(frame.size == 640) { "Expected one 20 ms PCM frame" }
        var peak = 0
        for (index in frame.indices step 2) {
            val sample = ((frame[index].toInt() and 0xff) or
                (frame[index + 1].toInt() shl 8)).toShort().toInt()
            peak = max(peak, abs(sample))
        }
        val deliver = synchronized(lock) {
            if (captureId != generation || !state.running) return
            state = state.copy(
                frames = state.frames + 1,
                nonSilentFrames = state.nonSilentFrames + if (peak > 32) 1 else 0,
                peak = max(state.peak, peak),
            )
            onPcm
        }
        deliver?.invoke(frame)
    }

    internal fun end(status: String, captureId: Long? = null) {
        val notify = synchronized(lock) {
            if (captureId != null && captureId != generation) return
            val wasRunning = state.running
            state = state.copy(running = false, status = status)
            wasRunning
        }
        if (notify) onStopped?.invoke()
    }
}
