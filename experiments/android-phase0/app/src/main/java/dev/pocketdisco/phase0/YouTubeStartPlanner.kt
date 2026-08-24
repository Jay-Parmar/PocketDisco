package dev.pocketdisco.phase0

data class YouTubeStartState(
    val iframeReady: Boolean,
    val playbackArmed: Boolean,
    val activityResumed: Boolean,
    val windowFocused: Boolean,
    val preparedTrialId: String?,
)

data class YouTubeStartPlan(
    val targetElapsedRealtimeMs: Long,
    val requestedPositionMs: Long,
)

object YouTubeStartPlanner {
    fun plan(
        trial: YouTubeControlTrial,
        clock: ClockEstimate,
        nowElapsedRealtimeMs: Long,
        state: YouTubeStartState,
    ): YouTubeStartPlan {
        require(state.iframeReady) { "YouTube IFrame is not ready" }
        require(state.playbackArmed) { "YouTube playback needs a direct readiness gesture" }
        require(state.activityResumed) { "YouTube activity is not in the foreground" }
        require(state.windowFocused) { "YouTube activity does not have focus" }
        require(state.preparedTrialId == trial.id) { "A different YouTube trial is prepared" }
        val targetElapsedRealtimeMs = clock.elapsedRealtimeForServerUnix(trial.effectiveAtUnixMs)
        require(targetElapsedRealtimeMs - nowElapsedRealtimeMs >= MIN_START_LEAD_MS) {
            "YouTube trial deadline is too close or already passed"
        }
        return YouTubeStartPlan(
            targetElapsedRealtimeMs = targetElapsedRealtimeMs,
            requestedPositionMs = trial.requestedPositionMs,
        )
    }

    private const val MIN_START_LEAD_MS = 500L
}
