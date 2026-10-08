package com.pocketdisco.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.WritableMap
import com.pocketdisco.spec.NativeAudioPlaybackSpec

@androidx.annotation.OptIn(UnstableApi::class)
class AudioPlaybackModule(context: ReactApplicationContext) :
    NativeAudioPlaybackSpec(context), LifecycleEventListener {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var invalidated = false
    private val controller = PlaybackController(AndroidPlaybackClock(handler) { !invalidated }) {
        Media3PlaybackEngine(context.applicationContext)
    }

    init {
        context.addLifecycleEventListener(this)
    }

    override fun getName() = NAME

    override fun getCapabilities(promise: Promise) = command(promise) {
        promise.resolve(Arguments.createMap().apply {
            putBoolean("canSchedule", true)
            putBoolean("canSeek", true)
            putBoolean("canReportPosition", true)
            putBoolean("canRateAdjust", false)
            putBoolean("canBackground", false)
        })
    }

    override fun prepare(itemId: String, positionMs: Double, promise: Promise) = command(promise) {
        controller.prepare(itemId, positionMs) { result ->
            result.fold(
                { promise.resolve(it.toMap()) },
                { reject(promise, it) },
            )
        }
    }

    override fun playAt(monotonicTimeMs: Double, positionMs: Double, promise: Promise) =
        command(promise) { promise.resolve(controller.playAt(monotonicTimeMs, positionMs).toMap()) }

    override fun pause(promise: Promise) = command(promise) {
        promise.resolve(controller.pause().toMap())
    }

    override fun seek(positionMs: Double, promise: Promise) = command(promise) {
        promise.resolve(controller.seek(positionMs).toMap())
    }

    override fun getTimedState(promise: Promise) = command(promise) {
        promise.resolve(controller.snapshot().toMap())
    }

    override fun disconnect(promise: Promise) = command(promise) {
        controller.disconnect()
        promise.resolve(null)
    }

    override fun onHostResume() = onMainThread {
        if (!invalidated) controller.setForeground(true)
    }

    override fun onHostPause() = onMainThread { controller.setForeground(false) }

    override fun onHostDestroy() = onMainThread {
        controller.setForeground(false)
        controller.disconnect()
    }

    override fun invalidate() {
        invalidated = true
        reactApplicationContext.removeLifecycleEventListener(this)
        onMainThread {
            controller.setForeground(false)
            controller.disconnect()
        }
        super.invalidate()
    }

    private fun command(promise: Promise, action: () -> Unit) = onMainThread {
        try {
            if (invalidated) throw PlaybackController.failure("module_unavailable")
            action()
        } catch (error: Exception) {
            reject(promise, error)
        }
    }

    private fun onMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post(action)
    }

    private fun reject(promise: Promise, error: Throwable) {
        val safe = error as? PlaybackFailure ?: PlaybackController.failure("playback_failed")
        promise.reject(safe.code, safe.message)
    }

    private fun PlaybackSnapshot.toMap(): WritableMap = Arguments.createMap().apply {
        putString("itemId", itemId)
        putString("status", status)
        putDouble("positionMs", positionMs.toDouble())
        putDouble("durationMs", durationMs.toDouble())
        putDouble("sampledAtMonotonicMs", sampledAtMonotonicMs.toDouble())
        if (scheduledStartMonotonicMs == null) putNull("scheduledStartMonotonicMs")
        else putDouble("scheduledStartMonotonicMs", scheduledStartMonotonicMs.toDouble())
        putString("errorCode", errorCode)
    }

    companion object {
        const val NAME = "AudioPlayback"
    }
}
