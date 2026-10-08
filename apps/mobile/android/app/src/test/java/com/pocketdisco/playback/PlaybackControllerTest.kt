package com.pocketdisco.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackControllerTest {
    private val clock = FakeClock()
    private val engines = mutableListOf<FakeEngine>()
    private val controller = PlaybackController(clock) {
        FakeEngine().also { engines.add(it) }
    }.apply { setForeground(true) }
    private val engine get() = engines.last()

    private fun prepareReady(positionMs: Double = 0.0): PlaybackSnapshot {
        var result: Result<PlaybackSnapshot>? = null
        controller.prepare("generated-pulse", positionMs) { result = it }
        engine.changeState(EngineState.READY)
        return result!!.getOrThrow()
    }

    @Test
    fun preparationWaitsForDecoderReadiness() {
        var result: Result<PlaybackSnapshot>? = null
        controller.prepare("generated-pulse", 1200.0) { result = it }
        assertNull(result)
        assertEquals("preparing", controller.snapshot().status)
        engine.durationMs = 24_023
        engine.changeState(EngineState.READY)
        val ready = result!!.getOrThrow()
        assertEquals("ready", ready.status)
        assertEquals(1200L, ready.positionMs)
        assertEquals(24_023L, ready.durationMs)
        assertEquals(clock.nowMs, ready.sampledAtMonotonicMs)
        assertFalse(engine.isPlaying)
    }

    @Test
    fun preparationTimesOutAndReleasesTheDecoder() {
        var result: Result<PlaybackSnapshot>? = null
        controller.prepare("generated-pulse", 0.0) { result = it }
        clock.advance(10_000)
        assertEquals("prepare_timeout", (result!!.exceptionOrNull() as PlaybackFailure).code)
        assertEquals("error", controller.snapshot().status)
        assertTrue(engine.released)
        assertEquals(0, engine.playCalls)
    }

    @Test
    fun onlyBundledItemAndFinitePositionsAreAccepted() {
        for (item in listOf("", "https://example.com/audio.m4a", "file:///data/local/tmp/song")) {
            assertCode("unsupported_item") { controller.prepare(item, 0.0) {} }
        }
        for (position in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, 24_001.0)) {
            assertCode("invalid_position") { controller.prepare("generated-pulse", position) {} }
        }
        assertTrue(engines.isEmpty())
    }

    @Test
    fun onlyPreparedForegroundPlaybackCanBeScheduled() {
        assertCode("not_ready") { controller.playAt(1000.0, 0.0) }
        prepareReady()
        controller.setForeground(false)
        assertCode("not_foreground") { controller.playAt(1000.0, 0.0) }
        assertCode("not_foreground") { controller.prepare("generated-pulse", 0.0) {} }
        assertCode("not_foreground") { controller.seek(1000.0) }
    }

    @Test
    fun deadlinesMustBeFiniteAndWithinTenSeconds() {
        prepareReady()
        for (deadline in listOf(999.0, 11_001.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertCode("invalid_deadline") { controller.playAt(deadline, 0.0) }
        }
        assertEquals("scheduled", controller.playAt(11_000.0, 0.0).status)
    }

    @Test
    fun startUsesTheNativeDeadlineAndActualPlayingState() {
        prepareReady()
        val scheduled = controller.playAt(3000.0, 2500.0)
        assertEquals("scheduled", scheduled.status)
        assertEquals(3000L, scheduled.scheduledStartMonotonicMs)
        assertEquals(2500L, engine.positionMs)
        clock.advance(1999)
        assertEquals(0, engine.playCalls)
        clock.advance(1)
        assertEquals(1, engine.playCalls)
        assertEquals("ready", controller.snapshot().status)
        engine.isPlaying = true
        assertEquals("playing", controller.snapshot().status)
        assertNull(controller.snapshot().scheduledStartMonotonicMs)
    }

    @Test
    fun aLateCallbackDoesNotStartAudio() {
        prepareReady()
        controller.playAt(3000.0, 0.0)
        clock.advance(2251)
        assertEquals(0, engine.playCalls)
        assertEquals("missed_deadline", controller.snapshot().errorCode)
        assertEquals("error", controller.snapshot().status)
    }

    @Test
    fun bufferingAtTheDeadlineDoesNotStartLater() {
        prepareReady()
        controller.playAt(3000.0, 1000.0)
        engine.changeState(EngineState.BUFFERING)
        clock.advance(2000)
        engine.changeState(EngineState.READY)
        assertEquals(0, engine.playCalls)
        assertEquals("not_ready", controller.snapshot().errorCode)
    }

    @Test
    fun pauseAndSeekCancelQueuedStarts() {
        prepareReady()
        controller.playAt(3000.0, 0.0)
        assertEquals("paused", controller.pause().status)
        clock.advance(2000)
        assertEquals(0, engine.playCalls)
        controller.playAt(5000.0, 0.0)
        assertEquals("paused", controller.seek(4000.0).status)
        clock.advance(2000)
        assertEquals(0, engine.playCalls)
        assertEquals(4000L, engine.positionMs)
    }

    @Test
    fun aNewScheduleInvalidatesAnAlreadyDequeuedCallback() {
        prepareReady()
        controller.playAt(3000.0, 0.0)
        val stale = clock.tasks.last().action
        controller.playAt(4000.0, 500.0)
        clock.nowMs = 3000
        stale()
        assertEquals(0, engine.playCalls)
        clock.advance(1000)
        assertEquals(1, engine.playCalls)
    }

    @Test
    fun backgroundingPausesAndNeverResumesAutomatically() {
        prepareReady()
        controller.playAt(3000.0, 0.0)
        controller.setForeground(false)
        clock.advance(2000)
        controller.setForeground(true)
        assertEquals(0, engine.playCalls)
        assertEquals("paused", controller.snapshot().status)
        engine.isPlaying = true
        controller.setForeground(false)
        assertFalse(engine.isPlaying)
    }

    @Test
    fun backgroundingSettlesPendingPreparationExactlyOnce() {
        val results = mutableListOf<Result<PlaybackSnapshot>>()
        controller.prepare("generated-pulse", 0.0) { results.add(it) }
        controller.setForeground(false)
        engine.changeState(EngineState.READY)
        clock.advance(10_000)
        assertEquals(1, results.size)
        assertEquals("cancelled", (results.single().exceptionOrNull() as PlaybackFailure).code)
        assertEquals(0, engine.playCalls)
    }

    @Test
    fun anotherPreparationReleasesOldPlayerAndIgnoresOldCallbacks() {
        var cancelled: Result<PlaybackSnapshot>? = null
        controller.prepare("generated-pulse", 0.0) { cancelled = it }
        val oldEngine = engine
        val oldListener = oldEngine.listener!!
        var ready: Result<PlaybackSnapshot>? = null
        controller.prepare("generated-pulse", 600.0) { ready = it }
        oldListener.onChanged()
        oldListener.onError()
        assertTrue(oldEngine.released)
        assertEquals("cancelled", (cancelled!!.exceptionOrNull() as PlaybackFailure).code)
        assertNull(ready)
        engine.changeState(EngineState.READY)
        assertEquals(600L, ready!!.getOrThrow().positionMs)
    }

    @Test
    fun disconnectReleasesResourcesAndCanBeCalledTwice() {
        prepareReady()
        controller.playAt(3000.0, 0.0)
        val oldEngine = engine
        controller.disconnect()
        controller.disconnect()
        clock.advance(3000)
        assertTrue(oldEngine.released)
        assertEquals(0, oldEngine.playCalls)
        assertEquals("idle", controller.snapshot().status)
        assertNull(controller.snapshot().itemId)
        assertNotNull(prepareReady())
    }

    @Test
    fun interruptionsCancelStartsAndPausePlayback() {
        prepareReady()
        controller.playAt(3000.0, 0.0)
        engine.listener!!.onInterrupted()
        clock.advance(2000)
        assertEquals(0, engine.playCalls)
        assertEquals("paused", controller.snapshot().status)
    }

    @Test
    fun anInterruptionDuringSeekCannotQueueAnotherStart() {
        prepareReady()
        engine.onSeek = { engine.listener!!.onInterrupted() }
        val state = controller.playAt(3000.0, 400.0)
        assertEquals("paused", state.status)
        assertNull(state.scheduledStartMonotonicMs)
        clock.advance(2000)
        assertEquals(0, engine.playCalls)
    }

    @Test
    fun decoderFailureDuringSeekCannotLeaveAStartQueued() {
        prepareReady()
        val failed = engine
        engine.onSeek = { failed.listener!!.onError() }
        val state = controller.playAt(3000.0, 400.0)
        assertEquals("error", state.status)
        assertNull(state.scheduledStartMonotonicMs)
        clock.advance(2000)
        assertEquals(0, failed.playCalls)
    }

    @Test
    fun pauseAndSeekSettlePendingPreparationOnlyOnce() {
        for (cancel in listOf<() -> Unit>({ controller.pause() }, { controller.seek(900.0) })) {
            val results = mutableListOf<Result<PlaybackSnapshot>>()
            controller.prepare("generated-pulse", 0.0) { results.add(it) }
            cancel()
            engine.changeState(EngineState.READY)
            clock.advance(10_000)
            assertEquals(1, results.size)
            assertEquals("cancelled", (results.single().exceptionOrNull() as PlaybackFailure).code)
            assertEquals(0, engine.playCalls)
        }
    }

    @Test
    fun decoderErrorsAreSanitizedAndSettlePreparation() {
        var result: Result<PlaybackSnapshot>? = null
        controller.prepare("generated-pulse", 0.0) { result = it }
        engine.listener!!.onError()
        val error = result!!.exceptionOrNull() as PlaybackFailure
        assertEquals("playback_failed", error.code)
        assertEquals("Could not play the demo audio.", error.message)
        assertEquals("playback_failed", controller.snapshot().errorCode)
        assertTrue(engine.released)
    }

    @Test
    fun snapshotsReportNaturalEndWithoutInventingPlayback() {
        prepareReady()
        engine.positionMs = 24_000
        engine.changeState(EngineState.ENDED)
        val state = controller.snapshot()
        assertEquals("ended", state.status)
        assertEquals(24_000L, state.positionMs)
        assertEquals(0, engine.playCalls)
    }

    @Test
    fun invalidCommandsDoNotCancelAValidSchedule() {
        prepareReady()
        controller.playAt(3000.0, 0.0)
        assertCode("invalid_position") { controller.seek(Double.NaN) }
        assertCode("invalid_position") { controller.playAt(4000.0, -1.0) }
        assertCode("unsupported_item") { controller.prepare("not-a-demo", 0.0) {} }
        clock.advance(2000)
        assertEquals(1, engine.playCalls)
    }

    private fun assertCode(code: String, action: () -> Unit) {
        assertEquals(code, assertThrows(PlaybackFailure::class.java, action).code)
    }

    private class FakeClock : PlaybackClock {
        data class Task(val time: Long, val action: () -> Unit, var cancelled: Boolean = false)
        override var nowMs = 1000L
        val tasks = mutableListOf<Task>()

        override fun schedule(delayMs: Long, action: () -> Unit): PlaybackCancellation {
            val task = Task(nowMs + delayMs, action)
            tasks.add(task)
            return PlaybackCancellation { task.cancelled = true }
        }

        fun advance(ms: Long) {
            nowMs += ms
            val due = tasks.filter { !it.cancelled && it.time <= nowMs }
            tasks.removeAll(due.toSet())
            due.forEach { if (!it.cancelled) it.action() }
        }
    }

    private class FakeEngine : PlaybackEngine {
        override var state = EngineState.IDLE
        override var isPlaying = false
        override var positionMs = 0L
        override var durationMs: Long? = 24_000
        override var listener: PlaybackEngine.Listener? = null
        var playCalls = 0
        var released = false
        var onSeek: (() -> Unit)? = null

        override fun prepare(positionMs: Long) {
            this.positionMs = positionMs
            changeState(EngineState.BUFFERING)
        }

        override fun play() { playCalls += 1 }
        override fun pause() { isPlaying = false }
        override fun seek(positionMs: Long) {
            this.positionMs = positionMs
            onSeek?.invoke()
        }
        override fun release() { released = true; isPlaying = false }

        fun changeState(value: EngineState) {
            state = value
            listener?.onChanged()
        }
    }
}
