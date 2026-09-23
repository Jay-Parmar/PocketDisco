package dev.pocketdisco.phase0

import android.bluetooth.BluetoothStatusCodes
import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class OutputCapabilitiesTest {
    @Test
    fun classifiesCommonOutputTypes() {
        assertEquals(
            OutputTransport.BUILT_IN,
            OutputTransport.fromAndroidType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
        )
        assertEquals(
            OutputTransport.WIRED,
            OutputTransport.fromAndroidType(AudioDeviceInfo.TYPE_WIRED_HEADPHONES),
        )
        assertEquals(
            OutputTransport.BLUETOOTH,
            OutputTransport.fromAndroidType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
        )
        assertEquals(
            OutputTransport.BLUETOOTH,
            OutputTransport.fromAndroidType(AudioDeviceInfo.TYPE_BLE_HEADSET),
        )
        assertEquals(
            OutputTransport.USB,
            OutputTransport.fromAndroidType(AudioDeviceInfo.TYPE_USB_HEADSET),
        )
    }

    @Test
    fun separatesDirectTargetsFromSystemGroups() {
        assertEquals(
            OutputTargetRole.DIRECT,
            OutputTargetRole.fromAndroidType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
        )
        assertEquals(
            OutputTargetRole.OBSERVE_ONLY,
            OutputTargetRole.fromAndroidType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO),
        )
        assertEquals(
            OutputTargetRole.SYSTEM_GROUP,
            OutputTargetRole.fromAndroidType(AudioDeviceInfo.TYPE_BLE_BROADCAST),
        )
        assertEquals(
            OutputTargetRole.OBSERVE_ONLY,
            OutputTargetRole.fromAndroidType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
        )
    }

    @Test
    fun mapsBluetoothFeatureResults() {
        assertEquals(
            FeatureSupport.SUPPORTED,
            FeatureSupport.fromPlatformResult(BluetoothStatusCodes.FEATURE_SUPPORTED),
        )
        assertEquals(
            FeatureSupport.NOT_SUPPORTED,
            FeatureSupport.fromPlatformResult(BluetoothStatusCodes.FEATURE_NOT_SUPPORTED),
        )
        assertEquals(
            FeatureSupport.NOT_CONFIGURED,
            FeatureSupport.fromPlatformResult(BluetoothStatusCodes.FEATURE_NOT_CONFIGURED),
        )
        assertEquals(
            FeatureSupport.BLUETOOTH_DISABLED,
            FeatureSupport.fromPlatformResult(BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED),
        )
        assertEquals(
            FeatureSupport.PLATFORM_ERROR,
            FeatureSupport.fromPlatformResult(BluetoothStatusCodes.ERROR_UNKNOWN),
        )
        assertEquals(FeatureSupport.UNKNOWN, FeatureSupport.fromPlatformResult(null))
        assertEquals(FeatureSupport.UNKNOWN, FeatureSupport.fromPlatformResult(99))
    }

    @Test
    fun preservesRawBluetoothFeatureResult() {
        val capability = FeatureCapability.fromPlatformResult(
            BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED,
        )

        assertEquals(FeatureSupport.BLUETOOTH_DISABLED, capability.support)
        assertEquals(BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED, capability.platformResult)
    }
}
