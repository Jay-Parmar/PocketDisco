package dev.pocketdisco.phase0

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

enum class OutputTransport(val isFanoutCandidate: Boolean) {
    BUILT_IN(false),
    WIRED(true),
    BLUETOOTH(true),
    USB(true),
    HDMI(true),
    OTHER(false),
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

enum class FeatureSupport {
    SUPPORTED,
    NOT_SUPPORTED,
    PERMISSION_REQUIRED,
    UNKNOWN,
    ;

    companion object {
        fun fromPlatformResult(result: Int?): FeatureSupport = when (result) {
            1 -> SUPPORTED
            0 -> NOT_SUPPORTED
            else -> UNKNOWN
        }
    }
}

data class OutputDeviceDescriptor(
    val id: Int,
    val label: String,
    val androidType: Int,
    val transport: OutputTransport,
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
        leAudio = bluetoothFeature { it.isLeAudioSupported },
        leAudioBroadcastSource = bluetoothFeature { it.isLeAudioBroadcastSourceSupported },
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
        )
    }

    @SuppressLint("MissingPermission")
    private fun bluetoothFeature(read: (android.bluetooth.BluetoothAdapter) -> Int): FeatureSupport {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return FeatureSupport.NOT_SUPPORTED
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return FeatureSupport.PERMISSION_REQUIRED
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return FeatureSupport.NOT_SUPPORTED
        return try {
            FeatureSupport.fromPlatformResult(read(adapter))
        } catch (_: SecurityException) {
            FeatureSupport.PERMISSION_REQUIRED
        }
    }
}
