package dev.pocketdisco.phase0

data class SyncObservation(
    val eventType: String,
    val trialId: String,
    val startId: String,
    val deviceId: String,
    val provider: String,
    val outputCategory: String,
    val outcome: String,
    val timestampMs: Long? = null,
    val clockId: String? = null,
    val failureReason: String? = null,
) {
    fun toJsonLine(): String = buildString {
        append('{')
        append("\"schema_version\":1")
        append(",\"event_type\":${JsonString.quote(eventType)}")
        append(",\"trial_id\":${JsonString.quote(trialId)}")
        append(",\"start_id\":${JsonString.quote(startId)}")
        append(",\"device_id\":${JsonString.quote(deviceId)}")
        append(",\"provider\":${JsonString.quote(provider)}")
        append(",\"output_category\":${JsonString.quote(outputCategory)}")
        append(",\"outcome\":${JsonString.quote(outcome)}")
        if (timestampMs != null) append(",\"timestamp_ms\":$timestampMs")
        if (clockId != null) append(",\"clock_id\":${JsonString.quote(clockId)}")
        if (failureReason != null) append(",\"failure_reason\":${JsonString.quote(failureReason)}")
        append('}')
    }
}

object SyncObservationExporter {
    private val validOutputs = setOf("built_in", "wired", "bluetooth")
    private val terminalEventNames = setOf("playback_started", "playback_error", "playback_start_failed")

    fun fromRawEvents(events: List<TelemetryEvent>): List<SyncObservation> {
        val coordinatorStarts = events
            .filter { it.category == "coordination" && it.name == "coordinator_trial_applied" }
            .mapNotNull(::coordinatorStart)
            .associateBy(CoordinatorStart::timing)
        val emitted = mutableSetOf<TimingKey>()
        return buildList {
            events.sortedBy(TelemetryEvent::sequence).forEach { event ->
                if (event.category != "licensed_audio" || event.name !in terminalEventNames) return@forEach
                val timing = timingKey(event) ?: return@forEach
                val start = coordinatorStarts[timing] ?: return@forEach
                if (!emitted.add(timing)) return@forEach
                if (event.name == "playback_started") {
                    add(
                        observation(
                            start = start,
                            outcome = "ok",
                            timestampMs = timing.targetWallTimeMs +
                                (event.elapsedRealtimeMs - timing.targetElapsedRealtimeMs),
                            failureReason = null,
                        ),
                    )
                } else {
                    add(
                        observation(
                            start = start,
                            outcome = "failure",
                            timestampMs = null,
                            failureReason = event.detail.ifBlank { event.name },
                        ),
                    )
                }
            }
        }
    }

    fun toNdjson(events: List<TelemetryEvent>): String {
        val observations = fromRawEvents(events)
        return if (observations.isEmpty()) {
            ""
        } else {
            observations.joinToString(separator = "\n", postfix = "\n", transform = SyncObservation::toJsonLine)
        }
    }

    private fun observation(
        start: CoordinatorStart,
        outcome: String,
        timestampMs: Long?,
        failureReason: String?,
    ) = SyncObservation(
        eventType = "playback_start",
        trialId = start.trialId,
        startId = start.timing.targetWallTimeMs.toString(),
        deviceId = start.deviceId,
        provider = "licensed_audio",
        outputCategory = start.outputCategory,
        outcome = outcome,
        timestampMs = timestampMs,
        clockId = if (outcome == "ok") "coordinator:${start.trialId}" else null,
        failureReason = failureReason,
    )

    private fun coordinatorStart(event: TelemetryEvent): CoordinatorStart? {
        val timing = timingKey(event) ?: return null
        if (event.trialId.isBlank() || event.deviceLabel.isBlank()) return null
        if (event.outputCategory !in validOutputs) return null
        return CoordinatorStart(
            timing = timing,
            trialId = event.trialId,
            deviceId = event.deviceLabel,
            outputCategory = event.outputCategory,
        )
    }

    private fun timingKey(event: TelemetryEvent): TimingKey? {
        val targetWall = event.targetWallTimeMs ?: return null
        val targetElapsed = event.targetElapsedRealtimeMs ?: return null
        return TimingKey(
            targetWallTimeMs = targetWall,
            targetElapsedRealtimeMs = targetElapsed,
        )
    }

    private data class CoordinatorStart(
        val timing: TimingKey,
        val trialId: String,
        val deviceId: String,
        val outputCategory: String,
    )

    private data class TimingKey(
        val targetWallTimeMs: Long,
        val targetElapsedRealtimeMs: Long,
    )
}
