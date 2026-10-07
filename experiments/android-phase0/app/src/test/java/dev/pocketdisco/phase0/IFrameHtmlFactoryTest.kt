package dev.pocketdisco.phase0

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IFrameHtmlFactoryTest {
    @Test
    fun keepsRequiredControlsAndReadinessHandling() {
        val html = IFrameHtmlFactory.create("dev.pocketdisco.phase0")

        assertTrue(html.contains("const configuredOrigin = \"https://dev.pocketdisco.phase0\""))
        assertTrue(html.contains("origin: configuredOrigin"))
        assertTrue(html.contains("controls: 1"))
        assertTrue(html.contains("onAutoplayBlocked"))
        assertTrue(html.contains("user_ready_gesture"))
        assertTrue(html.contains("playback_armed"))
        assertTrue(html.contains("armPending"))
        assertTrue(html.contains("itemPrepared"))
        assertTrue(html.contains("button.disabled = !iframeReady || !itemPrepared"))
        assertTrue(html.contains("event.data === YT.PlayerState.CUED"))
        assertTrue(html.contains("media_cued"))
        assertTrue(html.contains("player.pauseVideo()"))
        assertTrue(html.contains("player.seekTo(preparedStartSeconds, true)"))
        assertTrue(html.contains("arming: arming"))
        assertTrue(html.contains("playlist_transition"))
        assertTrue(html.contains("cueVideo: function (videoId, startSeconds)"))
        assertTrue(html.contains("cuePlaylist: function (playlistId, startSeconds)"))
        assertTrue(html.contains("startSeconds: startSeconds"))
        assertTrue(html.contains("min-width: 200px"))
        assertFalse(html.contains("modestbranding"))
        assertFalse(html.contains("iv_load_policy"))
    }
}
