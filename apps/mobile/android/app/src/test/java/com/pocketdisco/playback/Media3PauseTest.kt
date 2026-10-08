package com.pocketdisco.playback

import androidx.media3.common.Player
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class Media3PauseTest {
    @Test
    fun alreadyPausedFocusLossDoesNotSendAnotherUserRequest() {
        val fake = FakePlayer(playWhenReady = false)
        repeat(10) { fake.player.pauseIfRequested() }
        assertEquals(0, fake.pauseCalls)
    }

    @Test
    fun transientSuppressionClearsPlaybackIntentOnce() {
        val fake = FakePlayer(playWhenReady = true)
        repeat(10) { fake.player.pauseIfRequested() }
        assertEquals(1, fake.pauseCalls)
        assertFalse(fake.playWhenReady)
    }

    private class FakePlayer(var playWhenReady: Boolean) {
        var pauseCalls = 0
        val player = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getPlayWhenReady" -> playWhenReady
                "pause" -> {
                    pauseCalls += 1
                    playWhenReady = false
                    null
                }
                else -> error("Unexpected player call: ${method.name}")
            }
        } as Player
    }
}
