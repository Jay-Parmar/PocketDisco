package dev.pocketdisco.phase0

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputRouteAssessmentTest {
    @Test
    fun confirmsDistinctRoutesAcrossTracks() {
        val result = OutputRouteAssessment.evaluate(
            listOf(
                TrackRouteObservation("left", 7, setOf(7)),
                TrackRouteObservation("right", 8, setOf(8)),
            ),
            AndroidRouteMode.DUAL_TRACK,
        )

        assertEquals(OutputRouteState.DISTINCT_ROUTES, result.state)
        assertEquals(setOf(7, 8), result.actualDeviceIds)
    }

    @Test
    fun confirmsSystemManagedGroupOnOneTrack() {
        val result = OutputRouteAssessment.evaluate(
            listOf(TrackRouteObservation("system", null, setOf(7, 8))),
            AndroidRouteMode.SYSTEM_GROUP,
        )

        assertEquals(OutputRouteState.DISTINCT_ROUTES, result.state)
    }

    @Test
    fun reportsSingleRouteWhenTracksShareOneDestination() {
        val result = OutputRouteAssessment.evaluate(
            listOf(
                TrackRouteObservation("left", 7, setOf(7)),
                TrackRouteObservation("right", 8, setOf(7)),
            ),
            AndroidRouteMode.DUAL_TRACK,
        )

        assertEquals(OutputRouteState.SINGLE_ROUTE, result.state)
    }

    @Test
    fun reportsPartialRouteWhenOneTrackHasNoDestination() {
        val result = OutputRouteAssessment.evaluate(
            listOf(
                TrackRouteObservation("left", 7, setOf(7)),
                TrackRouteObservation("right", 8, emptySet()),
            ),
            AndroidRouteMode.DUAL_TRACK,
        )

        assertEquals(OutputRouteState.PARTIAL_ROUTE, result.state)
    }

    @Test
    fun reportsUnroutedWhenNoDestinationIsKnown() {
        val result = OutputRouteAssessment.evaluate(
            listOf(
                TrackRouteObservation("left", 7, emptySet()),
                TrackRouteObservation("right", 8, emptySet()),
            ),
            AndroidRouteMode.DUAL_TRACK,
        )

        assertEquals(OutputRouteState.UNROUTED, result.state)
    }

    @Test
    fun rejectsDistinctRoutesThatIgnorePreferences() {
        val result = OutputRouteAssessment.evaluate(
            listOf(
                TrackRouteObservation("left", 7, setOf(9)),
                TrackRouteObservation("right", 8, setOf(10)),
            ),
            AndroidRouteMode.DUAL_TRACK,
        )

        assertEquals(OutputRouteState.PREFERENCE_MISMATCH, result.state)
    }

    @Test
    fun rejectsOverlappingRoutesAcrossTracks() {
        val result = OutputRouteAssessment.evaluate(
            listOf(
                TrackRouteObservation("left", 7, setOf(7, 8)),
                TrackRouteObservation("right", 8, setOf(7, 8)),
            ),
            AndroidRouteMode.DUAL_TRACK,
        )

        assertEquals(OutputRouteState.PREFERENCE_MISMATCH, result.state)
    }

    @Test
    fun rejectsUnexpectedExtraRoute() {
        val result = OutputRouteAssessment.evaluate(
            listOf(
                TrackRouteObservation("left", 7, setOf(7, 9)),
                TrackRouteObservation("right", 8, setOf(8)),
            ),
            AndroidRouteMode.DUAL_TRACK,
        )

        assertEquals(OutputRouteState.PREFERENCE_MISMATCH, result.state)
    }

    @Test
    fun leavesSingleSystemGroupRouteUnverified() {
        val result = OutputRouteAssessment.evaluate(
            listOf(TrackRouteObservation("system", null, setOf(11))),
            AndroidRouteMode.SYSTEM_GROUP,
        )

        assertEquals(OutputRouteState.SYSTEM_GROUP_UNVERIFIED, result.state)
    }
}
