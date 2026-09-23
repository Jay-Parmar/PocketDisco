package dev.pocketdisco.phase0

data class TrackRouteObservation(
    val trackLabel: String,
    val requestedDeviceId: Int?,
    val actualDeviceIds: Set<Int>,
)

enum class AndroidRouteMode {
    SYSTEM_GROUP,
    DUAL_TRACK,
}

enum class OutputRouteState {
    UNROUTED,
    PARTIAL_ROUTE,
    SINGLE_ROUTE,
    DISTINCT_ROUTES,
    PREFERENCE_MISMATCH,
    SYSTEM_GROUP_UNVERIFIED,
}

data class OutputRouteResult(
    val state: OutputRouteState,
    val actualDeviceIds: Set<Int>,
)

object OutputRouteAssessment {
    fun evaluate(
        observations: List<TrackRouteObservation>,
        mode: AndroidRouteMode,
    ): OutputRouteResult {
        require(observations.isNotEmpty()) { "At least one track is required" }
        val actualDeviceIds = observations.flatMap { it.actualDeviceIds }.toSet()
        val state = when {
            actualDeviceIds.isEmpty() -> OutputRouteState.UNROUTED
            observations.any { it.actualDeviceIds.isEmpty() } -> OutputRouteState.PARTIAL_ROUTE
            actualDeviceIds.size == 1 -> OutputRouteState.SINGLE_ROUTE
            mode == AndroidRouteMode.SYSTEM_GROUP -> OutputRouteState.DISTINCT_ROUTES
            observations.any { observation ->
                val requestedDeviceId = observation.requestedDeviceId
                requestedDeviceId == null || observation.actualDeviceIds != setOf(requestedDeviceId)
            } -> OutputRouteState.PREFERENCE_MISMATCH
            else -> OutputRouteState.DISTINCT_ROUTES
        }
        val resolvedState = if (
            mode == AndroidRouteMode.SYSTEM_GROUP &&
            state == OutputRouteState.SINGLE_ROUTE
        ) {
            OutputRouteState.SYSTEM_GROUP_UNVERIFIED
        } else {
            state
        }
        return OutputRouteResult(resolvedState, actualDeviceIds)
    }
}
