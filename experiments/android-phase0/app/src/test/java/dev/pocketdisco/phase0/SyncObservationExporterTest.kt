package dev.pocketdisco.phase0

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncObservationExporterTest {
    @Test
    fun mapsFirstCoordinatorStartToSharedClockObservation() {
        var elapsed = 9_000L
        val recorder = recorder { elapsed }
        coordinatorApplied(recorder)
        elapsed = 9_017
        playbackEvent(recorder, "playback_started")
        elapsed = 9_020
        playbackEvent(recorder, "playback_started")

        val observations = SyncObservationExporter.fromRawEvents(recorder.snapshot())

        assertEquals(1, observations.size)
        assertEquals("playback_start", observations.single().eventType)
        assertEquals("trial-1", observations.single().trialId)
        assertEquals("20000", observations.single().startId)
        assertEquals("phone-a", observations.single().deviceId)
        assertEquals("built_in", observations.single().outputCategory)
        assertEquals(20_017L, observations.single().timestampMs)
        assertEquals("coordinator:trial-1", observations.single().clockId)
    }

    @Test
    fun mapsFailureAndIgnoresManualTarget() {
        var elapsed = 9_000L
        val recorder = recorder { elapsed }
        coordinatorApplied(recorder)
        elapsed = 9_100
        playbackEvent(recorder, "playback_start_failed", "operator_marked_failure")
        recorder.record(
            deviceLabel = "phone-a",
            trialId = "manual-1",
            outputCategory = "built_in",
            category = "coordination",
            name = "manual_target_converted",
            targetWallTimeMs = 30_000,
            targetElapsedRealtimeMs = 19_000,
        )
        recorder.record(
            deviceLabel = "phone-a",
            trialId = "manual-1",
            outputCategory = "built_in",
            category = "licensed_audio",
            name = "playback_started",
            targetWallTimeMs = 30_000,
            targetElapsedRealtimeMs = 19_000,
        )

        val observations = SyncObservationExporter.fromRawEvents(recorder.snapshot())
        val json = SyncObservationExporter.toNdjson(recorder.snapshot())

        assertEquals(1, observations.size)
        assertEquals("failure", observations.single().outcome)
        assertEquals("operator_marked_failure", observations.single().failureReason)
        assertTrue(json.contains("\"schema_version\":1"))
        assertTrue(json.contains("\"failure_reason\":\"operator_marked_failure\""))
        assertFalse(json.contains("manual-1"))
    }

    @Test
    fun keepsIdentityCapturedWhenCoordinatorTrialWasApplied() {
        val recorder = recorder { 9_017L }
        coordinatorApplied(recorder)
        recorder.record(
            deviceLabel = "edited-phone",
            trialId = "edited-trial",
            outputCategory = "wired",
            category = "licensed_audio",
            name = "playback_started",
            targetWallTimeMs = 20_000,
            targetElapsedRealtimeMs = 9_000,
        )

        val observation = SyncObservationExporter.fromRawEvents(recorder.snapshot()).single()

        assertEquals("phone-a", observation.deviceId)
        assertEquals("trial-1", observation.trialId)
        assertEquals("built_in", observation.outputCategory)
        assertEquals("coordinator:trial-1", observation.clockId)
    }

    private fun recorder(elapsed: () -> Long) = TelemetryRecorder(
        wallClockMs = { 100_000L },
        elapsedRealtimeMs = elapsed,
        sessionId = "session-1",
    )

    private fun coordinatorApplied(recorder: TelemetryRecorder) {
        recorder.record(
            deviceLabel = "phone-a",
            trialId = "trial-1",
            outputCategory = "built_in",
            category = "coordination",
            name = "coordinator_trial_applied",
            targetWallTimeMs = 20_000,
            targetElapsedRealtimeMs = 9_000,
        )
    }

    private fun playbackEvent(recorder: TelemetryRecorder, name: String, detail: String = "") {
        recorder.record(
            deviceLabel = "phone-a",
            trialId = "trial-1",
            outputCategory = "built_in",
            category = "licensed_audio",
            name = name,
            targetWallTimeMs = 20_000,
            targetElapsedRealtimeMs = 9_000,
            detail = detail,
        )
    }
}
