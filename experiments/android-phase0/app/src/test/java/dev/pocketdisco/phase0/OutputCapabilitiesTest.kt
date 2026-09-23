package dev.pocketdisco.phase0

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun marksExternalOutputsAsFanoutCandidates() {
        assertFalse(OutputTransport.BUILT_IN.isFanoutCandidate)
        assertTrue(OutputTransport.WIRED.isFanoutCandidate)
        assertTrue(OutputTransport.BLUETOOTH.isFanoutCandidate)
        assertTrue(OutputTransport.USB.isFanoutCandidate)
        assertTrue(OutputTransport.HDMI.isFanoutCandidate)
        assertFalse(OutputTransport.OTHER.isFanoutCandidate)
    }

    @Test
    fun mapsBluetoothFeatureResults() {
        assertEquals(FeatureSupport.SUPPORTED, FeatureSupport.fromPlatformResult(1))
        assertEquals(FeatureSupport.NOT_SUPPORTED, FeatureSupport.fromPlatformResult(0))
        assertEquals(FeatureSupport.UNKNOWN, FeatureSupport.fromPlatformResult(null))
        assertEquals(FeatureSupport.UNKNOWN, FeatureSupport.fromPlatformResult(99))
    }
}
