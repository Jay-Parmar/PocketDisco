package dev.pocketdisco.phase0

import kotlin.math.max

data class ClockSample(
    val clientSendElapsedRealtimeMs: Long,
    val clientReceiveElapsedRealtimeMs: Long,
    val serverReceiveUnixMs: Long,
    val serverSendUnixMs: Long,
) {
    init {
        require(clientReceiveElapsedRealtimeMs >= clientSendElapsedRealtimeMs)
        require(serverSendUnixMs >= serverReceiveUnixMs)
    }

    val roundTripTimeMs: Long
        get() = clientReceiveElapsedRealtimeMs - clientSendElapsedRealtimeMs

    val serverProcessingTimeMs: Long
        get() = serverSendUnixMs - serverReceiveUnixMs

    val networkRoundTripTimeMs: Long
        get() = max(0L, roundTripTimeMs - serverProcessingTimeMs)

    val serverToElapsedOffsetMs: Long
        get() {
            val clientMidpoint = clientSendElapsedRealtimeMs + roundTripTimeMs / 2L
            val serverMidpoint = serverReceiveUnixMs + serverProcessingTimeMs / 2L
            return serverMidpoint - clientMidpoint
        }
}
data class ClockEstimate(
    val serverToElapsedOffsetMs: Long,
    val uncertaintyMs: Long,
    val bestNetworkRoundTripTimeMs: Long,
    val sampleCount: Int,
) {
    fun elapsedRealtimeForServerUnix(serverUnixMs: Long): Long =
        Math.subtractExact(serverUnixMs, serverToElapsedOffsetMs)

    fun serverUnixForElapsedRealtime(elapsedRealtimeMs: Long): Long =
        Math.addExact(elapsedRealtimeMs, serverToElapsedOffsetMs)
}

object ClockEstimator {
    private const val PREFERRED_SAMPLE_COUNT = 3

    fun estimate(samples: List<ClockSample>): ClockEstimate {
        require(samples.isNotEmpty()) { "At least one clock sample is required" }
        val preferred = samples
            .sortedBy(ClockSample::networkRoundTripTimeMs)
            .take(PREFERRED_SAMPLE_COUNT)
        val offsets = preferred.map(ClockSample::serverToElapsedOffsetMs).sorted()
        val medianOffset = offsets[offsets.size / 2]
        val offsetSpread = offsets.maxOf { kotlin.math.abs(it - medianOffset) }
        val bestNetworkRtt = preferred.minOf(ClockSample::networkRoundTripTimeMs)
        return ClockEstimate(
            serverToElapsedOffsetMs = medianOffset,
            uncertaintyMs = bestNetworkRtt / 2L + offsetSpread,
            bestNetworkRoundTripTimeMs = bestNetworkRtt,
            sampleCount = samples.size,
        )
    }
}
