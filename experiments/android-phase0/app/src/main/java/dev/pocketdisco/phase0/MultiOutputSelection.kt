package dev.pocketdisco.phase0

enum class SelectionFailure {
    DUPLICATE_TARGET,
    TARGET_NOT_CONNECTED,
    TARGET_NOT_DIRECT,
}

sealed interface MultiOutputSelection {
    data class Valid(val targets: List<OutputDeviceDescriptor>) : MultiOutputSelection

    data class Invalid(val reason: SelectionFailure) : MultiOutputSelection
}

object MultiOutputSelector {
    fun select(
        available: List<OutputDeviceDescriptor>,
        firstId: Int,
        secondId: Int,
    ): MultiOutputSelection {
        if (firstId == secondId) {
            return MultiOutputSelection.Invalid(SelectionFailure.DUPLICATE_TARGET)
        }
        val byId = available.associateBy(OutputDeviceDescriptor::id)
        val targets = listOf(byId[firstId], byId[secondId])
        if (targets.any { it == null }) {
            return MultiOutputSelection.Invalid(SelectionFailure.TARGET_NOT_CONNECTED)
        }
        val connectedTargets = targets.filterNotNull()
        if (connectedTargets.any { it.targetRole != OutputTargetRole.DIRECT }) {
            return MultiOutputSelection.Invalid(SelectionFailure.TARGET_NOT_DIRECT)
        }
        return MultiOutputSelection.Valid(connectedTargets)
    }
}
