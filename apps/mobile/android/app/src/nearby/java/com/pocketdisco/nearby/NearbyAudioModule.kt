package com.pocketdisco.nearby

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.BaseActivityEventListener
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.common.LifecycleState
import com.pocketdisco.BuildConfig
import com.pocketdisco.capturelab.LabRuntime
import com.pocketdisco.capturelab.MainActivity
import com.pocketdisco.spec.NativeNearbyAudioSpec

class NearbyAudioModule(context: ReactApplicationContext) : NativeNearbyAudioSpec(context), LifecycleEventListener {
    private val main = Handler(Looper.getMainLooper())
    private val handoff = NearbyHandoff()
    @Volatile private var invalidated = false
    private val results = object : BaseActivityEventListener() {
        override fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
            if (requestCode == REQUEST_NEARBY) onMain { close() }
        }
    }

    init {
        context.addActivityEventListener(results)
        context.addLifecycleEventListener(this)
    }

    override fun getName() = NAME

    override fun getAvailability() = Arguments.createMap().apply {
        putBoolean("enabled", BuildConfig.NEARBY_AUDIO_ENABLED)
        putBoolean("supported", nearbyAudioSupported(BuildConfig.NEARBY_AUDIO_ENABLED, Build.VERSION.SDK_INT))
    }

    override fun open(roomId: String, promise: Promise) = onMain {
        try {
            check(!invalidated && nearbyAudioSupported(BuildConfig.NEARBY_AUDIO_ENABLED, Build.VERSION.SDK_INT)) {
                "Nearby audio needs an internal build and Android 10 or newer."
            }
            check(reactApplicationContext.lifecycleState == LifecycleState.RESUMED) { "Return to PocketDisco to open nearby audio." }
            val activity = checkNotNull(reactApplicationContext.currentActivity) { "Return to PocketDisco to open nearby audio." }
            handoff.open(roomId)
            try {
                @Suppress("DEPRECATION")
                activity.startActivityForResult(Intent(activity, MainActivity::class.java), REQUEST_NEARBY)
            } catch (error: Exception) {
                close()
                throw error
            }
            promise.resolve(null)
        } catch (_: Exception) {
            promise.reject("nearby_unavailable", "Could not open nearby audio. Return to the room and try again.")
        }
    }

    override fun stop(promise: Promise) = onMain {
        close()
        promise.resolve(null)
    }

    override fun onHostPause() = onMain { handoff.hostPaused() }

    override fun onHostResume() = onMain {
        if (handoff.hostReturned()) stopRuntime()
    }

    override fun onHostDestroy() = onMain { close() }

    override fun invalidate() {
        invalidated = true
        reactApplicationContext.removeActivityEventListener(results)
        reactApplicationContext.removeLifecycleEventListener(this)
        onMain { close() }
        super.invalidate()
    }

    private fun close() {
        if (handoff.close()) stopRuntime()
    }

    private fun stopRuntime() {
        if (Build.VERSION.SDK_INT >= 29) LabRuntime.stop(reactApplicationContext.applicationContext)
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }

    companion object {
        const val NAME = "NearbyAudio"
        private const val REQUEST_NEARBY = 4517
    }
}
