package dev.pocketdisco.phase0

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStartAttemptTest {
    @Test
    fun reportsFailureAndStopsStartingLaterTracks() {
        val calls = mutableListOf<String>()
        val expected = IllegalStateException("failed")
        var failure: Exception? = null

        val completed = attemptPlaybackStart(
            playActions = listOf(
                { calls += "first" },
                {
                    calls += "second"
                    throw expected
                },
                { calls += "third" },
            ),
            afterStarted = { calls += "observed" },
            onFailure = { failure = it },
        )

        assertFalse(completed)
        assertEquals(listOf("first", "second"), calls)
        assertSame(expected, failure)
    }

    @Test
    fun reportsFailureFromStartObservation() {
        val expected = IllegalStateException("status failed")
        var failure: Exception? = null

        val completed = attemptPlaybackStart(
            playActions = listOf({ Unit }),
            afterStarted = { throw expected },
            onFailure = { failure = it },
        )

        assertFalse(completed)
        assertSame(expected, failure)
    }

    @Test
    fun completesAfterEveryTrackStarts() {
        var observed = false
        var failure: Exception? = null

        val completed = attemptPlaybackStart(
            playActions = listOf({ Unit }, { Unit }),
            afterStarted = { observed = true },
            onFailure = { failure = it },
        )

        assertTrue(completed)
        assertTrue(observed)
        assertEquals(null, failure)
    }
}
