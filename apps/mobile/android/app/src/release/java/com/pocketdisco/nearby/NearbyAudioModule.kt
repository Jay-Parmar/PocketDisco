package com.pocketdisco.nearby

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.pocketdisco.spec.NativeNearbyAudioSpec

class NearbyAudioModule(context: ReactApplicationContext) : NativeNearbyAudioSpec(context) {
    override fun getName() = NAME

    override fun getAvailability() = Arguments.createMap().apply {
        putBoolean("enabled", false)
        putBoolean("supported", false)
    }

    override fun open(roomId: String, promise: Promise) {
        promise.reject("nearby_unavailable", "Nearby audio is not included in this build.")
    }

    override fun stop(promise: Promise) {
        promise.resolve(null)
    }

    companion object {
        const val NAME = "NearbyAudio"
    }
}
