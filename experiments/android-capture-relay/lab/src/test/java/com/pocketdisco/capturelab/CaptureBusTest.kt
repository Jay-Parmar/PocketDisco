package com.pocketdisco.capturelab

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CaptureBusTest {
    @Before
    @After
    fun reset() {
        CaptureBus.onPcm = null
        CaptureBus.onStopped = null
        CaptureBus.end("Stopped")
    }

    @Test
    fun countsSignalFramesAndPreservesPeak() {
        val capture = CaptureBus.begin()
        CaptureBus.record(capture, frame(0))
        CaptureBus.record(capture, frame(32))
        CaptureBus.record(capture, frame(33))
        CaptureBus.record(capture, frame(-32768))
        CaptureBus.record(capture, frame(1))
        val snapshot = CaptureBus.snapshot()
        assertEquals(5L, snapshot.frames)
        assertEquals(2L, snapshot.nonSilentFrames)
        assertEquals(32768, snapshot.peak)
    }

    @Test
    fun newSessionResetsMetricsAndRejectsOldWorkers() {
        val first = CaptureBus.begin()
        CaptureBus.record(first, frame(100))
        CaptureBus.end("Stopped", first)
        val second = CaptureBus.begin()
        var delivered = 0
        CaptureBus.onPcm = { delivered += 1 }
        CaptureBus.record(first, frame(1000))
        CaptureBus.end("Old error", first)
        CaptureBus.ready(first)
        assertEquals(CaptureSnapshot(true, "Starting capture", 0, 0, 0), CaptureBus.snapshot())
        CaptureBus.record(second, frame(200))
        assertEquals(1, delivered)
        assertEquals(200, CaptureBus.snapshot().peak)
    }

    @Test
    fun stoppedSessionDoesNotDeliverFramesOrBecomeReady() {
        val capture = CaptureBus.begin()
        var delivered = 0
        CaptureBus.onPcm = { delivered += 1 }
        CaptureBus.end("Permission revoked", capture)
        CaptureBus.ready(capture)
        CaptureBus.record(capture, frame(1000))
        assertEquals(0, delivered)
        assertEquals(CaptureSnapshot(false, "Permission revoked", 0, 0, 0), CaptureBus.snapshot())
    }

    @Test
    fun stopCallbackRunsOnceAfterStateChangesWithoutHoldingTheLock() {
        val capture = CaptureBus.begin()
        val worker = Executors.newSingleThreadExecutor()
        var stopped = 0
        try {
            CaptureBus.onStopped = {
                val snapshot = worker.submit(Callable { CaptureBus.snapshot() }).get(2, TimeUnit.SECONDS)
                assertFalse(snapshot.running)
                stopped += 1
            }
            CaptureBus.end("Stopped", capture)
            CaptureBus.end("Stopped again", capture)
            assertEquals(1, stopped)
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    fun readySessionKeepsItsStatistics() {
        val capture = CaptureBus.begin()
        CaptureBus.record(capture, frame(-1000))
        CaptureBus.ready(capture)
        val snapshot = CaptureBus.snapshot()
        assertTrue(snapshot.running)
        assertEquals("Capturing selected app only", snapshot.status)
        assertEquals(1L, snapshot.nonSilentFrames)
    }

    @Test
    fun pendingStartCountsAsRunningAndFailureStopsIt() {
        val capture = CaptureBus.begin()
        var stopped = 0
        CaptureBus.onStopped = { stopped += 1 }
        assertTrue(CaptureBus.isRunning(capture))
        assertEquals("Starting capture", CaptureBus.snapshot().status)
        CaptureBus.end("Foreground service unavailable", capture)
        assertFalse(CaptureBus.isRunning(capture))
        assertEquals(1, stopped)
    }

    @Test(expected = IllegalStateException::class)
    fun duplicateStartCannotResetAnActiveSession() {
        CaptureBus.begin()
        CaptureBus.begin()
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAnIncompleteFrame() {
        CaptureBus.record(CaptureBus.begin(), ByteArray(639))
    }

    private fun frame(sample: Int): ByteArray = ByteArray(640).also {
        it[0] = sample.toByte()
        it[1] = (sample shr 8).toByte()
    }
}
