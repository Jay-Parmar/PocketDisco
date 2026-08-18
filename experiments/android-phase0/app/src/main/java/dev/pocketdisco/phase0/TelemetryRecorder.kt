package dev.pocketdisco.phase0

import java.util.UUID

data class TelemetryEvent(
    val sequence: Long,
    val sessionId: String,
    val deviceLabel: String,
    val trialId: String,
    val outputCategory: String,
    val category: String,
    val name: String,
    val wallTimeMs: Long,
    val elapsedRealtimeMs: Long,
    val playerPositionMs: Long?,
    val targetWallTimeMs: Long?,
    val targetElapsedRealtimeMs: Long?,
    val detail: String,
)

class TelemetryRecorder(
    private val wallClockMs: () -> Long,
    private val elapsedRealtimeMs: () -> Long,
    private val sessionId: String = UUID.randomUUID().toString(),
) {
    private val events = mutableListOf<TelemetryEvent>()
    private var nextSequence = 1L

    @Synchronized
    fun record(
        deviceLabel: String,
        trialId: String,
        outputCategory: String = "",
        category: String,
        name: String,
        playerPositionMs: Long? = null,
        targetWallTimeMs: Long? = null,
        targetElapsedRealtimeMs: Long? = null,
        detail: String = "",
        observedElapsedRealtimeMs: Long? = null,
    ): TelemetryEvent {
        val event = TelemetryEvent(
            sequence = nextSequence++,
            sessionId = sessionId,
            deviceLabel = deviceLabel.trim().take(100),
            trialId = trialId.trim().take(100),
            outputCategory = outputCategory.take(100),
            category = category.take(100),
            name = name.take(100),
            wallTimeMs = wallClockMs(),
            elapsedRealtimeMs = observedElapsedRealtimeMs ?: elapsedRealtimeMs(),
            playerPositionMs = playerPositionMs,
            targetWallTimeMs = targetWallTimeMs,
            targetElapsedRealtimeMs = targetElapsedRealtimeMs,
            detail = detail.take(4_000),
        )
        events += event
        return event
    }

    @Synchronized
    fun toNdjson(): String = events.joinToString(separator = "\n", postfix = if (events.isEmpty()) "" else "\n") {
        it.toJsonLine()
    }

    @Synchronized
    fun size(): Int = events.size

    @Synchronized
    fun snapshot(): List<TelemetryEvent> = events.toList()
}

internal fun TelemetryEvent.toJsonLine(): String = buildString {
    append('{')
    appendJsonField("sequence", sequence)
    appendJsonField("session_id", sessionId)
    appendJsonField("device_label", deviceLabel)
    appendJsonField("trial_id", trialId)
    appendJsonField("output_category", outputCategory)
    appendJsonField("category", category)
    appendJsonField("name", name)
    appendJsonField("wall_time_ms", wallTimeMs)
    appendJsonField("elapsed_realtime_ms", elapsedRealtimeMs)
    appendJsonField("player_position_ms", playerPositionMs)
    appendJsonField("target_wall_time_ms", targetWallTimeMs)
    appendJsonField("target_elapsed_realtime_ms", targetElapsedRealtimeMs)
    appendJsonField("detail", detail, isLast = true)
    append('}')
}

private fun StringBuilder.appendJsonField(name: String, value: String, isLast: Boolean = false) {
    append(JsonString.quote(name))
    append(':')
    append(JsonString.quote(value))
    if (!isLast) append(',')
}

private fun StringBuilder.appendJsonField(name: String, value: Long?, isLast: Boolean = false) {
    append(JsonString.quote(name))
    append(':')
    append(value ?: "null")
    if (!isLast) append(',')
}

object JsonString {
    fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (character.code < 0x20) {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
        }
        append('"')
    }
}
