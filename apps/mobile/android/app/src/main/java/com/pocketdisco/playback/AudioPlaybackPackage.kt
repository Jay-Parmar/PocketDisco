package com.pocketdisco.playback

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider

class AudioPlaybackPackage : BaseReactPackage() {
    override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? =
        if (name == AudioPlaybackModule.NAME) AudioPlaybackModule(reactContext) else null

    override fun getReactModuleInfoProvider() = ReactModuleInfoProvider {
        mapOf(
            AudioPlaybackModule.NAME to ReactModuleInfo(
                AudioPlaybackModule.NAME,
                AudioPlaybackModule.NAME,
                false,
                false,
                false,
                true,
            ),
        )
    }
}
