package dev.pocketdisco.phase0

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputRouteAssessmentTest {
    @Test
    fun confirmsDistinctRoutesAcrossTracks() {
        val result = OutputRouteAssessment.evaluate(
            listOf(
                TrackRouteObservation("left", setOf(7)),
                TrackRouteObservation("right", setOf(8)),
            ),
        )

        assertEquals(OutputRouteState.DISTINCT_ROUTES, result.state)
        assertEquals(setOf(7, 8), result.actualDeviceIds)
    }

    @Test
    fun confirmsSystemManagedGroupOnOneTrack() {
        val result = OutputRouteAssessment.evaluate(
            listOf(TrackRouteObservation("system", setOf(7, 8))),
        )

        assertEquals(OutputRouteState.DISTINCT_ROUTES, result.state)
    }

    @Test
    fun reportsSingleRouteWhenTracksShareOneDestination() {
        val result = OutputRouteAssessment.evaluate(
            listOf(
                TrackRouteObservation("left", setOf(7)),
                TrackRouteObservation("right", setOf(7)),
            ),
        )

        assertEquals(OutputRouteState.SINGLE_ROUTE, result.state)
    }

    @Test
    fun reportsPartialRouteWhenOneTrackHasNoDestination() {
        val result = OutputRouteAssessment.evaluate(
            listOf(
                TrackRouteObservation("left", setOf(7)),
                TrackRouteObservation("right", emptySet()),
            ),
        )

        assertEquals(OutputRouteState.PARTIAL_ROUTE, result.state)
    }

    @Test
    fun reportsUnroutedWhenNoDestinationIsKnown() {
        val result = OutputRouteAssessment.evaluate(
            listOf(
                TrackRouteObservation("left", emptySet()),
                TrackRouteObservation("right", emptySet()),
            ),
        )

        assertEquals(OutputRouteState.UNROUTED, result.state)
    }
}
