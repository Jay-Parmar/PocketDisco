package com.pocketdisco.capturelab

import com.pocketdisco.capturelab.protocol.AudioFrame
import com.pocketdisco.capturelab.protocol.PcmFormat
import com.pocketdisco.capturelab.protocol.TimedAudioFrame
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayoutPlannerTest {
    private var now = 1_000_000_000L
    private val session = UUID(1, 2)
    private val startAt = now + 1_500_000_000L

    private fun frame(sequence: Long, at: Long = startAt + sequence * STEP, uncertainty: Long = 1_000_000L) =
        TimedAudioFrame(AudioFrame(session, sequence, at - 1_500_000_000L, 1,
            ByteArray(PcmFormat.FRAME_BYTES) { 7 }), at, uncertainty)

    private fun planner(prefill: Int = 1, capacity: Int = 100) =
        PlayoutPlanner({ now }, capacity).also { it.start(prefill) }

    @Test
    fun waitsForPrefillThenStartsOnlyAtItsDeadline() {
        val planner = planner(prefill = 3)
        planner.offer(frame(0))
        planner.offer(frame(1))
        assertNull(planner.next())
        planner.offer(frame(2))
        val chunk = checkNotNull(planner.next())
        assertEquals(startAt, planner.startDeadline(chunk.epoch))
        assertFalse(planner.beginPlayback(chunk.epoch))
        now = startAt
        assertTrue(planner.beginPlayback(chunk.epoch))
        assertFalse(planner.beginPlayback(chunk.epoch))
        assertEquals(1L, planner.snapshot().scheduledStarts)
        assertEquals(0L, planner.snapshot().startErrorNanos)
        assertEquals("timestamp_wait", planner.snapshot().timingState)
    }

    @Test
    fun offerOwnsThePcmBytes() {
        val planner = planner()
        val source = frame(0)
        planner.offer(source)
        source.frame.pcm.fill(99)
        assertEquals(7.toByte(), checkNotNull(planner.next()).pcm[0])
    }

    @Test
    fun rejectsPastFarFutureMalformedAndOtherSessions() {
        val planner = planner()
        assertFalse(planner.offer(frame(0, now - 1)))
        assertFalse(planner.offer(frame(0, now + 3_000_000_001L)))
        assertFalse(planner.offer(frame(0).let { it.copy(frame = it.frame.copy(pcm = ByteArray(1))) }))
        assertTrue(planner.offer(frame(0)))
        assertFalse(planner.offer(frame(1).let { it.copy(frame = it.frame.copy(sessionId = UUID(3, 4))) }))
        assertFalse(planner.offer(frame(0)))
        assertEquals(1L, planner.snapshot().lateFrames)
        assertEquals(5L, planner.snapshot().droppedFrames)
    }

    @Test
    fun queueOverflowRebuffersInsteadOfPlayingBacklog() {
        val planner = planner(capacity = 3)
        repeat(4) { planner.offer(frame(it.toLong())) }
        assertEquals(1, planner.snapshot().queuedFrames)
        assertEquals(3L, planner.snapshot().droppedFrames)
        assertEquals(1L, planner.snapshot().resyncs)
        assertNotNull(planner.next())
        assertEquals(startAt + 3 * STEP, planner.snapshot().lastScheduledPlayNanos)
    }

    @Test
    fun lateScheduledStartIsCancelled() {
        val planner = planner()
        planner.offer(frame(0))
        val epoch = checkNotNull(planner.next()).epoch
        now = startAt + 21_000_000L
        assertFalse(planner.beginPlayback(epoch))
        assertEquals(0L, planner.snapshot().scheduledStarts)
        assertEquals(1L, planner.snapshot().lateFrames)
        assertNull(planner.startDeadline(epoch))
    }

    @Test
    fun removesFramesWithoutEnoughTimeToPrefill() {
        val planner = planner()
        planner.offer(frame(0, now + 50_000_000L))
        assertNull(planner.next())
        assertEquals(0, planner.snapshot().queuedFrames)
        assertEquals(1L, planner.snapshot().lateFrames)
    }

    @Test
    fun fillsTwoMissingFramesWithSilenceAtTheSameCadence() {
        val planner = planner()
        planner.offer(frame(0))
        planner.offer(frame(3))
        assertNotNull(planner.next())
        assertArrayEquals(ByteArray(PcmFormat.FRAME_BYTES), checkNotNull(planner.next()).pcm)
        assertArrayEquals(ByteArray(PcmFormat.FRAME_BYTES), checkNotNull(planner.next()).pcm)
        assertEquals(7.toByte(), checkNotNull(planner.next()).pcm[0])
        assertEquals(2L, planner.snapshot().insertedSilenceFrames)
    }

    @Test
    fun largeGapRequiresANewStart() {
        val planner = planner()
        planner.offer(frame(0))
        planner.offer(frame(4))
        val epoch = checkNotNull(planner.next()).epoch
        assertNull(planner.next())
        assertNotEquals(epoch, planner.epoch())
        assertEquals(1L, planner.snapshot().resyncs)
        assertEquals(0, planner.snapshot().queuedFrames)
    }

    @Test
    fun emptyInputCannotGenerateAnUnboundedSilenceStream() {
        val planner = planner()
        planner.offer(frame(0))
        val epoch = checkNotNull(planner.next()).epoch
        assertNull(planner.next())
        now = startAt
        assertTrue(planner.beginPlayback(epoch))
        assertNotNull(planner.next())
        assertNotNull(planner.next())
        assertNull(planner.next())
        assertEquals(2L, planner.snapshot().insertedSilenceFrames)
        assertEquals("rebuffering", planner.snapshot().timingState)
    }

    @Test
    fun deadlineJumpInvalidatesTheScheduledOutput() {
        val planner = planner()
        planner.offer(frame(0))
        val epoch = checkNotNull(planner.next()).epoch
        planner.offer(frame(1, startAt + STEP + 41_000_000L))
        assertNull(planner.startDeadline(epoch))
        assertEquals(1L, planner.snapshot().resyncs)
    }

    @Test
    fun queuedDeadlineJumpIsCheckedWhenConsumed() {
        val planner = planner(prefill = 2)
        planner.offer(frame(0))
        planner.offer(frame(1, startAt + STEP + 41_000_000L))
        assertNotNull(planner.next())
        assertNull(planner.next())
        assertEquals(1L, planner.snapshot().resyncs)
    }

    @Test
    fun smallClockSlewDoesNotChangeTheContinuousSampleTimeline() {
        val planner = planner()
        planner.offer(frame(0))
        val epoch = checkNotNull(planner.next()).epoch
        planner.offer(frame(1, startAt + STEP + 1_000_000L))
        assertNotNull(planner.next())
        assertEquals(epoch, planner.epoch())
        assertEquals(startAt, planner.snapshot().lastScheduledPlayNanos)
    }

    @Test
    fun clockLossCancelsPrefillAndRequiresFreshFrames() {
        val planner = planner()
        planner.offer(frame(0))
        val epoch = checkNotNull(planner.next()).epoch
        planner.invalidateTiming()
        now = startAt
        assertFalse(planner.beginPlayback(epoch))
        assertEquals("waiting_clock", planner.snapshot().timingState)
        assertNull(planner.snapshot().uncertaintyNanos)
        assertTrue(planner.offer(frame(10, now + 1_500_000_000L)))
        assertNotNull(planner.next())
        assertNotEquals(epoch, planner.epoch())
    }

    @Test
    fun uncertainClockCannotKeepOldAudioScheduled() {
        val planner = planner()
        planner.offer(frame(0))
        val epoch = checkNotNull(planner.next()).epoch
        assertFalse(planner.offer(frame(1, uncertainty = 100_000_001L)))
        assertNull(planner.startDeadline(epoch))
        assertEquals("waiting_clock", planner.snapshot().timingState)
    }

    @Test
    fun underrunRebufferDropsItsQueuedAudio() {
        val planner = planner()
        planner.offer(frame(0))
        planner.offer(frame(1))
        val epoch = checkNotNull(planner.next()).epoch
        planner.rebuffer()
        assertEquals(0, planner.snapshot().queuedFrames)
        assertFalse(planner.beginPlayback(epoch))
        assertEquals(1L, planner.snapshot().resyncs)
    }

    @Test
    fun stopAndRestartCannotStartAnOldDeadlineOrReuseOldAudio() {
        val planner = planner()
        planner.offer(frame(0))
        val old = checkNotNull(planner.next()).epoch
        planner.stop()
        now = startAt
        assertFalse(planner.beginPlayback(old))
        assertFalse(planner.offer(frame(1)))
        planner.start(1)
        assertNull(planner.next())
        assertEquals(0L, planner.snapshot().scheduledStarts)
        planner.offer(frame(20, now + 1_500_000_000L))
        assertNotEquals(old, checkNotNull(planner.next()).epoch)
        assertFalse(planner.beginPlayback(old))
    }

    @Test
    fun validAudioTimestampReportsThePresentationError() {
        val planner = planner()
        planner.offer(frame(0))
        val epoch = checkNotNull(planner.next()).epoch
        now = startAt
        planner.beginPlayback(epoch)
        now += 400_000_000L
        planner.timestamp(epoch, 4_000, startAt + 255_000_000L)
        assertEquals(5_000_000L, planner.snapshot().outputErrorNanos)
        assertEquals("playing", planner.snapshot().timingState)
    }

    @Test
    fun constantOutputDelayIsLearnedForTheNextScheduledStart() {
        val planner = planner()
        planner.offer(frame(0))
        var epoch = checkNotNull(planner.next()).epoch
        now = startAt
        planner.beginPlayback(epoch)
        now += 400_000_000L
        planner.timestamp(epoch, 4_000, startAt + 310_000_000L)
        assertEquals(60_000_000L, planner.snapshot().outputLeadNanos)
        assertEquals("rebuffering", planner.snapshot().timingState)
        val target = now + 1_500_000_000L
        planner.offer(frame(100, target))
        epoch = checkNotNull(planner.next()).epoch
        assertEquals(target - 60_000_000L, planner.startDeadline(epoch))
        now = target - 60_000_000L
        assertTrue(planner.beginPlayback(epoch))
        now = target + 400_000_000L
        planner.timestamp(epoch, 4_000, target + 250_000_000L)
        assertEquals(0L, planner.snapshot().outputErrorNanos)
        assertEquals("playing", planner.snapshot().timingState)
    }

    @Test
    fun timestampCalibrationHasABoundedRetryCount() {
        val planner = planner()
        repeat(4) { attempt ->
            val target = now + 1_500_000_000L
            planner.offer(frame(attempt * 100L, target))
            val epoch = checkNotNull(planner.next()).epoch
            now = checkNotNull(planner.startDeadline(epoch))
            assertTrue(planner.beginPlayback(epoch))
            now = target + 400_000_000L
            planner.timestamp(epoch, 4_000, target + 310_000_000L)
        }
        assertTrue(planner.failed())
        assertEquals("timing_unavailable", planner.snapshot().timingState)
        assertEquals(180_000_000L, planner.snapshot().outputLeadNanos)
        assertFalse(planner.offer(frame(500, now + 1_500_000_000L)))
    }

    @Test
    fun missingTimestampsFailOnceInsteadOfRebufferingForever() {
        val planner = planner()
        planner.offer(frame(0))
        val epoch = checkNotNull(planner.next()).epoch
        now = startAt
        planner.beginPlayback(epoch)
        now += 3_000_000_001L
        planner.checkTimestamp()
        assertTrue(planner.failed())
        assertEquals("timestamp_unavailable", planner.snapshot().timingState)
        val resyncs = planner.snapshot().resyncs
        planner.checkTimestamp()
        planner.stop()
        assertEquals(resyncs, planner.snapshot().resyncs)
        assertEquals("timestamp_unavailable", planner.snapshot().timingState)
    }

    @Test
    fun staleOrOldEpochTimestampsCannotCalibrateANewSession() {
        val planner = planner()
        planner.offer(frame(0))
        val epoch = checkNotNull(planner.next()).epoch
        now = startAt
        planner.beginPlayback(epoch)
        now += 1_000_000_000L
        planner.timestamp(epoch, 4_000, startAt + 250_000_000L)
        planner.timestamp(epoch - 1, 4_000, now)
        assertNull(planner.snapshot().outputErrorNanos)
        assertEquals(0L, planner.snapshot().outputLeadNanos)
    }

    companion object { private const val STEP = 20_000_000L }
}
