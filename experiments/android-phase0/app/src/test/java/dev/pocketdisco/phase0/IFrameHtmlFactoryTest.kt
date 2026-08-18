package dev.pocketdisco.phase0

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IFrameHtmlFactoryTest {
    @Test
    fun keepsRequiredControlsAndReadinessHandling() {
        val html = IFrameHtmlFactory.create("https://probe.example.test")

        assertTrue(html.contains("origin: configuredOrigin"))
        assertTrue(html.contains("controls: 1"))
        assertTrue(html.contains("onAutoplayBlocked"))
        assertTrue(html.contains("user_ready_gesture"))
        assertTrue(html.contains("playlist_transition"))
        assertTrue(html.contains("min-width: 200px"))
        assertFalse(html.contains("modestbranding"))
        assertFalse(html.contains("iv_load_policy"))
    }
}
