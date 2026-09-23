package dev.pocketdisco.phase0

data class CoordinationTarget(
    val wallTimeMs: Long,
    val elapsedRealtimeMs: Long,
) {
    companion object {
        fun fromWallTime(
            targetWallTimeMs: Long,
            currentWallTimeMs: Long,
            currentElapsedRealtimeMs: Long,
        ): CoordinationTarget {
            val leadTimeMs = Math.subtractExact(targetWallTimeMs, currentWallTimeMs)
            require(leadTimeMs > 0) { "Start time must be in the future" }
            return CoordinationTarget(
                wallTimeMs = targetWallTimeMs,
                elapsedRealtimeMs = Math.addExact(currentElapsedRealtimeMs, leadTimeMs),
            )
        }
    }
}
