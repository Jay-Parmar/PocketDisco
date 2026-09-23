package dev.pocketdisco.phase0

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter2
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.UUID

@SuppressLint("SetTextI18n")
class MultiOutputActivity : Activity() {
    private lateinit var capabilityProbe: AndroidOutputCapabilityProbe
    private lateinit var controller: MultiOutputController
    private lateinit var recorder: TelemetryRecorder
    private lateinit var audioManager: AudioManager
    private lateinit var capabilityStatus: TextView
    private lateinit var firstOutput: Spinner
    private lateinit var secondOutput: Spinner
    private lateinit var safeVolumeConfirmed: CheckBox
    private lateinit var probeStatus: TextView
    private lateinit var runDualButton: Button
    private var directTargets = emptyList<OutputDeviceDescriptor>()
    private val trialId = UUID.randomUUID().toString()
    private var deviceCallbackRegistered = false
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            refreshOutputs()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            refreshOutputs()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_multi_output)

        capabilityProbe = AndroidOutputCapabilityProbe(this)
        audioManager = getSystemService(AudioManager::class.java)
        recorder = TelemetryRecorder(System::currentTimeMillis, SystemClock::elapsedRealtime)
        controller = MultiOutputController(this, SystemClock::elapsedRealtime, ::onProbeEvent)
        bindViews()
        wireControls()
        refreshOutputs()
    }

    override fun onStart() {
        super.onStart()
        if (!deviceCallbackRegistered) {
            audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
            deviceCallbackRegistered = true
        }
    }

    override fun onStop() {
        controller.stop("activity_stopped")
        if (deviceCallbackRegistered) {
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
            deviceCallbackRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        controller.release()
        super.onDestroy()
    }

    @Deprecated("Activity result API keeps this throwaway probe dependency-light")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != EXPORT_REQUEST || resultCode != RESULT_OK) return
        val destination = data?.data ?: return
        try {
            record("export_requested")
            contentResolver.openOutputStream(destination)?.bufferedWriter(Charsets.UTF_8).use { writer ->
                requireNotNull(writer) { "Could not open export destination" }
                writer.write(recorder.toNdjson())
            }
            toast("Telemetry exported")
        } catch (error: Exception) {
            toast("Export failed: ${error.message}")
        }
    }

    private fun bindViews() {
        capabilityStatus = findViewById(R.id.output_capability_status)
        firstOutput = findViewById(R.id.first_output)
        secondOutput = findViewById(R.id.second_output)
        safeVolumeConfirmed = findViewById(R.id.safe_volume_confirmed)
        probeStatus = findViewById(R.id.multi_output_status)
        runDualButton = findViewById(R.id.run_dual_output)
    }

    private fun wireControls() {
        findViewById<Button>(R.id.refresh_outputs).setOnClickListener { refreshOutputs() }
        findViewById<Button>(R.id.open_output_switcher).setOnClickListener { openOutputSwitcher() }
        findViewById<Button>(R.id.run_system_output).setOnClickListener {
            runAction {
                requireSafeVolume()
                controller.scheduleSystemRoute(SystemClock.elapsedRealtime() + START_LEAD_MS)
            }
        }
        runDualButton.setOnClickListener {
            runAction {
                requireSafeVolume()
                val first = directTargets[firstOutput.selectedItemPosition]
                val second = directTargets[secondOutput.selectedItemPosition]
                controller.scheduleDual(
                    first.id,
                    second.id,
                    SystemClock.elapsedRealtime() + START_LEAD_MS,
                )
            }
        }
        findViewById<Button>(R.id.stop_multi_output).setOnClickListener {
            controller.stop()
            probeStatus.text = "Stopped"
        }
        findViewById<Button>(R.id.export_multi_output).setOnClickListener { launchExport() }
    }

    private fun refreshOutputs() {
        val previousFirst = directTargets.getOrNull(firstOutput.safeSelectedPosition())?.id
        val previousSecond = directTargets.getOrNull(secondOutput.safeSelectedPosition())?.id
        val snapshot = capabilityProbe.snapshot()
        directTargets = snapshot.outputs.filter { it.targetRole == OutputTargetRole.DIRECT }
        val labels = directTargets.map { output ->
            "${output.label} (${output.transport.name.lowercase()}, id ${output.id})"
        }.ifEmpty { listOf("No direct outputs") }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        firstOutput.adapter = adapter
        secondOutput.adapter = adapter
        restoreSelection(firstOutput, previousFirst, fallback = 0)
        restoreSelection(secondOutput, previousSecond, fallback = 1)
        runDualButton.isEnabled = directTargets.size >= 2
        capabilityStatus.text = buildString {
            append("Android ${snapshot.sdkInt}\n")
            append("LE Audio: ${snapshot.leAudio.name.lowercase()}\n")
            append("LE broadcast source: ${snapshot.leAudioBroadcastSource.name.lowercase()}\n")
            append("Outputs: ${snapshot.outputs.size}, direct targets: ${directTargets.size}")
        }
        record("capabilities_refreshed", MultiOutputTelemetry.capabilityDetail(snapshot))
    }

    private fun restoreSelection(spinner: Spinner, deviceId: Int?, fallback: Int) {
        if (directTargets.isEmpty()) return
        val index = directTargets.indexOfFirst { it.id == deviceId }.takeIf { it >= 0 }
            ?: fallback.coerceAtMost(directTargets.lastIndex)
        spinner.setSelection(index)
    }

    private fun openOutputSwitcher() {
        if (Build.VERSION.SDK_INT >= 34) {
            val shown = MediaRouter2.getInstance(this).showSystemOutputSwitcher()
            if (!shown) toast("System output switcher is unavailable")
        } else {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
    }

    private fun onProbeEvent(event: MultiOutputProbeEvent) {
        record(
            name = event.name,
            detail = MultiOutputTelemetry.eventDetail(event),
            observedElapsedRealtimeMs = event.elapsedRealtimeMs,
        )
        val status = event.status
        probeStatus.text = buildString {
            append(event.name.replace('_', ' '))
            append(" at ${event.elapsedRealtimeMs} ms")
            if (status != null) {
                append("\nMode: ${status.mode.name.lowercase()}")
                append("\nRoute: ${status.route.state.name.lowercase()}")
                status.tracks.forEach { track ->
                    append("\n${track.trackLabel}: requested ${track.requestedDeviceId ?: "system"}")
                    append(", actual ${track.actualDeviceIds.sorted()}")
                    append(", underruns ${track.underrunCount}")
                }
            }
            if (event.detail.isNotBlank()) append("\n${event.detail}")
        }
    }

    private fun requireSafeVolume() {
        require(safeVolumeConfirmed.isChecked) { "Confirm a safe listening volume first" }
    }

    private fun launchExport() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/x-ndjson"
            putExtra(Intent.EXTRA_TITLE, "pocketdisco-android-output-$trialId.jsonl")
        }
        startActivityForResult(intent, EXPORT_REQUEST)
    }

    private fun record(
        name: String,
        detail: String = "",
        observedElapsedRealtimeMs: Long? = null,
    ) {
        recorder.record(
            deviceLabel = "android-output-probe",
            trialId = trialId,
            outputCategory = "multi_output",
            category = "multi_output",
            name = name,
            detail = detail,
            observedElapsedRealtimeMs = observedElapsedRealtimeMs,
        )
    }

    private fun runAction(action: () -> Unit) {
        try {
            action()
        } catch (error: Exception) {
            probeStatus.text = "Error: ${error.message}"
            toast(error.message ?: "Output probe failed")
        }
    }

    private fun Spinner.safeSelectedPosition(): Int = selectedItemPosition.coerceAtLeast(0)

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val EXPORT_REQUEST = 1201
        private const val START_LEAD_MS = 5_000L
    }
}
