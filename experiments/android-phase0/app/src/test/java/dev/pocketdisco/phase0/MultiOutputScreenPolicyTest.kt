package dev.pocketdisco.phase0

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun reusesMatchingPreparedCoordinatorRoute() {
        assertTrue(
            shouldReuseCoordinatorPreparation(
                preparedRoute = CoordinatedOutputRoute.System,
                requestedRoute = CoordinatedOutputRoute.System,
                controllerIsPrepared = true,
            ),
        )
    }

    @Test
    fun rebuildsChangedOrInactiveCoordinatorRoute() {
        val firstRoute = CoordinatedOutputRoute.Dual(10, 20)

        assertFalse(
            shouldReuseCoordinatorPreparation(
                preparedRoute = firstRoute,
                requestedRoute = CoordinatedOutputRoute.Dual(20, 10),
                controllerIsPrepared = true,
            ),
        )
        assertFalse(
            shouldReuseCoordinatorPreparation(
                preparedRoute = firstRoute,
                requestedRoute = firstRoute,
                controllerIsPrepared = false,
            ),
        )
    }
}
