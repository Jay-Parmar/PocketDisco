package com.pocketdisco.nearby

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider

class NearbyAudioPackage : BaseReactPackage() {
    override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? =
        if (name == NearbyAudioModule.NAME) NearbyAudioModule(reactContext) else null

    override fun getReactModuleInfoProvider() = ReactModuleInfoProvider {
        mapOf(NearbyAudioModule.NAME to ReactModuleInfo(
            NearbyAudioModule.NAME, NearbyAudioModule.NAME, false, false, false, true,
        ))
    }
}
