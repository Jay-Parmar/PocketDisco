package dev.pocketdisco.phase0

data class TrackRouteObservation(
    val trackLabel: String,
    val actualDeviceIds: Set<Int>,
)

enum class OutputRouteState {
    UNROUTED,
    PARTIAL_ROUTE,
    SINGLE_ROUTE,
    DISTINCT_ROUTES,
}

data class OutputRouteResult(
    val state: OutputRouteState,
    val actualDeviceIds: Set<Int>,
)

object OutputRouteAssessment {
    fun evaluate(observations: List<TrackRouteObservation>): OutputRouteResult {
        require(observations.isNotEmpty()) { "At least one track is required" }
        val actualDeviceIds = observations.flatMap { it.actualDeviceIds }.toSet()
        val state = when {
            actualDeviceIds.isEmpty() -> OutputRouteState.UNROUTED
            observations.any { it.actualDeviceIds.isEmpty() } -> OutputRouteState.PARTIAL_ROUTE
            actualDeviceIds.size == 1 -> OutputRouteState.SINGLE_ROUTE
            else -> OutputRouteState.DISTINCT_ROUTES
        }
        return OutputRouteResult(state, actualDeviceIds)
    }
}
