package dev.pocketdisco.phase0

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class YouTubeStartPlannerTest {
    private val trial = YouTubeControlTrial.parse(
        id = "bdc2bc6e-eb31-469b-a101-44ef4732352f",
        itemType = "video",
        itemId = "M7lc1UVf-VE",
        requestedPositionMs = 1_500,
        effectiveAtUnixMs = 10_000,
        createdAtUnixMs = 5_000,
    )
    private val clock = ClockEstimate(
        serverToElapsedOffsetMs = 1_000,
        uncertaintyMs = 10,
        bestNetworkRoundTripTimeMs = 20,
        sampleCount = 7,
    )

    @Test
    fun mapsAnArmedForegroundTrialToMonotonicTime() {
        val plan = YouTubeStartPlanner.plan(
            trial = trial,
            clock = clock,
            nowElapsedRealtimeMs = 8_000,
            state = readyState(),
        )

        assertEquals(9_000, plan.targetElapsedRealtimeMs)
        assertEquals(1_500, plan.requestedPositionMs)
    }

    @Test
    fun rejectsUnarmedBackgroundOrUnfocusedStarts() {
        listOf(
            readyState().copy(playbackArmed = false),
            readyState().copy(activityResumed = false),
            readyState().copy(windowFocused = false),
        ).forEach { state ->
            assertThrows(IllegalArgumentException::class.java) {
                YouTubeStartPlanner.plan(trial, clock, 8_000, state)
            }
        }
    }

    @Test
    fun rejectsWrongTrialAndMissedDeadline() {
        assertThrows(IllegalArgumentException::class.java) {
            YouTubeStartPlanner.plan(
                trial,
                clock,
                8_000,
                readyState().copy(preparedTrialId = "9a566f09-92fa-48fc-90ba-d6b9e634179f"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            YouTubeStartPlanner.plan(trial, clock, 8_600, readyState())
        }
    }

    @Test
    fun validatesExecutionStateWithoutRequiringRemainingLead() {
        YouTubeStartPlanner.requireReady(trial, readyState())

        assertThrows(IllegalArgumentException::class.java) {
            YouTubeStartPlanner.requireReady(
                trial,
                readyState().copy(windowFocused = false),
            )
        }
    }

    private fun readyState() = YouTubeStartState(
        iframeReady = true,
        playbackArmed = true,
        activityResumed = true,
        windowFocused = true,
        preparedTrialId = trial.id,
    )
}
