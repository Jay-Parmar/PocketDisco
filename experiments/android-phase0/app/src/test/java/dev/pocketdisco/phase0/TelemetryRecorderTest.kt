package dev.pocketdisco.phase0

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryRecorderTest {
    @Test
    fun emitsOrderedEscapedNdjson() {
        var wallTime = 100L
        var elapsedTime = 200L
        val recorder = TelemetryRecorder(
            wallClockMs = { wallTime },
            elapsedRealtimeMs = { elapsedTime },
            sessionId = "session-1",
        )

        recorder.record(
            deviceLabel = "phone-a",
            trialId = "run-1",
            category = "licensed_audio",
            name = "started",
            playerPositionMs = 12,
            detail = "quote=\"yes\"\nnext",
        )
        wallTime = 101
        elapsedTime = 201
        recorder.record(
            deviceLabel = "phone-a",
            trialId = "run-1",
            category = "licensed_audio",
            name = "sample",
        )

        val lines = recorder.toNdjson().trim().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("\"sequence\":1"))
        assertTrue(lines[1].contains("\"sequence\":2"))
        assertTrue(lines[0].contains("quote=\\\"yes\\\"\\nnext"))
        assertTrue(lines[1].contains("\"player_position_ms\":null"))
    }
}
