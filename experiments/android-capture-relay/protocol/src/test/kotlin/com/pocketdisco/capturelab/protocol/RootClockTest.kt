package com.pocketdisco.capturelab.protocol

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootClockTest {
    private val clock = RootClock()
    private var local = 100_000_000_000L

    @Test
    fun `three samples establish a positive or negative root offset`() {
        for (offset in listOf(-7_000_000_000L, 3_000_000_000L)) {
            clock.reset()
            repeat(2) {
                sample(offset)
                assertFalse(clock.estimate(local)!!.ready)
            }
            sample(offset)
            val estimate = clock.estimate(local)!!
            assertTrue(estimate.ready)
            assertEquals(offset, estimate.offsetNanos)
            assertTrue(estimate.uncertaintyNanos in 10_000_000L..11_000_000L)
        }
    }

    @Test
    fun `root processing time is excluded from round trip uncertainty`() {
        repeat(3) { sample(2_000_000_000, outbound = 5_000_000, inbound = 5_000_000, processing = 100_000_000) }
        val estimate = clock.estimate(local)!!
        assertEquals(2_000_000_000L, estimate.offsetNanos)
        assertTrue(estimate.uncertaintyNanos in 5_000_000L..6_000_000L)
        assertTrue(estimate.ready)
    }

    @Test
    fun `uncertainty grows with the age of the selected sample`() {
        repeat(3) { sample(0) }
        val initial = clock.estimate(local)!!
        val aged = clock.estimate(local + 2_000_000_000L)!!

        assertEquals(initial.ageNanos + 2_000_000_000L, aged.ageNanos)
        assertEquals(400_000L, aged.uncertaintyNanos - initial.uncertaintyNanos)
        assertEquals(10_000_000L + aged.ageNanos / 5_000, aged.uncertaintyNanos)
        assertEquals(3, aged.samples)
        assertTrue(aged.ready)
    }

    @Test
    fun `age uncertainty crosses the readiness limit before samples expire`() {
        repeat(3) { sample(0, outbound = 99_750_000, inbound = 99_750_000) }
        val initial = clock.estimate(local)!!
        assertTrue(initial.ready)
        val boundaryAge = (RootClock.MAX_UNCERTAINTY_NANOS - 99_750_000L) * 5_000
        val boundaryAt = local + boundaryAge - initial.ageNanos
        val boundary = clock.estimate(boundaryAt)!!
        val overLimit = clock.estimate(boundaryAt + 5_000)!!

        assertTrue(boundary.ready)
        assertEquals(3, overLimit.samples)
        assertTrue(overLimit.ageNanos < RootClock.MAX_AGE_NANOS)
        assertFalse(overLimit.ready)
        assertEquals(RootClock.MAX_UNCERTAINTY_NANOS, boundary.uncertaintyNanos)
        assertEquals(RootClock.MAX_UNCERTAINTY_NANOS + 1, overLimit.uncertaintyNanos)
    }

    @Test
    fun `asymmetric paths include offset error in uncertainty`() {
        val offset = -1_000_000_000L
        repeat(3) { sample(offset, outbound = 35_000_000, inbound = 5_000_000) }
        val estimate = clock.estimate(local)!!
        assertEquals(offset + 15_000_000, estimate.offsetNanos)
        assertTrue(abs(estimate.offsetNanos - offset) <= estimate.uncertaintyNanos)
    }

    @Test
    fun `queued slow probes cannot replace a good mapping`() {
        repeat(3) { sample(0) }
        val before = clock.estimate(local)!!.offsetNanos
        assertFalse(clock.add(local, local + 250_000_000, local + 250_000_000, local + 500_000_000))
        assertEquals(before, clock.estimate(local)!!.offsetNanos)
        assertTrue(clock.estimate(local)!!.ready)
    }

    @Test
    fun `lower latency samples slew the estimate by at most one millisecond`() {
        repeat(3) { sample(0) }
        repeat(5) { index ->
            sample(10_000_000, outbound = 1_000_000, inbound = 1_000_000)
            assertEquals((index + 1) * 1_000_000L, clock.estimate(local)!!.offsetNanos)
        }
    }

    @Test
    fun `clock jumps require fresh samples instead of scheduling with the old offset`() {
        repeat(3) { sample(0) }
        sample(500_000_000)
        assertFalse(clock.estimate(local)!!.ready)
        assertEquals(1, clock.estimate(local)!!.samples)
        repeat(2) { sample(500_000_000) }
        assertTrue(clock.estimate(local)!!.ready)
        assertEquals(500_000_000L, clock.estimate(local)!!.offsetNanos)
    }

    @Test
    fun `stale samples and backwards local time invalidate the mapping`() {
        repeat(3) { sample(0) }
        assertNull(clock.estimate(local + RootClock.MAX_AGE_NANOS + 1))
        repeat(3) { sample(0) }
        assertNotNull(clock.estimate(local))
        assertNull(clock.estimate(local - 1_000_000_000))
    }

    @Test
    fun `partial expiry drops readiness without clearing reply diagnostics`() {
        val started = local
        repeat(3) { index ->
            val sent = started + index * 1_000_000_000L
            assertTrue(clock.add(sent, sent + 10_000_000, sent + 10_000_000, sent + 20_000_000))
        }
        assertTrue(clock.estimate(started + 2_020_000_000)!!.ready)

        val estimate = clock.estimate(started + 5_020_000_001)!!
        assertEquals(2, estimate.samples)
        assertFalse(estimate.ready)
        assertTrue(estimate.ageNanos < RootClock.MAX_AGE_NANOS)
        assertTrue(estimate.uncertaintyNanos < RootClock.MAX_UNCERTAINTY_NANOS)
        assertEquals(3L, clock.acceptedReplies)
        assertEquals(0L, clock.rejectedReplies)

        assertNull(clock.estimate(started + 7_020_000_001))
        assertEquals(3L, clock.acceptedReplies)
        assertEquals(20_000_000L, clock.lastRoundTripNanos)
        assertEquals(20_000_000L, clock.lastElapsedNanos)
    }

    @Test
    fun `rejected replies retain their timing without replacing the mapping`() {
        repeat(3) { sample(0) }
        val before = clock.estimate(local)!!
        assertFalse(clock.add(local, local + 250_000_000, local + 250_000_000, local + 500_000_000))

        assertEquals(3L, clock.acceptedReplies)
        assertEquals(1L, clock.rejectedReplies)
        assertEquals(500_000_000L, clock.lastRoundTripNanos)
        assertEquals(500_000_000L, clock.lastElapsedNanos)
        assertEquals(before.offsetNanos, clock.estimate(local + 500_000_000)!!.offsetNanos)
        assertEquals(before.samples, clock.estimate(local + 500_000_000)!!.samples)
    }

    @Test
    fun `invalid intervals never report negative diagnostic durations`() {
        assertNull(clock.lastElapsedNanos)
        assertNull(clock.lastRoundTripNanos)
        assertFalse(clock.add(-1, 1, 2, 3))
        assertNull(clock.lastElapsedNanos)
        assertNull(clock.lastRoundTripNanos)

        assertFalse(clock.add(1, 4, 3, 6))
        assertEquals(5L, clock.lastElapsedNanos)
        assertNull(clock.lastRoundTripNanos)
        assertFalse(clock.add(1, 2, 20, 6))
        assertEquals(5L, clock.lastElapsedNanos)
        assertNull(clock.lastRoundTripNanos)
        assertEquals(0L, clock.acceptedReplies)
        assertEquals(3L, clock.rejectedReplies)
    }

    @Test
    fun `mapping resets preserve lifetime reply counters`() {
        repeat(3) { sample(0) }
        sample(500_000_000)
        assertEquals(4L, clock.acceptedReplies)
        assertEquals(1, clock.estimate(local)!!.samples)
        clock.reset()
        assertNull(clock.estimate(local))
        assertEquals(4L, clock.acceptedReplies)
        assertEquals(0L, clock.rejectedReplies)
        assertEquals(20_000_000L, clock.lastRoundTripNanos)
        assertEquals(21_000_000L, clock.lastElapsedNanos)
    }

    @Test
    fun `sample storage remains bounded`() {
        repeat(40) { sample(0) }
        assertEquals(8, clock.estimate(local)!!.samples)
        clock.reset()
        assertNull(clock.estimate(local))
    }

    @Test
    fun `malformed sample intervals never create a mapping`() {
        for (times in listOf(
            longArrayOf(-1, 1, 2, 3), longArrayOf(1, -1, 2, 3),
            longArrayOf(1, 3, 2, 4), longArrayOf(4, 2, 3, 1),
            longArrayOf(1, 2, 10, 5), longArrayOf(1, 2, 3, 2_000_000_000),
        )) {
            assertFalse(clock.add(times[0], times[1], times[2], times[3]))
        }
        assertNull(clock.estimate(local))
    }

    @Test
    fun `large valid epochs do not overflow midpoint arithmetic`() {
        local = Long.MAX_VALUE - 1_000_000_000
        repeat(3) { sample(-8_000_000_000_000_000_000L) }
        val estimate = clock.estimate(local)!!
        assertEquals(-8_000_000_000_000_000_000L, estimate.offsetNanos)
        assertTrue(estimate.ready)
    }

    @Test
    fun `three hop probes still map directly to the root clock`() {
        val receivers = listOf(RootClock(), RootClock(), RootClock())
        val offsets = listOf(5_000_000_000L, -3_000_000_000L, 70_000_000_000L)
        for ((index, receiver) in receivers.withIndex()) {
            val transit = (index + 1) * 10_000_000L
            repeat(3) { round ->
                val sent = local + round * 100_000_000
                assertTrue(receiver.add(sent, sent + transit + offsets[index],
                    sent + transit + offsets[index] + 1_000_000, sent + transit * 2 + 1_000_000))
            }
        }
        val rootDeadline = local + 2_000_000_000
        receivers.forEachIndexed { index, receiver ->
            val estimate = receiver.estimate(local + 500_000_000)!!
            assertTrue(estimate.ready)
            val localDeadline = rootDeadline - estimate.offsetNanos
            assertEquals(rootDeadline, localDeadline + offsets[index])
        }
    }

    private fun sample(offset: Long, outbound: Long = 10_000_000, inbound: Long = 10_000_000,
        processing: Long = 1_000_000) {
        val received = local + outbound + processing + inbound
        assertTrue(clock.add(local, local + outbound + offset,
            local + outbound + offset + processing, received))
        local = received + 50_000_000
    }
}
