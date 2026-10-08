package com.pocketdisco.playback

enum class EngineState { IDLE, BUFFERING, READY, ENDED }

interface PlaybackEngine {
    val state: EngineState
    val isPlaying: Boolean
    val positionMs: Long
    val durationMs: Long?
    var listener: Listener?

    fun prepare(positionMs: Long)
    fun play()
    fun pause()
    fun seek(positionMs: Long)
    fun release()

    interface Listener {
        fun onChanged()
        fun onError()
        fun onInterrupted()
    }
}

fun interface PlaybackCancellation {
    fun cancel()
}

interface PlaybackClock {
    val nowMs: Long
    fun schedule(delayMs: Long, action: () -> Unit): PlaybackCancellation
}

data class PlaybackSnapshot(
    val itemId: String?,
    val status: String,
    val positionMs: Long,
    val durationMs: Long,
    val sampledAtMonotonicMs: Long,
    val scheduledStartMonotonicMs: Long?,
    val errorCode: String?,
)

class PlaybackFailure(val code: String, message: String) : Exception(message)
