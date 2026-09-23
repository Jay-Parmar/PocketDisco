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
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future

@SuppressLint("SetTextI18n")
class MultiOutputActivity : Activity() {
    private lateinit var capabilityProbe: AndroidOutputCapabilityProbe
    private lateinit var controller: MultiOutputController
    private lateinit var recorder: TelemetryRecorder
    private lateinit var generatedSyncRecorder: GeneratedSyncTelemetryRecorder
    private lateinit var audioManager: AudioManager
    private lateinit var capabilityStatus: TextView
    private lateinit var firstOutput: Spinner
    private lateinit var secondOutput: Spinner
    private lateinit var safeVolumeConfirmed: CheckBox
    private lateinit var probeStatus: TextView
    private lateinit var runDualButton: Button
    private lateinit var coordinatorUrl: EditText
    private lateinit var coordinatorToken: EditText
    private lateinit var coordinatorTrialId: EditText
    private lateinit var coordinatorRoute: RadioGroup
    private lateinit var scenarioId: EditText
    private lateinit var clientId: EditText
    private lateinit var environment: Spinner
    private lateinit var outputCategory: Spinner
    private lateinit var clockStatus: TextView
    private lateinit var syncClockButton: Button
    private lateinit var createTrialButton: Button
    private lateinit var fetchTrialButton: Button
    private var directTargets = emptyList<OutputDeviceDescriptor>()
    private val localTrialId = UUID.randomUUID().toString()
    private var activeTrialId = localTrialId
    private var deviceCallbackRegistered = false
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private var coordinatorTask: Future<*>? = null
    @Volatile
    private var coordinatorGeneration = 0L
    private var clockEstimate: ClockEstimate? = null
    private var clockBaseUrl: String? = null
    private val environments = GeneratedSyncEnvironment.values().toList()
    private val outputCategories = GeneratedSyncOutputCategory.values().toList()
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
        generatedSyncRecorder = GeneratedSyncTelemetryRecorder()
        controller = MultiOutputController(this, SystemClock::elapsedRealtime, ::onProbeEvent)
        bindViews()
        configureTelemetrySelectors()
        setCoordinatorBusy(false)
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
        cancelCoordinatorTask()
        clockEstimate = null
        clockBaseUrl = null
        clockStatus.text = "Clock: sample required"
        setCoordinatorBusy(false)
        generatedSyncRecorder.clearActive()
        controller.stop("activity_stopped")
        setKeepScreenAwake(false)
        if (deviceCallbackRegistered) {
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
            deviceCallbackRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        cancelCoordinatorTask()
        networkExecutor.shutdownNow()
        controller.release()
        super.onDestroy()
    }

    @Deprecated("Activity result API keeps this throwaway probe dependency-light")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode !in setOf(RAW_EXPORT_REQUEST, SYNC_EXPORT_REQUEST) || resultCode != RESULT_OK) {
            return
        }
        val destination = data?.data ?: return
        try {
            val content = if (requestCode == RAW_EXPORT_REQUEST) {
                record("export_requested")
                recorder.toNdjson()
            } else {
                generatedSyncRecorder.toNdjson()
            }
            contentResolver.openOutputStream(destination)?.bufferedWriter(Charsets.UTF_8).use { writer ->
                requireNotNull(writer) { "Could not open export destination" }
                writer.write(content)
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
        coordinatorUrl = findViewById(R.id.multi_output_coordinator_url)
        coordinatorToken = findViewById(R.id.multi_output_coordinator_token)
        coordinatorTrialId = findViewById(R.id.multi_output_coordinator_trial_id)
        coordinatorRoute = findViewById(R.id.coordinator_output_route)
        scenarioId = findViewById(R.id.multi_output_scenario_id)
        clientId = findViewById(R.id.multi_output_client_id)
        environment = findViewById(R.id.multi_output_environment)
        outputCategory = findViewById(R.id.multi_output_category)
        clockStatus = findViewById(R.id.multi_output_clock_status)
        syncClockButton = findViewById(R.id.sync_multi_output_clock)
        createTrialButton = findViewById(R.id.create_generated_trial)
        fetchTrialButton = findViewById(R.id.fetch_generated_trial)
    }

    private fun configureTelemetrySelectors() {
        environment.adapter = enumAdapter(environments.map { it.wireValue })
        outputCategory.adapter = enumAdapter(outputCategories.map { it.wireValue })
    }

    private fun enumAdapter(values: List<String>) = ArrayAdapter(
        this,
        android.R.layout.simple_spinner_item,
        values.map { it.replace('_', ' ') },
    ).apply {
        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    }

    private fun wireControls() {
        findViewById<Button>(R.id.refresh_outputs).setOnClickListener { refreshOutputs() }
        findViewById<Button>(R.id.open_output_switcher).setOnClickListener { openOutputSwitcher() }
        findViewById<Button>(R.id.run_system_output).setOnClickListener {
            runAction {
                requireSafeVolume()
                cancelCoordinatorTask()
                generatedSyncRecorder.clearActive()
                activeTrialId = localTrialId
                controller.scheduleSystemRoute(SystemClock.elapsedRealtime() + START_LEAD_MS)
            }
        }
        runDualButton.setOnClickListener {
            runAction {
                requireSafeVolume()
                cancelCoordinatorTask()
                generatedSyncRecorder.clearActive()
                activeTrialId = localTrialId
                val first = directTargets[firstOutput.selectedItemPosition]
                val second = directTargets[secondOutput.selectedItemPosition]
                controller.scheduleDual(
                    first.id,
                    second.id,
                    SystemClock.elapsedRealtime() + START_LEAD_MS,
                )
            }
        }
        syncClockButton.setOnClickListener {
            runAction { synchronizeCoordinatorClock() }
        }
        createTrialButton.setOnClickListener {
            runAction { createCoordinatorTrial() }
        }
        fetchTrialButton.setOnClickListener {
            runAction { fetchCoordinatorTrial() }
        }
        findViewById<Button>(R.id.stop_multi_output).setOnClickListener {
            cancelCoordinatorTask()
            generatedSyncRecorder.clearActive()
            controller.stop()
            probeStatus.text = "Stopped"
        }
        findViewById<Button>(R.id.export_multi_output).setOnClickListener {
            launchExport(RAW_EXPORT_REQUEST, "android-output")
        }
        findViewById<Button>(R.id.export_generated_sync).setOnClickListener {
            runAction {
                require(generatedSyncRecorder.size() > 0) {
                    "Complete a coordinated generated signal trial first"
                }
                launchExport(SYNC_EXPORT_REQUEST, "android-generated-sync-v2")
            }
        }
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
            append("LE Audio: ${snapshot.leAudio.support.name.lowercase()}\n")
            append("LE broadcast source: ${snapshot.leAudioBroadcastSource.support.name.lowercase()}\n")
            append("Outputs: ${snapshot.outputs.size}, direct targets: ${directTargets.size}\n")
            append("Signal: ${ClickSignal.SIGNAL_ID}")
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
        keepScreenAwakeForEvent(event.name)?.let(::setKeepScreenAwake)
        generatedSyncRecorder.record(event)
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

    private fun synchronizeCoordinatorClock() {
        val (baseUrl, token) = coordinatorCredentials()
        cancelCoordinatorTask()
        clockEstimate = null
        clockBaseUrl = null
        clockStatus.text = "Clock: sampling"
        startCoordinatorTask { generation ->
            try {
                val client = CoordinatorClient(baseUrl, token)
                val samples = buildList {
                    repeat(CLOCK_SAMPLE_COUNT) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        val sentAt = SystemClock.elapsedRealtime()
                        val response = client.getTime()
                        val receivedAt = SystemClock.elapsedRealtime()
                        add(
                            ClockSample(
                                clientSendElapsedRealtimeMs = sentAt,
                                clientReceiveElapsedRealtimeMs = receivedAt,
                                serverReceiveUnixMs = response.serverReceiveUnixMs,
                                serverSendUnixMs = response.serverSendUnixMs,
                            ),
                        )
                    }
                }
                val estimate = ClockEstimator.estimate(samples)
                postCoordinatorResult(generation) {
                    clockEstimate = estimate
                    clockBaseUrl = baseUrl
                    samples.forEachIndexed { index, sample ->
                        record(
                            name = "coordinator_time_sample",
                            detail = "index=$index;rtt_ms=${sample.roundTripTimeMs};" +
                                "network_rtt_ms=${sample.networkRoundTripTimeMs};" +
                                "offset_ms=${sample.serverToElapsedOffsetMs}",
                        )
                    }
                    record(
                        name = "coordinator_clock_estimated",
                        detail = "samples=${estimate.sampleCount};" +
                            "best_network_rtt_ms=${estimate.bestNetworkRoundTripTimeMs};" +
                            "uncertainty_ms=${estimate.uncertaintyMs};" +
                            "offset_ms=${estimate.serverToElapsedOffsetMs}",
                    )
                    clockStatus.text = "Clock: ${estimate.uncertaintyMs} ms uncertainty, " +
                        "best RTT ${estimate.bestNetworkRoundTripTimeMs} ms"
                }
            } catch (error: Exception) {
                coordinatorFailure(generation, "clock_sync", error) {
                    clockEstimate = null
                    clockBaseUrl = null
                    clockStatus.text = "Clock: synchronization failed"
                }
            }
        }
    }

    private fun createCoordinatorTrial() {
        requireSafeVolume()
        val route = selectedCoordinatorRoute()
        val identity = selectedGeneratedIdentity(route)
        val estimate = currentClockEstimate()
        val (baseUrl, token) = coordinatorCredentials()
        require(baseUrl == clockBaseUrl) {
            "Synchronize the clock again after changing the coordinator URL"
        }
        cancelCoordinatorTask()
        generatedSyncRecorder.clearActive("replaced")
        prepareCoordinatorRoute(route)
        try {
            val request = CoordinatorTrialRequest(
                assetId = ClickSignal.SIGNAL_ID,
                assetSha256 = ClickSignal.PCM_SHA256,
                requestedPositionMs = 0,
                effectiveAtUnixMs = Math.addExact(
                    estimate.serverUnixForElapsedRealtime(SystemClock.elapsedRealtime()),
                    COORDINATOR_LEAD_TIME_MS,
                ),
            )
            val idempotencyKey = UUID.randomUUID().toString()
            startCoordinatorTask { generation ->
                try {
                    val trial = CoordinatorClient(baseUrl, token).createTrial(request, idempotencyKey)
                    postCoordinatorResult(generation) {
                        runAction { applyCoordinatorTrial(trial, identity) }
                    }
                } catch (error: Exception) {
                    coordinatorFailure(generation, "create_trial", error)
                }
            }
        } catch (error: Exception) {
            controller.cancelPrepared("request_failed")
            throw error
        }
    }

    private fun fetchCoordinatorTrial() {
        requireSafeVolume()
        val route = selectedCoordinatorRoute()
        val identity = selectedGeneratedIdentity(route)
        currentClockEstimate()
        val (baseUrl, token) = coordinatorCredentials()
        require(baseUrl == clockBaseUrl) {
            "Synchronize the clock again after changing the coordinator URL"
        }
        val requestedTrialId = coordinatorTrialId.text.toString().trim()
        cancelCoordinatorTask()
        generatedSyncRecorder.clearActive("replaced")
        prepareCoordinatorRoute(route)
        try {
            startCoordinatorTask { generation ->
                try {
                    val trial = CoordinatorClient(baseUrl, token).getTrial(requestedTrialId)
                    postCoordinatorResult(generation) {
                        runAction { applyCoordinatorTrial(trial, identity) }
                    }
                } catch (error: Exception) {
                    coordinatorFailure(generation, "fetch_trial", error)
                }
            }
        } catch (error: Exception) {
            controller.cancelPrepared("request_failed")
            throw error
        }
    }

    private fun applyCoordinatorTrial(
        trial: CoordinatorTrial,
        identity: GeneratedSyncIdentity,
    ) {
        val planned = try {
            requireSafeVolume()
            val estimate = currentClockEstimate()
            estimate to GeneratedSignalTrialPlanner.plan(
                trial,
                estimate,
                SystemClock.elapsedRealtime(),
            )
        } catch (error: Exception) {
            controller.cancelPrepared("trial_rejected")
            record(
                name = "coordinator_trial_rejected",
                detail = "trial_id=${trial.id};error_type=${error.javaClass.simpleName}",
            )
            throw error
        }
        val (estimate, plan) = planned

        try {
            coordinatorTrialId.setText(plan.trialId)
            activeTrialId = plan.trialId
            generatedSyncRecorder.begin(
                GeneratedSyncContext(
                    identity = identity,
                    trialId = plan.trialId,
                    targetTimestampMs = plan.target.wallTimeMs,
                    targetElapsedRealtimeMs = plan.target.elapsedRealtimeMs,
                    clockUncertaintyMs = estimate.uncertaintyMs,
                ),
            )
            record(
                name = "coordinator_trial_applied",
                detail = "trial_id=${plan.trialId};signal_id=${ClickSignal.SIGNAL_ID};" +
                    "target_server_ms=${plan.target.wallTimeMs};" +
                    "target_elapsed_realtime_ms=${plan.target.elapsedRealtimeMs};" +
                    "clock_uncertainty_ms=${estimate.uncertaintyMs}",
            )
            GeneratedSignalTrialPlanner.requireSufficientLead(
                targetElapsedRealtimeMs = plan.target.elapsedRealtimeMs,
                currentElapsedRealtimeMs = SystemClock.elapsedRealtime(),
            )
            controller.armPrepared(plan.target.elapsedRealtimeMs)
        } catch (error: Exception) {
            controller.cancelPrepared("arm_failed")
            generatedSyncRecorder.recordFailure("schedule_failed")
            generatedSyncRecorder.clearActive()
            record(
                name = "coordinator_schedule_failed",
                detail = "trial_id=${plan.trialId};error_type=${error.javaClass.simpleName}",
            )
            throw error
        }
        clockEstimate = null
        clockBaseUrl = null
        clockStatus.text = "Clock: sample again before the next trial"
    }

    private fun selectedCoordinatorRoute(): CoordinatedOutputRoute =
        when (coordinatorRoute.checkedRadioButtonId) {
            R.id.coordinator_system_route -> CoordinatedOutputRoute.System
            R.id.coordinator_dual_route -> {
                val first = directTargets.getOrNull(firstOutput.safeSelectedPosition())
                    ?: throw IllegalStateException("Select a first direct output")
                val second = directTargets.getOrNull(secondOutput.safeSelectedPosition())
                    ?: throw IllegalStateException("Select a second direct output")
                require(first.id != second.id) { "Select two different direct outputs" }
                CoordinatedOutputRoute.Dual(first.id, second.id)
            }

            else -> throw IllegalStateException("Select a coordinator output route")
        }

    private fun prepareCoordinatorRoute(route: CoordinatedOutputRoute) {
        when (route) {
            CoordinatedOutputRoute.System -> controller.prepareSystemRoute()
            is CoordinatedOutputRoute.Dual -> controller.prepareDual(
                route.firstDeviceId,
                route.secondDeviceId,
            )
        }
    }

    private fun selectedGeneratedIdentity(route: CoordinatedOutputRoute): GeneratedSyncIdentity =
        GeneratedSyncIdentity.fromInput(
            scenarioId = scenarioId.text.toString(),
            deviceId = clientId.text.toString(),
            environment = environments[environment.safeSelectedPosition()],
            outputCategory = outputCategories[outputCategory.safeSelectedPosition()],
            routeMode = when (route) {
                CoordinatedOutputRoute.System -> GeneratedSyncRouteMode.SYSTEM_GROUP
                is CoordinatedOutputRoute.Dual -> GeneratedSyncRouteMode.APP_FANOUT
            },
        )

    private fun coordinatorCredentials(): Pair<String, String> {
        val baseUrl = ProbeInput.coordinatorBaseUrl(coordinatorUrl.text.toString())
        val token = coordinatorToken.text.toString()
        require(token.isNotBlank()) { "Coordinator bearer token is required" }
        return baseUrl to token
    }

    private fun currentClockEstimate(): ClockEstimate =
        clockEstimate ?: throw IllegalStateException("Take seven coordinator time samples first")

    private fun startCoordinatorTask(action: (generation: Long) -> Unit) {
        check(coordinatorTask == null) { "A coordinator request is already running" }
        val generation = coordinatorGeneration
        setCoordinatorBusy(true)
        try {
            coordinatorTask = networkExecutor.submit { action(generation) }
        } catch (error: Exception) {
            controller.cancelPrepared("request_failed")
            setCoordinatorBusy(false)
            throw error
        }
    }

    private fun postCoordinatorResult(generation: Long, action: () -> Unit) {
        runOnUiThread {
            if (isDestroyed || generation != coordinatorGeneration) return@runOnUiThread
            coordinatorTask = null
            try {
                action()
            } finally {
                setCoordinatorBusy(false)
            }
        }
    }

    private fun coordinatorFailure(
        generation: Long,
        operation: String,
        error: Exception,
        cleanup: () -> Unit = {},
    ) {
        postCoordinatorResult(generation) {
            controller.cancelPrepared("request_failed")
            cleanup()
            record(
                name = "coordinator_request_failed",
                detail = "operation=$operation;error_type=${error.javaClass.simpleName}",
            )
            toast("Coordinator request failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun cancelCoordinatorTask() {
        coordinatorGeneration++
        coordinatorTask?.cancel(true)
        coordinatorTask = null
        controller.cancelPrepared("cancelled")
        if (::syncClockButton.isInitialized) setCoordinatorBusy(false)
    }

    private fun setCoordinatorBusy(busy: Boolean) {
        syncClockButton.isEnabled = !busy
        createTrialButton.isEnabled = !busy && clockEstimate != null
        fetchTrialButton.isEnabled = !busy && clockEstimate != null
        coordinatorUrl.isEnabled = !busy
        coordinatorToken.isEnabled = !busy
        coordinatorTrialId.isEnabled = !busy
        scenarioId.isEnabled = !busy
        clientId.isEnabled = !busy
        environment.isEnabled = !busy
        outputCategory.isEnabled = !busy
        safeVolumeConfirmed.isEnabled = !busy
    }

    private fun requireSafeVolume() {
        require(safeVolumeConfirmed.isChecked) { "Confirm a safe listening volume first" }
    }

    private fun setKeepScreenAwake(enabled: Boolean) {
        val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (enabled) window.addFlags(flag) else window.clearFlags(flag)
    }

    private fun launchExport(requestCode: Int, label: String) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/x-ndjson"
            putExtra(Intent.EXTRA_TITLE, "pocketdisco-$label-$activeTrialId.jsonl")
        }
        startActivityForResult(intent, requestCode)
    }

    private fun record(
        name: String,
        detail: String = "",
        observedElapsedRealtimeMs: Long? = null,
    ) {
        recorder.record(
            deviceLabel = "android-output-probe",
            trialId = activeTrialId,
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
        private const val RAW_EXPORT_REQUEST = 1201
        private const val SYNC_EXPORT_REQUEST = 1202
        private const val START_LEAD_MS = 5_000L
        private const val COORDINATOR_LEAD_TIME_MS = 25_000L
        private const val CLOCK_SAMPLE_COUNT = 7
    }
}

private sealed interface CoordinatedOutputRoute {
    data object System : CoordinatedOutputRoute

    data class Dual(
        val firstDeviceId: Int,
        val secondDeviceId: Int,
    ) : CoordinatedOutputRoute
}

internal fun keepScreenAwakeForEvent(eventName: String): Boolean? = when (eventName) {
    "playback_scheduled" -> true
    "playback_stopped" -> false
    else -> null
}
