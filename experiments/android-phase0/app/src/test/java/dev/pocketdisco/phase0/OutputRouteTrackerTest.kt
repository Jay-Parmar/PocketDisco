package dev.pocketdisco.phase0

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputRouteTrackerTest {
    @Test
    fun reportsLossAfterDistinctRouteDisappears() {
        val tracker = OutputRouteTracker()

        assertFalse(tracker.observe(OutputRouteState.DISTINCT_ROUTES))
        assertTrue(tracker.observe(OutputRouteState.SINGLE_ROUTE))
    }

    @Test
    fun ignoresFailuresBeforeDistinctRouteExists() {
        val tracker = OutputRouteTracker()

        assertFalse(tracker.observe(OutputRouteState.UNROUTED))
        assertFalse(tracker.observe(OutputRouteState.PREFERENCE_MISMATCH))
    }

    @Test
    fun resetForgetsEstablishedRoute() {
        val tracker = OutputRouteTracker()
        tracker.observe(OutputRouteState.DISTINCT_ROUTES)

        tracker.reset()

        assertFalse(tracker.observe(OutputRouteState.SINGLE_ROUTE))
    }
}
