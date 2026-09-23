package dev.pocketdisco.phase0

import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

enum class OutputTransport {
    BUILT_IN,
    WIRED,
    BLUETOOTH,
    USB,
    HDMI,
    OTHER,
    ;

    companion object {
        fun fromAndroidType(type: Int): OutputTransport = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
            -> BUILT_IN

            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            -> WIRED

            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            -> BLUETOOTH

            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            -> USB

            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI_EARC,
            -> HDMI

            else -> OTHER
        }
    }
}

enum class OutputTargetRole {
    DIRECT,
    SYSTEM_GROUP,
    OBSERVE_ONLY,
    ;

    companion object {
        fun fromAndroidType(type: Int): OutputTargetRole = when (type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI_EARC,
            -> DIRECT

            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            -> SYSTEM_GROUP

            else -> OBSERVE_ONLY
        }
    }
}

enum class FeatureSupport {
    SUPPORTED,
    NOT_SUPPORTED,
    NOT_CONFIGURED,
    PERMISSION_REQUIRED,
    UNKNOWN,
    ;

    companion object {
        fun fromPlatformResult(result: Int?): FeatureSupport = when (result) {
            BluetoothStatusCodes.FEATURE_SUPPORTED -> SUPPORTED
            BluetoothStatusCodes.FEATURE_NOT_SUPPORTED -> NOT_SUPPORTED
            BluetoothStatusCodes.FEATURE_NOT_CONFIGURED -> NOT_CONFIGURED
            else -> UNKNOWN
        }
    }
}

data class OutputDeviceDescriptor(
    val id: Int,
    val label: String,
    val androidType: Int,
    val transport: OutputTransport,
    val targetRole: OutputTargetRole,
)

data class OutputCapabilitySnapshot(
    val sdkInt: Int,
    val leAudio: FeatureSupport,
    val leAudioBroadcastSource: FeatureSupport,
    val outputs: List<OutputDeviceDescriptor>,
)

class AndroidOutputCapabilityProbe(private val context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    fun snapshot(): OutputCapabilitySnapshot = OutputCapabilitySnapshot(
        sdkInt = Build.VERSION.SDK_INT,
        leAudio = leAudioSupport(),
        leAudioBroadcastSource = leAudioBroadcastSourceSupport(),
        outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .map(::describe)
            .sortedWith(compareBy(OutputDeviceDescriptor::transport, OutputDeviceDescriptor::label)),
    )

    private fun describe(device: AudioDeviceInfo): OutputDeviceDescriptor {
        val transport = OutputTransport.fromAndroidType(device.type)
        val label = device.productName.toString().trim().ifBlank {
            transport.name.lowercase().replaceFirstChar(Char::uppercase)
        }
        return OutputDeviceDescriptor(
            id = device.id,
            label = label,
            androidType = device.type,
            transport = transport,
            targetRole = OutputTargetRole.fromAndroidType(device.type),
        )
    }

    private fun leAudioSupport(): FeatureSupport {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return FeatureSupport.NOT_SUPPORTED
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return FeatureSupport.NOT_SUPPORTED
        return try {
            FeatureSupport.fromPlatformResult(adapter.isLeAudioSupported)
        } catch (_: SecurityException) {
            FeatureSupport.PERMISSION_REQUIRED
        }
    }

    private fun leAudioBroadcastSourceSupport(): FeatureSupport {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return FeatureSupport.NOT_SUPPORTED
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return FeatureSupport.NOT_SUPPORTED
        return try {
            FeatureSupport.fromPlatformResult(adapter.isLeAudioBroadcastSourceSupported)
        } catch (_: SecurityException) {
            FeatureSupport.PERMISSION_REQUIRED
        }
    }
}
