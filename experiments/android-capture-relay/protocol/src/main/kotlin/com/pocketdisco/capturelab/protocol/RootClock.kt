package com.pocketdisco.capturelab.protocol

import java.util.ArrayDeque
import kotlin.math.abs

data class TimedAudioFrame(
    val frame: AudioFrame,
    val playAtNanos: Long,
    val uncertaintyNanos: Long,
)

internal data class ClockEstimate(
    val offsetNanos: Long,
    val uncertaintyNanos: Long,
    val ageNanos: Long,
    val samples: Int,
    val ready: Boolean,
)

internal class RootClock {
    private data class Sample(val offset: Long, val roundTrip: Long, val receivedAt: Long)

    private val samples = ArrayDeque<Sample>()
    private var offset: Long? = null
    // Mapping resets leave lifetime reply diagnostics intact.
    var acceptedReplies = 0L
        private set
    var rejectedReplies = 0L
        private set
    var lastRoundTripNanos: Long? = null
        private set
    var lastElapsedNanos: Long? = null
        private set

    fun add(sent: Long, rootReceived: Long, rootSent: Long, received: Long): Boolean {
        val elapsed = if (sent >= 0 && received >= sent) received - sent else null
        val processing = if (rootReceived >= 0 && rootSent >= rootReceived) rootSent - rootReceived else null
        lastElapsedNanos = elapsed
        lastRoundTripNanos = if (elapsed != null && processing != null && processing <= elapsed) {
            elapsed - processing
        } else null
        if (elapsed == null || processing == null) return rejectReply()
        if (elapsed > MAX_ROUND_TRIP_NANOS || processing > elapsed) return rejectReply()
        if (elapsed - processing > MAX_UNCERTAINTY_NANOS * 2) return rejectReply()
        expire(received)
        val sample = Sample(
            rootReceived + processing / 2 - (sent + elapsed / 2),
            elapsed - processing,
            received,
        )
        if (samples.isEmpty()) offset = null
        val previous = offset
        if (previous != null && distance(previous, sample.offset) > MAX_STEP_NANOS) {
            reset()
        }
        samples.addLast(sample)
        while (samples.size > MAX_SAMPLES) samples.removeFirst()
        val best = samples.minBy { it.roundTrip }
        offset = offset?.let { it + (best.offset - it).coerceIn(-MAX_SLEW_NANOS, MAX_SLEW_NANOS) }
            ?: best.offset
        acceptedReplies++
        return true
    }

    fun estimate(now: Long): ClockEstimate? {
        expire(now)
        if (samples.isEmpty()) return null
        val best = samples.minBy { it.roundTrip }
        val currentOffset = checkNotNull(offset)
        val age = now - best.receivedAt
        val uncertainty = (best.roundTrip + 1) / 2 + distance(currentOffset, best.offset)
            + age / 5_000
        return ClockEstimate(
            currentOffset, uncertainty, age, samples.size,
            samples.size >= MIN_SAMPLES && uncertainty <= MAX_UNCERTAINTY_NANOS,
        )
    }

    fun reset() {
        samples.clear()
        offset = null
    }

    private fun rejectReply(): Boolean {
        rejectedReplies++
        return false
    }

    private fun expire(now: Long) {
        if (samples.any { now < it.receivedAt }) {
            reset()
            return
        }
        while (samples.isNotEmpty() && now - samples.first.receivedAt > MAX_AGE_NANOS) {
            samples.removeFirst()
        }
        if (samples.isEmpty()) offset = null
    }

    private fun distance(first: Long, second: Long): Long = try {
        abs(Math.subtractExact(first, second)).takeIf { it >= 0 } ?: Long.MAX_VALUE
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }

    companion object {
        const val MAX_AGE_NANOS = 5_000_000_000L
        const val MAX_UNCERTAINTY_NANOS = 100_000_000L
        private const val MAX_ROUND_TRIP_NANOS = 1_000_000_000L
        private const val MAX_SLEW_NANOS = 1_000_000L
        private const val MAX_STEP_NANOS = 250_000_000L
        private const val MIN_SAMPLES = 3
        private const val MAX_SAMPLES = 8
    }
}
