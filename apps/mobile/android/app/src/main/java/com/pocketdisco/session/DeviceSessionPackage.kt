package com.pocketdisco.session

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider

class DeviceSessionPackage : BaseReactPackage() {
    override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? =
        if (name == DeviceSessionModule.NAME) DeviceSessionModule(reactContext) else null

    override fun getReactModuleInfoProvider() = ReactModuleInfoProvider {
        mapOf(
            DeviceSessionModule.NAME to ReactModuleInfo(
                DeviceSessionModule.NAME,
                DeviceSessionModule.NAME,
                false,
                false,
                false,
                true,
            ),
        )
    }
}
