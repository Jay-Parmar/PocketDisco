package dev.pocketdisco.phase0

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedSyncTelemetryTest {
    @Test
    fun exportsCommandAndPlayerTimingWithFrozenIdentity() {
        val recorder = GeneratedSyncTelemetryRecorder()
        recorder.begin(context())

        recorder.record(probeEvent("play_commands_issued", 4_004))
        recorder.record(probeEvent("playback_observed", 4_012))
        recorder.record(probeEvent("playback_observed", 4_020))

        val observations = recorder.snapshot()
        assertEquals(2, observations.size)
        assertEquals("command_issued", observations[0].eventType)
        assertEquals(10_004L, observations[0].timestampMs)
        assertEquals("playback_observed", observations[1].eventType)
        assertEquals(10_012L, observations[1].timestampMs)
        observations.forEach { observation ->
            assertEquals("mixed-01", observation.scenarioId)
            assertEquals("android-phone", observation.deviceId)
            assertEquals("10000", observation.startId)
            assertEquals("generated_audio", observation.provider)
            assertEquals(ClickSignal.SIGNAL_ID, observation.signalId)
            assertEquals(ClickSignal.PCM_SHA256, observation.signalSha256)
            assertEquals(10_000L, observation.targetTimestampMs)
            assertEquals("coordinator:trial-01", observation.clockId)
            assertEquals(3L, observation.clockUncertaintyMs)
        }
    }

    @Test
    fun exportsSchemaV2WithoutCredentialsOrAcousticClaims() {
        val recorder = GeneratedSyncTelemetryRecorder()
        recorder.begin(context())
        recorder.record(probeEvent("play_commands_issued", 4_004))
        recorder.record(probeEvent("playback_observed", 4_012))

        val json = recorder.toNdjson()

        assertTrue(json.contains("\"schema_version\":2"))
        assertTrue(json.contains("\"platform\":\"android\""))
        assertTrue(json.contains("\"environment\":\"physical\""))
        assertTrue(json.contains("\"output_category\":\"bluetooth\""))
        assertTrue(json.contains("\"route_mode\":\"system_group\""))
        assertTrue(json.contains("\"clock_source\":\"coordinator_estimate\""))
        assertFalse(json.contains("output_id"))
        assertFalse(json.contains("acoustic_onset"))
        assertFalse(json.contains("coordinator_url"))
        assertFalse(json.contains("bearer"))
    }

    @Test
    fun recordsStableFailuresAndIgnoresLocalEvents() {
        val recorder = GeneratedSyncTelemetryRecorder()
        recorder.begin(context())
        recorder.record(probeEvent("route_sample", 3_900))
        recorder.record(probeEvent("playback_start_failed", 4_001, "error=PrivateException"))
        recorder.record(probeEvent("playback_start_failed", 4_002))

        assertEquals(2, recorder.size())
        assertTrue(recorder.snapshot().all { it.outcome == "failure" })
        assertTrue(recorder.snapshot().all { it.failureReason == "player_failed" })
        assertFalse(recorder.toNdjson().contains("PrivateException"))

        recorder.clearActive()
        recorder.record(probeEvent("play_commands_issued", 5_000))
        assertEquals(2, recorder.size())
    }

    @Test
    fun completesPendingMeasurementsWhenRouteIsLostOrContextIsCleared() {
        val recorder = GeneratedSyncTelemetryRecorder()
        recorder.begin(context())
        recorder.record(probeEvent("play_commands_issued", 4_004))
        recorder.record(probeEvent("route_lost", 4_008))

        assertEquals(2, recorder.size())
        assertEquals("playback_observed", recorder.snapshot()[1].eventType)
        assertEquals("route_lost", recorder.snapshot()[1].failureReason)

        recorder.begin(context().copy(trialId = "trial-02"))
        recorder.clearActive("cancelled")

        assertEquals(4, recorder.size())
        assertTrue(recorder.snapshot().takeLast(2).all { it.failureReason == "cancelled" })
    }

    @Test
    fun validatesAndNormalizesOperatorIdentity() {
        val identity = GeneratedSyncIdentity.fromInput(
            scenarioId = "  mixed-01  ",
            deviceId = " android-emulator ",
            environment = GeneratedSyncEnvironment.EMULATOR,
            outputCategory = GeneratedSyncOutputCategory.VIRTUAL,
            routeMode = GeneratedSyncRouteMode.APP_FANOUT,
        )

        assertEquals("mixed-01", identity.scenarioId)
        assertEquals("android-emulator", identity.deviceId)
        assertThrows(IllegalArgumentException::class.java) {
            GeneratedSyncIdentity.fromInput(
                scenarioId = " ",
                deviceId = "android-phone",
                environment = GeneratedSyncEnvironment.PHYSICAL,
                outputCategory = GeneratedSyncOutputCategory.BUILT_IN,
                routeMode = GeneratedSyncRouteMode.SYSTEM_GROUP,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            GeneratedSyncIdentity.fromInput(
                scenarioId = "mixed-01",
                deviceId = "android\nphone",
                environment = GeneratedSyncEnvironment.PHYSICAL,
                outputCategory = GeneratedSyncOutputCategory.BUILT_IN,
                routeMode = GeneratedSyncRouteMode.SYSTEM_GROUP,
            )
        }
    }

    private fun context() = GeneratedSyncContext(
        identity = GeneratedSyncIdentity.fromInput(
            scenarioId = "mixed-01",
            deviceId = "android-phone",
            environment = GeneratedSyncEnvironment.PHYSICAL,
            outputCategory = GeneratedSyncOutputCategory.BLUETOOTH,
            routeMode = GeneratedSyncRouteMode.SYSTEM_GROUP,
        ),
        trialId = "trial-01",
        targetTimestampMs = 10_000,
        targetElapsedRealtimeMs = 4_000,
        clockUncertaintyMs = 3,
    )

    private fun probeEvent(
        name: String,
        elapsedRealtimeMs: Long,
        detail: String = "",
    ) = MultiOutputProbeEvent(
        name = name,
        elapsedRealtimeMs = elapsedRealtimeMs,
        status = null,
        detail = detail,
    )
}
