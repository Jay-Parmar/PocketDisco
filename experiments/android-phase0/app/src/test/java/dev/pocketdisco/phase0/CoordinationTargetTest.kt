package dev.pocketdisco.phase0

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CoordinationTargetTest {
    @Test
    fun convertsCommonWallTimeToLocalElapsedRealtime() {
        val target = CoordinationTarget.fromWallTime(
            targetWallTimeMs = 1_300,
            currentWallTimeMs = 1_000,
            currentElapsedRealtimeMs = 9_000,
        )

        assertEquals(1_300, target.wallTimeMs)
        assertEquals(9_300, target.elapsedRealtimeMs)
    }

    @Test
    fun rejectsPastTarget() {
        assertThrows(IllegalArgumentException::class.java) {
            CoordinationTarget.fromWallTime(
                targetWallTimeMs = 999,
                currentWallTimeMs = 1_000,
                currentElapsedRealtimeMs = 9_000,
            )
        }
    }
}
