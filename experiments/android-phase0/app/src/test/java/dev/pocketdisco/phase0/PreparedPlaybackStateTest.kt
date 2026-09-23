package dev.pocketdisco.phase0

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparedPlaybackStateTest {
    @Test
    fun movesFromIdleThroughPreparedAndArmed() {
        val state = PreparedPlaybackState()

        assertEquals(PlaybackStartPhase.IDLE, state.phase)
        state.markPrepared()
        assertTrue(state.canCancelPreparation)
        state.markArmed()

        assertEquals(PlaybackStartPhase.ARMED, state.phase)
        assertFalse(state.canCancelPreparation)
    }

    @Test
    fun requiresPreparationBeforeArmingAndCanReset() {
        val state = PreparedPlaybackState()

        assertThrows(IllegalStateException::class.java, state::markArmed)
        state.markPrepared()
        state.reset()

        assertEquals(PlaybackStartPhase.IDLE, state.phase)
        assertFalse(state.canCancelPreparation)
    }
}
