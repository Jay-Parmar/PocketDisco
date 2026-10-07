package dev.pocketdisco.phase0

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackObservationTrackerTest {
    @Test
    fun observesPlaybackAfterEveryTrackHasTimingAndRoute() {
        val tracker = PlaybackObservationTracker()

        assertTrue(tracker.observe(status(1_000L to setOf(7), 1_100L to setOf(8))))
        assertFalse(tracker.observe(status(2_000L to setOf(7), 2_100L to setOf(8))))
    }

    @Test
    fun waitsForTimestampAndRoute() {
        val tracker = PlaybackObservationTracker()

        assertFalse(tracker.observe(status(null to setOf(7), 1_100L to setOf(8))))
        assertFalse(tracker.observe(status(1_000L to setOf(7), 1_100L to emptySet())))
    }

    @Test
    fun resetAllowsAnotherObservation() {
        val tracker = PlaybackObservationTracker()
        tracker.observe(status(1_000L to setOf(7)))

        tracker.reset()

        assertTrue(tracker.observe(status(2_000L to setOf(7))))
    }

    private fun status(vararg tracks: Pair<Long?, Set<Int>>) = MultiOutputStatus(
        mode = AndroidRouteMode.SYSTEM_GROUP,
        route = OutputRouteResult(
            OutputRouteState.SYSTEM_GROUP_UNVERIFIED,
            tracks.flatMap { it.second }.toSet(),
        ),
        tracks = tracks.mapIndexed { index, track ->
            OutputTrackStatus(
                trackLabel = "track_${index + 1}",
                requestedDeviceId = null,
                preferenceAccepted = null,
                actualDeviceIds = track.second,
                underrunCount = 0,
                framePosition = track.first?.let { 0 },
                frameNanoTime = track.first,
            )
        },
    )
}
