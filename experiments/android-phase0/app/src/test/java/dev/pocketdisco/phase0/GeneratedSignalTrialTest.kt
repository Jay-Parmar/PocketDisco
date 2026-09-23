package dev.pocketdisco.phase0

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GeneratedSignalTrialTest {
    private val estimate = ClockEstimate(
        serverToElapsedOffsetMs = 10_000,
        uncertaintyMs = 4,
        bestNetworkRoundTripTimeMs = 6,
        sampleCount = 7,
    )

    @Test
    fun mapsValidTrialToMonotonicTarget() {
        val plan = GeneratedSignalTrialPlanner.plan(
            trial = trial(effectiveAtUnixMs = 30_000),
            clockEstimate = estimate,
            currentElapsedRealtimeMs = 15_000,
        )

        assertEquals("trial-1", plan.trialId)
        assertEquals(30_000, plan.target.wallTimeMs)
        assertEquals(20_000, plan.target.elapsedRealtimeMs)
    }

    @Test
    fun rejectsWrongSignalIdentity() {
        assertThrows(IllegalArgumentException::class.java) {
            GeneratedSignalTrialPlanner.plan(
                trial = trial(assetId = "other-signal"),
                clockEstimate = estimate,
                currentElapsedRealtimeMs = 15_000,
            )
        }
    }

    @Test
    fun rejectsWrongSignalDigest() {
        assertThrows(IllegalArgumentException::class.java) {
            GeneratedSignalTrialPlanner.plan(
                trial = trial(assetSha256 = "0".repeat(64)),
                clockEstimate = estimate,
                currentElapsedRealtimeMs = 15_000,
            )
        }
    }

    @Test
    fun rejectsNonzeroPosition() {
        assertThrows(IllegalArgumentException::class.java) {
            GeneratedSignalTrialPlanner.plan(
                trial = trial(requestedPositionMs = 1),
                clockEstimate = estimate,
                currentElapsedRealtimeMs = 15_000,
            )
        }
    }

    @Test
    fun enforcesMinimumMonotonicLead() {
        assertThrows(IllegalArgumentException::class.java) {
            GeneratedSignalTrialPlanner.plan(
                trial = trial(effectiveAtUnixMs = 29_999),
                clockEstimate = estimate,
                currentElapsedRealtimeMs = 15_000,
            )
        }

        val plan = GeneratedSignalTrialPlanner.plan(
            trial = trial(effectiveAtUnixMs = 30_000),
            clockEstimate = estimate,
            currentElapsedRealtimeMs = 15_000,
        )
        assertEquals(20_000, plan.target.elapsedRealtimeMs)
    }

    @Test
    fun rechecksLeadBeforeArmingPreparedPlayback() {
        GeneratedSignalTrialPlanner.requireSufficientLead(
            targetElapsedRealtimeMs = 20_000,
            currentElapsedRealtimeMs = 15_000,
        )

        assertThrows(IllegalArgumentException::class.java) {
            GeneratedSignalTrialPlanner.requireSufficientLead(
                targetElapsedRealtimeMs = 20_000,
                currentElapsedRealtimeMs = 15_001,
            )
        }
    }

    private fun trial(
        assetId: String = ClickSignal.SIGNAL_ID,
        assetSha256: String = ClickSignal.PCM_SHA256,
        requestedPositionMs: Long = 0,
        effectiveAtUnixMs: Long = 30_000,
    ) = CoordinatorTrial(
        id = "trial-1",
        assetId = assetId,
        assetSha256 = assetSha256,
        requestedPositionMs = requestedPositionMs,
        effectiveAtUnixMs = effectiveAtUnixMs,
        createdAtUnixMs = 20_000,
    )
}
