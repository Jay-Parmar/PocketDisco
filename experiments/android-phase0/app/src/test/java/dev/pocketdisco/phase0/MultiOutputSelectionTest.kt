package dev.pocketdisco.phase0

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiOutputSelectionTest {
    private val first = output(id = 4, role = OutputTargetRole.DIRECT)
    private val second = output(id = 7, role = OutputTargetRole.DIRECT)

    @Test
    fun selectsTwoConnectedDirectTargets() {
        val result = MultiOutputSelector.select(listOf(first, second), 4, 7)

        assertTrue(result is MultiOutputSelection.Valid)
        assertEquals(listOf(first, second), (result as MultiOutputSelection.Valid).targets)
    }

    @Test
    fun rejectsDuplicateTargets() {
        val result = MultiOutputSelector.select(listOf(first, second), 4, 4)

        assertEquals(
            MultiOutputSelection.Invalid(SelectionFailure.DUPLICATE_TARGET),
            result,
        )
    }

    @Test
    fun rejectsDisconnectedTargets() {
        val result = MultiOutputSelector.select(listOf(first), 4, 7)

        assertEquals(
            MultiOutputSelection.Invalid(SelectionFailure.TARGET_NOT_CONNECTED),
            result,
        )
    }

    @Test
    fun rejectsSystemGroupAsDirectTarget() {
        val group = output(id = 9, role = OutputTargetRole.SYSTEM_GROUP)
        val result = MultiOutputSelector.select(listOf(first, group), 4, 9)

        assertEquals(
            MultiOutputSelection.Invalid(SelectionFailure.TARGET_NOT_DIRECT),
            result,
        )
    }

    private fun output(id: Int, role: OutputTargetRole) = OutputDeviceDescriptor(
        id = id,
        label = "Output $id",
        androidType = id,
        transport = OutputTransport.BLUETOOTH,
        targetRole = role,
    )
}
