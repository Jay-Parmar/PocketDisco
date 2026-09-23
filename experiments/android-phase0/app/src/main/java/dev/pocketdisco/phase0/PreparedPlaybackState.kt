package dev.pocketdisco.phase0

enum class PlaybackStartPhase {
    IDLE,
    PREPARED,
    ARMED,
}

class PreparedPlaybackState {
    var phase: PlaybackStartPhase = PlaybackStartPhase.IDLE
        private set

    val canCancelPreparation: Boolean
        get() = phase == PlaybackStartPhase.PREPARED

    fun markPrepared() {
        check(phase == PlaybackStartPhase.IDLE) { "Playback state must be idle before preparation" }
        phase = PlaybackStartPhase.PREPARED
    }

    fun markArmed() {
        check(phase == PlaybackStartPhase.PREPARED) { "Playback must be prepared before arming" }
        phase = PlaybackStartPhase.ARMED
    }

    fun reset() {
        phase = PlaybackStartPhase.IDLE
    }
}
