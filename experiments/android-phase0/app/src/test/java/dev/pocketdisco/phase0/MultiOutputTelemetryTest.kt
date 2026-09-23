package dev.pocketdisco.phase0

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiOutputTelemetryTest {
    @Test
    fun capabilityDetailExcludesDeviceNames() {
        val snapshot = OutputCapabilitySnapshot(
            sdkInt = 36,
            leAudio = FeatureSupport.SUPPORTED,
            leAudioBroadcastSource = FeatureSupport.NOT_SUPPORTED,
            outputs = listOf(
                OutputDeviceDescriptor(
                    id = 7,
                    label = "Private headphones",
                    androidType = 8,
                    transport = OutputTransport.BLUETOOTH,
                    targetRole = OutputTargetRole.DIRECT,
                ),
            ),
        )

        val detail = MultiOutputTelemetry.capabilityDetail(snapshot)

        assertTrue(detail.contains("id=7"))
        assertTrue(detail.contains("transport=bluetooth"))
        assertFalse(detail.contains("Private headphones"))
    }

    @Test
    fun eventDetailIncludesRoutesAndTiming() {
        val event = MultiOutputProbeEvent(
            name = "route_sample",
            elapsedRealtimeMs = 123,
            status = MultiOutputStatus(
                mode = AndroidRouteMode.DUAL_TRACK,
                route = OutputRouteResult(OutputRouteState.DISTINCT_ROUTES, setOf(7, 8)),
                tracks = listOf(
                    OutputTrackStatus("track_1", 7, true, setOf(7), 0, 480, 1_000),
                    OutputTrackStatus("track_2", 8, true, setOf(8), 1, 475, 1_100),
                ),
            ),
            detail = "play_call_span_ns=100",
        )

        val detail = MultiOutputTelemetry.eventDetail(event)

        assertTrue(detail.contains("route_state=distinct_routes"))
        assertTrue(detail.contains("actual_ids=7,8"))
        assertTrue(detail.contains("underruns=1"))
        assertTrue(detail.contains("play_call_span_ns=100"))
    }
}
