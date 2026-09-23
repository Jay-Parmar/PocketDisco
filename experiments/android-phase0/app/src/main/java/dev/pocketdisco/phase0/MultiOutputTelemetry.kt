package dev.pocketdisco.phase0

object MultiOutputTelemetry {
    fun capabilityDetail(snapshot: OutputCapabilitySnapshot): String = buildString {
        append("sdk=${snapshot.sdkInt}")
        append(";le_audio=${snapshot.leAudio.name.lowercase()}")
        append(";le_broadcast_source=${snapshot.leAudioBroadcastSource.name.lowercase()}")
        append(";outputs=")
        append(
            snapshot.outputs.joinToString("|") { output ->
                "id=${output.id},type=${output.androidType}," +
                    "transport=${output.transport.name.lowercase()}," +
                    "role=${output.targetRole.name.lowercase()}"
            },
        )
    }

    fun eventDetail(event: MultiOutputProbeEvent): String = buildString {
        event.status?.let { status ->
            append("mode=${status.mode.name.lowercase()}")
            append(";route_state=${status.route.state.name.lowercase()}")
            append(";actual_ids=${status.route.actualDeviceIds.sorted().joinToString(",")}")
            status.tracks.forEach { track ->
                append(";")
                append(track.trackLabel)
                append("[requested=${track.requestedDeviceId ?: "system"}")
                append(",preferred=${track.preferenceAccepted ?: "system"}")
                append(",actual=${track.actualDeviceIds.sorted().joinToString(",")}")
                append(",underruns=${track.underrunCount}")
                append(",frame=${track.framePosition ?: "none"}")
                append(",frame_ns=${track.frameNanoTime ?: "none"}]")
            }
        }
        if (event.detail.isNotBlank()) {
            if (isNotEmpty()) append(';')
            append(event.detail)
        }
    }
}
