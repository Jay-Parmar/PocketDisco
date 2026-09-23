package dev.pocketdisco.phase0

data class GeneratedSignalPlan(
    val trialId: String,
    val target: CoordinationTarget,
)

object GeneratedSignalTrialPlanner {
    const val MINIMUM_LEAD_MS = 5_000L

    fun plan(
        trial: CoordinatorTrial,
        clockEstimate: ClockEstimate,
        currentElapsedRealtimeMs: Long,
    ): GeneratedSignalPlan {
        require(trial.assetId == ClickSignal.SIGNAL_ID) {
            "Trial signal identity does not match"
        }
        require(trial.assetSha256 == ClickSignal.PCM_SHA256) {
            "Trial signal digest does not match"
        }
        require(trial.requestedPositionMs == 0L) {
            "Generated signal trials must start at position zero"
        }
        val targetElapsedRealtimeMs = clockEstimate.elapsedRealtimeForServerUnix(
            trial.effectiveAtUnixMs,
        )
        requireSufficientLead(targetElapsedRealtimeMs, currentElapsedRealtimeMs)
        return GeneratedSignalPlan(
            trialId = trial.id,
            target = CoordinationTarget(
                wallTimeMs = trial.effectiveAtUnixMs,
                elapsedRealtimeMs = targetElapsedRealtimeMs,
            ),
        )
    }

    fun requireSufficientLead(
        targetElapsedRealtimeMs: Long,
        currentElapsedRealtimeMs: Long,
    ) {
        val leadMs = Math.subtractExact(targetElapsedRealtimeMs, currentElapsedRealtimeMs)
        require(leadMs >= MINIMUM_LEAD_MS) {
            "Generated signal trial has insufficient lead time"
        }
    }
}
