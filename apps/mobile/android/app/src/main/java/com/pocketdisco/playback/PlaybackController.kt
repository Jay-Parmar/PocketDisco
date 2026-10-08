package com.pocketdisco.playback

import kotlin.math.ceil

class PlaybackController(
    private val clock: PlaybackClock,
    private val createEngine: () -> PlaybackEngine,
) {
    private var engine: PlaybackEngine? = null
    private var foreground = false
    private var itemId: String? = null
    private var stoppedStatus = "idle"
    private var errorCode: String? = null
    private var lastPositionMs = 0L
    private var scheduledAt: Long? = null
    private var generation = 0L
    private var startTask: PlaybackCancellation? = null
    private var prepareTimeout: PlaybackCancellation? = null
    private var preparation: ((Result<PlaybackSnapshot>) -> Unit)? = null

    fun prepare(item: String, positionMs: Double, completion: (Result<PlaybackSnapshot>) -> Unit) {
        requireForeground()
        if (item != ITEM_ID) throw failure("unsupported_item")
        val position = validatePosition(positionMs, DURATION_MS)
        cancelPending()
        releaseEngine()
        itemId = item
        lastPositionMs = position
        errorCode = null
        stoppedStatus = "ready"
        preparation = completion
        try {
            val next = createEngine()
            engine = next
            next.listener = object : PlaybackEngine.Listener {
                override fun onChanged() {
                    if (engine === next && next.state == EngineState.READY) completePreparation()
                }

                override fun onError() {
                    if (engine === next) fail("playback_failed")
                }

                override fun onInterrupted() {
                    if (engine === next) interrupt()
                }
            }
            val request = generation
            prepareTimeout = clock.schedule(PREPARE_TIMEOUT_MS) {
                if (request == generation && preparation != null) fail("prepare_timeout")
            }
            next.prepare(position)
            if (engine === next && next.state == EngineState.READY) completePreparation()
        } catch (_: Exception) {
            fail("playback_failed")
        }
    }

    fun playAt(monotonicTimeMs: Double, positionMs: Double): PlaybackSnapshot {
        requireForeground()
        val active = requireEngine()
        if (preparation != null || active.state != EngineState.READY) throw failure("not_ready")
        val position = validatePosition(positionMs, duration())
        val now = clock.nowMs
        if (!monotonicTimeMs.isFinite() || monotonicTimeMs < now ||
            monotonicTimeMs > now + MAX_LEAD_MS
        ) throw failure("invalid_deadline")
        val target = ceil(monotonicTimeMs).toLong()
        cancelPending()
        val request = generation
        useEngine {
            active.pause()
            if (request != generation || engine !== active || !foreground) return@useEngine
            active.seek(position)
            if (request != generation || engine !== active || !foreground) return@useEngine
            lastPositionMs = position
            stoppedStatus = "ready"
            scheduledAt = target
            startTask = clock.schedule((target - clock.nowMs).coerceAtLeast(0)) {
                if (request != generation || engine !== active || !foreground) return@schedule
                startTask = null
                scheduledAt = null
                when {
                    clock.nowMs - target > MAX_LATENESS_MS -> fail("missed_deadline")
                    active.state != EngineState.READY -> fail("not_ready")
                    else -> {
                        try {
                            active.play()
                        } catch (_: Exception) {
                            fail("playback_failed")
                        }
                    }
                }
            }
        }
        return snapshot()
    }

    fun pause(): PlaybackSnapshot {
        cancelPending()
        stoppedStatus = if (itemId == null) "idle" else "paused"
        useEngine { engine?.pause() }
        return snapshot()
    }

    fun seek(positionMs: Double): PlaybackSnapshot {
        requireForeground()
        val active = requireEngine()
        val position = validatePosition(positionMs, duration())
        cancelPending()
        stoppedStatus = "paused"
        useEngine {
            active.pause()
            active.seek(position)
        }
        lastPositionMs = position
        return snapshot()
    }

    fun snapshot(): PlaybackSnapshot {
        val active = engine
        val duration = duration()
        lastPositionMs = (active?.positionMs ?: lastPositionMs).coerceIn(0, duration)
        val status = when {
            errorCode != null -> "error"
            itemId == null -> "idle"
            preparation != null -> "preparing"
            scheduledAt != null -> "scheduled"
            active?.state == EngineState.ENDED -> "ended"
            active?.isPlaying == true -> "playing"
            stoppedStatus == "paused" -> "paused"
            active?.state == EngineState.BUFFERING -> "preparing"
            else -> stoppedStatus
        }
        return PlaybackSnapshot(
            itemId, status, lastPositionMs, duration, clock.nowMs, scheduledAt, errorCode,
        )
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (!value) interrupt()
    }

    fun disconnect() {
        cancelPending()
        releaseEngine()
        itemId = null
        lastPositionMs = 0
        stoppedStatus = "idle"
        errorCode = null
    }

    private fun interrupt() {
        cancelPending()
        stoppedStatus = if (itemId == null) "idle" else "paused"
        try {
            engine?.pause()
        } catch (_: Exception) {
            fail("playback_failed")
        }
    }

    private fun completePreparation() {
        val completion = preparation ?: return
        preparation = null
        prepareTimeout?.cancel()
        prepareTimeout = null
        completion(Result.success(snapshot()))
    }

    private fun cancelPending(reason: PlaybackFailure = failure("cancelled")) {
        generation += 1
        startTask?.cancel()
        startTask = null
        scheduledAt = null
        prepareTimeout?.cancel()
        prepareTimeout = null
        val completion = preparation
        preparation = null
        completion?.invoke(Result.failure(reason))
    }

    private fun fail(code: String) {
        errorCode = code
        cancelPending(failure(code))
        releaseEngine()
    }

    private fun releaseEngine() {
        val previous = engine ?: return
        engine = null
        previous.listener = null
        try {
            lastPositionMs = previous.positionMs.coerceAtLeast(0)
        } catch (_: Exception) {
            // The last sampled position remains available after a decoder failure.
        }
        try {
            previous.release()
        } catch (_: Exception) {
            // A failed decoder is already detached from future commands.
        }
    }

    private inline fun useEngine(action: () -> Unit) {
        try {
            action()
        } catch (_: Exception) {
            fail("playback_failed")
            throw failure("playback_failed")
        }
    }

    private fun requireForeground() {
        if (!foreground) throw failure("not_foreground")
    }

    private fun requireEngine(): PlaybackEngine {
        if (errorCode != null || itemId == null) throw failure("not_ready")
        return engine ?: throw failure("not_ready")
    }

    private fun duration() = if (itemId == null) 0L else
        engine?.durationMs?.takeIf { it > 0 } ?: DURATION_MS

    private fun validatePosition(position: Double, duration: Long): Long {
        if (!position.isFinite() || position < 0 || position > duration) {
            throw failure("invalid_position")
        }
        return position.toLong()
    }

    companion object {
        const val ITEM_ID = "generated-pulse"
        const val DURATION_MS = 24_000L
        private const val PREPARE_TIMEOUT_MS = 10_000L
        private const val MAX_LEAD_MS = 10_000L
        private const val MAX_LATENESS_MS = 250L

        fun failure(code: String) = PlaybackFailure(code, when (code) {
            "unsupported_item" -> "This audio item is not supported."
            "invalid_position" -> "Choose a position within the demo audio."
            "invalid_deadline" -> "Schedule playback within the next ten seconds."
            "not_foreground" -> "Keep PocketDisco open to play audio."
            "not_ready" -> "Prepare the demo audio before playing."
            "prepare_timeout" -> "The demo audio took too long to prepare."
            "missed_deadline" -> "The scheduled start was missed. Try again."
            "cancelled" -> "Playback preparation was cancelled."
            "module_unavailable" -> "Playback is no longer available."
            else -> "Could not play the demo audio."
        })
    }
}
