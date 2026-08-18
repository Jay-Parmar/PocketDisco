package dev.pocketdisco.phase0

import org.junit.Assert.assertEquals
import org.junit.Test

class ClockEstimatorTest {
    @Test
    fun choosesMedianOffsetFromThreeLowestNetworkRttSamples() {
        val samples = listOf(
            sample(clientStart = 1_000, localRtt = 100, serverMidpoint = 11_050, serverWork = 0),
            sample(clientStart = 2_000, localRtt = 20, serverMidpoint = 12_011, serverWork = 2),
            sample(clientStart = 3_000, localRtt = 30, serverMidpoint = 13_014, serverWork = 4),
            sample(clientStart = 4_000, localRtt = 10, serverMidpoint = 14_005, serverWork = 0),
            sample(clientStart = 5_000, localRtt = 200, serverMidpoint = 14_900, serverWork = 0),
            sample(clientStart = 6_000, localRtt = 300, serverMidpoint = 16_300, serverWork = 0),
            sample(clientStart = 7_000, localRtt = 400, serverMidpoint = 16_500, serverWork = 0),
        )

        val estimate = ClockEstimator.estimate(samples)

        assertEquals(10_000, estimate.serverToElapsedOffsetMs)
        assertEquals(7, estimate.sampleCount)
        assertEquals(10, estimate.bestNetworkRoundTripTimeMs)
        assertEquals(6, estimate.uncertaintyMs)
        assertEquals(20_000, estimate.elapsedRealtimeForServerUnix(30_000))
        assertEquals(30_000, estimate.serverUnixForElapsedRealtime(20_000))
    }

    @Test
    fun subtractsServerProcessingFromNetworkRtt() {
        val sample = ClockSample(
            clientSendElapsedRealtimeMs = 100,
            clientReceiveElapsedRealtimeMs = 150,
            serverReceiveUnixMs = 1_020,
            serverSendUnixMs = 1_030,
        )

        assertEquals(50, sample.roundTripTimeMs)
        assertEquals(10, sample.serverProcessingTimeMs)
        assertEquals(40, sample.networkRoundTripTimeMs)
        assertEquals(900, sample.serverToElapsedOffsetMs)
    }

    private fun sample(
        clientStart: Long,
        localRtt: Long,
        serverMidpoint: Long,
        serverWork: Long,
    ): ClockSample {
        val receive = clientStart + localRtt
        val serverReceive = serverMidpoint - serverWork / 2
        return ClockSample(
            clientSendElapsedRealtimeMs = clientStart,
            clientReceiveElapsedRealtimeMs = receive,
            serverReceiveUnixMs = serverReceive,
            serverSendUnixMs = serverReceive + serverWork,
        )
    }
}
