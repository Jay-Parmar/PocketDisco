package com.pocketdisco.capturelab.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockProbeScheduleTest {
    private val schedule = ClockProbeSchedule()

    @Test
    fun `startup spends seven rapid probes then maintains at two per second`() {
        repeat(7) { index ->
            val now = index * 100_000_000L
            assertTrue(schedule.shouldProbe(now, false, 0, false))
            assertFalse(schedule.shouldProbe(now + 99_999_999, false, 0, false))
        }
        assertFalse(schedule.shouldProbe(1_099_999_999, false, 0, false))
        assertTrue(schedule.shouldProbe(1_100_000_000, false, 0, false))
        assertFalse(schedule.shouldProbe(1_599_999_999, false, 0, false))
        assertTrue(schedule.shouldProbe(1_600_000_000, false, 0, false))
    }

    @Test
    fun `restoring six fresh samples immediately ends rapid cadence`() {
        assertTrue(schedule.shouldProbe(0, false, 0, false))
        assertTrue(schedule.shouldProbe(100_000_000, true, 5, false))
        assertFalse(schedule.shouldProbe(200_000_000, true, 6, false))
        assertFalse(schedule.shouldProbe(599_999_999, true, 6, false))
        assertTrue(schedule.shouldProbe(600_000_000, true, 6, false))
    }

    @Test
    fun `low reserve does not rearm its burst until reserve is restored`() {
        assertTrue(schedule.shouldProbe(0, true, 6, false))
        repeat(7) { index ->
            assertTrue(schedule.shouldProbe(500_000_000L + index * 100_000_000L, true, 5, false))
        }
        assertFalse(schedule.shouldProbe(1_200_000_000, true, 5, false))
        assertFalse(schedule.shouldProbe(1_599_999_999, false, 2, false))
        assertTrue(schedule.shouldProbe(1_600_000_000, false, 2, false))
        assertFalse(schedule.shouldProbe(1_700_000_000, false, 2, false))
        assertTrue(schedule.shouldProbe(2_100_000_000, false, 2, false))
        assertFalse(schedule.shouldProbe(2_200_000_000, true, 6, false))
        assertTrue(schedule.shouldProbe(2_300_000_000, true, 5, false))
    }

    @Test
    fun `one pending probe blocks retries and a delayed tick never catches up`() {
        assertTrue(schedule.shouldProbe(0, false, 0, false))
        repeat(100) { index ->
            assertFalse(schedule.shouldProbe((index + 1) * 50_000_000L, false, 0, true))
        }
        assertTrue(schedule.shouldProbe(5_000_000_000, false, 0, false))
        assertFalse(schedule.shouldProbe(5_000_000_000, false, 0, false))
        assertFalse(schedule.shouldProbe(5_099_999_999, false, 0, false))
        assertTrue(schedule.shouldProbe(5_100_000_000, false, 0, false))
    }

    @Test
    fun `zero and backwards clock readings do not bypass probe spacing`() {
        assertTrue(schedule.shouldProbe(0, false, 0, false))
        assertFalse(schedule.shouldProbe(0, false, 0, false))
        assertTrue(schedule.shouldProbe(100_000_000, false, 0, false))
        assertFalse(schedule.shouldProbe(50_000_000, false, 0, false))
        assertFalse(schedule.shouldProbe(149_999_999, false, 0, false))
        assertTrue(schedule.shouldProbe(150_000_000, false, 0, false))
    }

    @Test
    fun `parent reconnect resets the bounded warmup schedule`() {
        repeat(7) { index -> assertTrue(schedule.shouldProbe(index * 100_000_000L, false, 0, false)) }
        assertFalse(schedule.shouldProbe(700_000_000, false, 0, false))
        schedule.reset()
        assertTrue(schedule.shouldProbe(700_000_000, false, 0, false))
        assertFalse(schedule.shouldProbe(799_999_999, false, 0, false))
        assertTrue(schedule.shouldProbe(800_000_000, false, 0, false))
    }

    @Test
    fun `sample reserve survives four rejected high RTT replies`() {
        val link = ClockLink()
        var slowReplies = 0
        var warmed = false
        var lostReadiness = false
        for (millis in 0L..30_000L step 10) {
            link.step(millis) {
                if (millis >= 15_000 && slowReplies < 4) {
                    slowReplies++
                    250
                } else 20
            }
            val ready = link.clock.estimate(link.now)?.ready == true
            if (warmed && !ready) lostReadiness = true
            if (ready) warmed = true
        }
        assertTrue(warmed)
        assertEquals(4L, link.clock.rejectedReplies)
        assertEquals(0, link.timeouts)
        assertFalse("Four slow replies exhausted the clock sample reserve", lostReadiness)
    }

    @Test
    fun `an outage fails closed and reacquires three fresh replies without a retry pileup`() {
        val link = ClockLink()
        var firstFreshReplyAt: Long? = null
        var recoveredAt: Long? = null
        var sawUnready = false
        for (millis in 0L..30_000L step 10) {
            val accepted = link.clock.acceptedReplies
            link.step(millis) { if (millis in 15_000L..21_999L) null else 20 }
            val estimate = link.clock.estimate(link.now)
            if (millis in 20_100L..21_999L && estimate?.ready != true) sawUnready = true
            if (millis >= 22_000 && link.clock.acceptedReplies > accepted && firstFreshReplyAt == null) {
                firstFreshReplyAt = millis
                assertEquals(1, estimate!!.samples)
                assertFalse(estimate.ready)
            }
            if (firstFreshReplyAt != null && estimate?.ready == true) {
                recoveredAt = millis
                break
            }
        }
        assertTrue(sawUnready)
        assertTrue(link.timeouts > 0)
        assertTrue("Clock did not recover after replies resumed", recoveredAt != null)
        assertTrue("Fresh-sample recovery took over one second", recoveredAt!! - firstFreshReplyAt!! <= 1_000)
        assertEquals(0L, link.clock.rejectedReplies)
    }

    private class ClockLink {
        private class Pending(val sent: Long, val replyAt: Long?)

        val clock = RootClock()
        private val schedule = ClockProbeSchedule()
        private var pending: Pending? = null
        var now = 100_000_000_000L
            private set
        var timeouts = 0
            private set

        fun step(millis: Long, replyDelayMillis: () -> Long?) {
            now = 100_000_000_000L + millis * 1_000_000
            pending?.let { probe ->
                if (probe.replyAt != null && now >= probe.replyAt) {
                    val rootTime = probe.sent + (probe.replyAt - probe.sent) / 2 + 1_000_000_000
                    clock.add(probe.sent, rootTime, rootTime, now)
                    pending = null
                } else if (now - probe.sent > 1_000_000_000) {
                    timeouts++
                    pending = null
                }
            }
            val estimate = clock.estimate(now)
            if (schedule.shouldProbe(now, estimate?.ready == true, estimate?.samples ?: 0, pending != null)) {
                pending = Pending(now, replyDelayMillis()?.let { now + it * 1_000_000 })
            }
        }
    }
}
