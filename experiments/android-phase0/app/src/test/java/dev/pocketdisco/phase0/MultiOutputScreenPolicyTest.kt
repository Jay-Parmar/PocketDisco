package dev.pocketdisco.phase0

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MultiOutputScreenPolicyTest {
    @Test
    fun keepsScreenAwakeAfterScheduling() {
        assertEquals(true, keepScreenAwakeForEvent("playback_scheduled"))
    }

    @Test
    fun allowsSleepAfterPlaybackStops() {
        assertEquals(false, keepScreenAwakeForEvent("playback_stopped"))
    }

    @Test
    fun ignoresEventsThatDoNotChangePlaybackLifecycle() {
        assertNull(keepScreenAwakeForEvent("route_sample"))
    }
}
