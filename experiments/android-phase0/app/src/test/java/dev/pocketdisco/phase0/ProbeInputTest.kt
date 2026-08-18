package dev.pocketdisco.phase0

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProbeInputTest {
    @Test
    fun acceptsSignedHttpsMp4UrlWithoutLoggingIt() {
        val value = "https://cdn.example.test/audio/test.MP4?signature=secret"

        assertEquals(value, ProbeInput.licensedAssetUrl(value))
    }

    @Test
    fun rejectsCleartextAndNonSeekFriendlyAssets() {
        assertThrows(IllegalArgumentException::class.java) {
            ProbeInput.licensedAssetUrl("http://cdn.example.test/audio/test.m4a")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProbeInput.licensedAssetUrl("https://cdn.example.test/audio/test.mp3")
        }
    }

    @Test
    fun normalizesWebOrigin() {
        assertEquals("https://example.test:8443", ProbeInput.webOrigin("HTTPS://Example.Test:8443/"))
    }

    @Test
    fun rejectsOriginPathAndCredentials() {
        assertThrows(IllegalArgumentException::class.java) {
            ProbeInput.webOrigin("https://example.test/embed")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProbeInput.webOrigin("https://user@example.test")
        }
    }

    @Test
    fun validatesProviderIdentifiersAndAssetHash() {
        assertEquals("dQw4w9WgXcQ", ProbeInput.videoId(" dQw4w9WgXcQ "))
        assertEquals("PL1234567890", ProbeInput.playlistId("PL1234567890"))
        assertEquals("ab".repeat(32), ProbeInput.assetSha256("AB".repeat(32)))
    }

    @Test
    fun limitsCleartextCoordinatorToLan() {
        assertEquals(
            "http://192.168.1.20:8765",
            ProbeInput.coordinatorBaseUrl("http://192.168.1.20:8765/"),
        )
        assertEquals(
            "http://127.0.0.1:8765",
            ProbeInput.coordinatorBaseUrl("http://127.0.0.1:8765"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ProbeInput.coordinatorBaseUrl("http://public.example.test:8765")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProbeInput.coordinatorBaseUrl("http://10.example.test:8765")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProbeInput.coordinatorBaseUrl("http://192.168.example.test:8765")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProbeInput.coordinatorBaseUrl("http://172.16.example.test:8765")
        }
        assertEquals(
            "https://public.example.test",
            ProbeInput.coordinatorBaseUrl("https://PUBLIC.example.test/"),
        )
    }
}
