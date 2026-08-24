package dev.pocketdisco.phase0

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class YouTubeControlTrialTest {
    @Test
    fun parsesVideoAndPlaylistTrials() {
        val video = YouTubeControlTrial.parse(
            id = "bdc2bc6e-eb31-469b-a101-44ef4732352f",
            itemType = "video",
            itemId = "M7lc1UVf-VE",
            requestedPositionMs = 1_500,
            effectiveAtUnixMs = 1_786_899_005_000,
            createdAtUnixMs = 1_786_899_000_000,
        )
        val playlist = YouTubeControlTrial.parse(
            id = "9a566f09-92fa-48fc-90ba-d6b9e634179f",
            itemType = "playlist",
            itemId = "PL1234567890",
            requestedPositionMs = 0,
            effectiveAtUnixMs = 1_786_899_005_000,
            createdAtUnixMs = 1_786_899_000_000,
        )

        assertEquals(YouTubeItemType.VIDEO, video.itemType)
        assertEquals("M7lc1UVf-VE", video.itemId)
        assertEquals(YouTubeItemType.PLAYLIST, playlist.itemType)
        assertEquals("PL1234567890", playlist.itemId)
    }

    @Test
    fun rejectsUrlsAndUnknownItemTypes() {
        assertThrows(IllegalArgumentException::class.java) {
            YouTubeControlTrial.parse(
                id = "bdc2bc6e-eb31-469b-a101-44ef4732352f",
                itemType = "audio",
                itemId = "M7lc1UVf-VE",
                requestedPositionMs = 0,
                effectiveAtUnixMs = 1,
                createdAtUnixMs = 0,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            YouTubeControlTrialRequest(
                itemType = YouTubeItemType.VIDEO,
                itemId = "https://youtube.com/watch?v=M7lc1UVf-VE",
                requestedPositionMs = 0,
                effectiveAtUnixMs = 1,
            )
        }
    }

    @Test
    fun rejectsInvalidTimingFields() {
        assertThrows(IllegalArgumentException::class.java) {
            YouTubeControlTrialRequest(
                itemType = YouTubeItemType.VIDEO,
                itemId = "M7lc1UVf-VE",
                requestedPositionMs = -1,
                effectiveAtUnixMs = 1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            YouTubeControlTrial.parse(
                id = "bdc2bc6e-eb31-469b-a101-44ef4732352f",
                itemType = "video",
                itemId = "M7lc1UVf-VE",
                requestedPositionMs = 0,
                effectiveAtUnixMs = 0,
                createdAtUnixMs = 1,
            )
        }
    }
}
