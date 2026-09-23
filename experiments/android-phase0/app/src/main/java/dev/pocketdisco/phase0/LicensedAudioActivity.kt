package dev.pocketdisco.phase0

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import java.util.UUID
import java.util.concurrent.Executors

@SuppressLint("SetTextI18n")
class LicensedAudioActivity : Activity() {
    private lateinit var controller: LicensedAudioController
    private lateinit var recorder: TelemetryRecorder
    private lateinit var deviceLabel: EditText
    private lateinit var trialId: EditText
    private lateinit var assetId: EditText
    private lateinit var assetSha256: EditText
    private lateinit var assetUrl: EditText
    private lateinit var rightsConfirmed: CheckBox
    private lateinit var outputCategory: RadioGroup
    private lateinit var playbackPositionMs: EditText
    private lateinit var startWallTimeMs: EditText
    private lateinit var coordinatorUrl: EditText
    private lateinit var coordinatorToken: EditText
    private lateinit var coordinatorTrialId: EditText
    private lateinit var clockStatus: TextView
    private lateinit var playerStatus: TextView
    private lateinit var telemetryStatus: TextView
    private lateinit var syncClockButton: Button
    private lateinit var createTrialButton: Button
    private lateinit var fetchTrialButton: Button
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private var clockEstimate: ClockEstimate? = null
    private var clockBaseUrl: String? = null
    private var activeCoordinatorTarget: CoordinationTarget? = null

    private val sampleHandler = Handler(Looper.getMainLooper())
    private val samplePosition = object : Runnable {
        override fun run() {
            if (::controller.isInitialized) {
                val state = controller.timedState()
                if (state.playbackState != androidx.media3.common.Player.STATE_IDLE) {
                    record(
                        category = "licensed_audio",
                        name = "position_sample",
                        positionMs = state.positionMs,
                        targetWallTimeMs = state.targetWallTimeMs,
                        targetElapsedRealtimeMs = state.targetElapsedRealtimeMs,
                        detail = "buffered_ms=${state.bufferedPositionMs};duration_ms=${state.durationMs};" +
                            "state=${LicensedAudioController.playbackStateName(state.playbackState)};playing=${state.isPlaying}",
                    )
                    updatePlayerStatus(state)
                }
            }
            sampleHandler.postDelayed(this, POSITION_SAMPLE_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_licensed_audio)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        bindViews()
        recorder = TelemetryRecorder(System::currentTimeMillis, SystemClock::elapsedRealtime)
        controller = LicensedAudioController(this, SystemClock::elapsedRealtime, ::onPlaybackEvent)
        wireControls()
        record(category = "lifecycle", name = "activity_created")
        sampleHandler.post(samplePosition)
    }

    override fun onResume() {
        super.onResume()
        if (::recorder.isInitialized) record(category = "lifecycle", name = "activity_resumed")
    }

    override fun onPause() {
        if (::recorder.isInitialized) record(category = "lifecycle", name = "activity_paused")
        super.onPause()
    }

    override fun onDestroy() {
        sampleHandler.removeCallbacks(samplePosition)
        networkExecutor.shutdownNow()
        if (::recorder.isInitialized) record(category = "lifecycle", name = "activity_destroyed")
        if (::controller.isInitialized) controller.release()
        super.onDestroy()
    }

    @Deprecated("Activity result API keeps this throwaway probe dependency-light")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode !in setOf(RAW_EXPORT_REQUEST, SYNC_EXPORT_REQUEST) || resultCode != RESULT_OK) return
        val destination = data?.data ?: return
        try {
            record(category = "telemetry", name = "export_requested")
            val content = if (requestCode == RAW_EXPORT_REQUEST) {
                recorder.toNdjson()
            } else {
                SyncObservationExporter.toNdjson(recorder.snapshot()).also {
                    require(it.isNotBlank()) { "No coordinator playback-start observations are ready" }
                }
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
        deviceLabel = findViewById(R.id.device_label)
        trialId = findViewById(R.id.trial_id)
        outputCategory = findViewById(R.id.output_category)
        assetId = findViewById(R.id.asset_id)
        assetSha256 = findViewById(R.id.asset_sha256)
        assetUrl = findViewById(R.id.asset_url)
        rightsConfirmed = findViewById(R.id.rights_confirmed)
        playbackPositionMs = findViewById(R.id.playback_position_ms)
        startWallTimeMs = findViewById(R.id.start_wall_time_ms)
        coordinatorUrl = findViewById(R.id.coordinator_url)
        coordinatorToken = findViewById(R.id.coordinator_token)
        coordinatorTrialId = findViewById(R.id.coordinator_trial_id)
        clockStatus = findViewById(R.id.clock_status)
        playerStatus = findViewById(R.id.player_status)
        telemetryStatus = findViewById(R.id.telemetry_status)
        syncClockButton = findViewById(R.id.sync_coordinator_clock)
        createTrialButton = findViewById(R.id.create_coordinator_trial)
        fetchTrialButton = findViewById(R.id.fetch_coordinator_trial)
    }

    private fun wireControls() {
        findViewById<Button>(R.id.preload).setOnClickListener {
            runInputAction {
                require(rightsConfirmed.isChecked) { "Confirm the recording rights before loading" }
                val url = ProbeInput.licensedAssetUrl(assetUrl.text.toString())
                activeCoordinatorTarget = null
                controller.preload(url, assetId.text.toString().trim(), position())
            }
        }
        syncClockButton.setOnClickListener {
            runInputAction { synchronizeCoordinatorClock() }
        }
        createTrialButton.setOnClickListener {
            runInputAction { createCoordinatorTrial() }
        }
        fetchTrialButton.setOnClickListener {
            runInputAction { fetchCoordinatorTrial() }
        }
        findViewById<Button>(R.id.generate_target).setOnClickListener {
            val target = System.currentTimeMillis() + DEFAULT_LEAD_TIME_MS
            startWallTimeMs.setText(target.toString())
            record(
                category = "coordination",
                name = "common_target_generated",
                targetWallTimeMs = target,
                detail = "lead_ms=$DEFAULT_LEAD_TIME_MS",
            )
        }
        findViewById<Button>(R.id.copy_target).setOnClickListener {
            val target = startWallTimeMs.text.toString().trim()
            requireInput(target.isNotEmpty(), "Generate or enter a target first") {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("PocketDisco start time", target))
                record(category = "coordination", name = "common_target_copied", detail = target)
                toast("Common target copied")
            }
        }
        findViewById<Button>(R.id.schedule_play).setOnClickListener {
            runInputAction {
                val wallNow = System.currentTimeMillis()
                val elapsedNow = SystemClock.elapsedRealtime()
                val targetWall = startWallTimeMs.text.toString().trim().toLongOrNull()
                    ?: throw IllegalArgumentException("Enter a Unix start time in milliseconds")
                val target = CoordinationTarget.fromWallTime(targetWall, wallNow, elapsedNow)
                require(targetWall - wallNow >= MINIMUM_SCHEDULE_LEAD_MS) {
                    "Use a target at least $MINIMUM_SCHEDULE_LEAD_MS ms ahead"
                }
                record(
                    category = "coordination",
                    name = "manual_target_converted",
                    targetWallTimeMs = target.wallTimeMs,
                    targetElapsedRealtimeMs = target.elapsedRealtimeMs,
                    detail = "confidence=lower;wall_clock_mapping=true",
                )
                activeCoordinatorTarget = null
                controller.schedulePlay(target, position())
            }
        }
        findViewById<Button>(R.id.play_now).setOnClickListener {
            runInputAction { controller.playNow() }
        }
        findViewById<Button>(R.id.pause).setOnClickListener {
            controller.pause()
        }
        findViewById<Button>(R.id.seek).setOnClickListener {
            runInputAction { controller.seekTo(position()) }
        }
        findViewById<Button>(R.id.mark_audible_onset).setOnClickListener {
            val state = controller.timedState()
            record(
                category = "licensed_audio",
                name = "audible_onset_marked",
                positionMs = state.positionMs,
                targetWallTimeMs = state.targetWallTimeMs,
                targetElapsedRealtimeMs = state.targetElapsedRealtimeMs,
                detail = "manual_observation=true",
            )
        }
        findViewById<Button>(R.id.mark_start_failure).setOnClickListener {
            runInputAction {
                val target = activeCoordinatorTarget
                    ?: throw IllegalStateException("No coordinator start is active")
                record(
                    category = "licensed_audio",
                    name = "playback_start_failed",
                    targetWallTimeMs = target.wallTimeMs,
                    targetElapsedRealtimeMs = target.elapsedRealtimeMs,
                    detail = "operator_marked_failure",
                )
                controller.pause()
            }
        }
        findViewById<Button>(R.id.export_telemetry).setOnClickListener {
            launchExport(RAW_EXPORT_REQUEST, "licensed-detail")
        }
        findViewById<Button>(R.id.export_sync_observations).setOnClickListener {
            launchExport(SYNC_EXPORT_REQUEST, "licensed-sync")
        }
    }

    private fun onPlaybackEvent(event: PlaybackProbeEvent) {
        record(
            category = "licensed_audio",
            name = event.name,
            positionMs = event.positionMs,
            targetWallTimeMs = event.targetWallTimeMs,
            targetElapsedRealtimeMs = event.targetElapsedRealtimeMs,
            detail = event.detail,
            observedElapsedRealtimeMs = event.observedElapsedRealtimeMs,
        )
        updatePlayerStatus(controller.timedState())
    }

    private fun updatePlayerStatus(state: TimedPlayerState) {
        playerStatus.text = buildString {
            append("Player: ")
            append(LicensedAudioController.playbackStateName(state.playbackState))
            append(", position ")
            append(state.positionMs)
            append(" ms, playing ")
            append(state.isPlaying)
        }
    }

    private fun record(
        category: String,
        name: String,
        positionMs: Long? = null,
        targetWallTimeMs: Long? = null,
        targetElapsedRealtimeMs: Long? = null,
        detail: String = "",
        observedElapsedRealtimeMs: Long? = null,
    ) {
        val event = recorder.record(
            deviceLabel = deviceLabel.text.toString(),
            trialId = trialId.text.toString(),
            outputCategory = selectedOutputCategory(),
            category = category,
            name = name,
            playerPositionMs = positionMs,
            targetWallTimeMs = targetWallTimeMs,
            targetElapsedRealtimeMs = targetElapsedRealtimeMs,
            detail = detail,
            observedElapsedRealtimeMs = observedElapsedRealtimeMs,
        )
        telemetryStatus.text = "${event.sequence}. ${event.category}/${event.name}\n${event.detail}"
    }

    private fun position(): Long {
        val value = playbackPositionMs.text.toString().trim().toLongOrNull()
            ?: throw IllegalArgumentException("Enter a playback position in milliseconds")
        require(value >= 0) { "Playback position cannot be negative" }
        return value
    }

    private fun selectedOutputCategory(): String = when (outputCategory.checkedRadioButtonId) {
        R.id.output_built_in -> "built_in"
        R.id.output_wired -> "wired"
        R.id.output_bluetooth -> "bluetooth"
        else -> throw IllegalStateException("Select an output category")
    }

    private fun synchronizeCoordinatorClock() {
        val (baseUrl, token) = coordinatorCredentials()
        syncClockButton.isEnabled = false
        clockStatus.text = "Clock: sampling"
        networkExecutor.execute {
            try {
                val client = CoordinatorClient(baseUrl, token)
                val samples = buildList {
                    repeat(CLOCK_SAMPLE_COUNT) {
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
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    clockEstimate = estimate
                    clockBaseUrl = baseUrl
                    samples.forEachIndexed { index, sample ->
                        record(
                            category = "clock",
                            name = "coordinator_time_sample",
                            detail = "index=$index;rtt_ms=${sample.roundTripTimeMs};" +
                                "network_rtt_ms=${sample.networkRoundTripTimeMs};" +
                                "offset_ms=${sample.serverToElapsedOffsetMs}",
                        )
                    }
                    record(
                        category = "clock",
                        name = "coordinator_clock_estimated",
                        detail = "samples=${estimate.sampleCount};best_network_rtt_ms=${estimate.bestNetworkRoundTripTimeMs};" +
                            "uncertainty_ms=${estimate.uncertaintyMs};offset_ms=${estimate.serverToElapsedOffsetMs}",
                    )
                    clockStatus.text = "Clock: ${estimate.uncertaintyMs} ms uncertainty, " +
                        "best RTT ${estimate.bestNetworkRoundTripTimeMs} ms"
                    syncClockButton.isEnabled = true
                }
            } catch (error: Exception) {
                coordinatorFailure("Clock synchronization", error) {
                    syncClockButton.isEnabled = true
                    clockStatus.text = "Clock: synchronization failed"
                }
            }
        }
    }

    private fun createCoordinatorTrial() {
        require(controller.isReady) { "Preload the licensed asset and wait for ready" }
        val estimate = currentClockEstimate()
        val (baseUrl, token) = coordinatorCredentials()
        require(baseUrl == clockBaseUrl) { "Synchronize the clock again after changing the coordinator URL" }
        val request = CoordinatorTrialRequest(
            assetId = assetId.text.toString().trim().also { require(it.isNotEmpty()) { "Asset label is required" } },
            assetSha256 = ProbeInput.assetSha256(assetSha256.text.toString()),
            requestedPositionMs = position(),
            effectiveAtUnixMs = estimate.serverUnixForElapsedRealtime(SystemClock.elapsedRealtime()) +
                COORDINATOR_LEAD_TIME_MS,
        )
        val idempotencyKey = UUID.randomUUID().toString()
        createTrialButton.isEnabled = false
        networkExecutor.execute {
            try {
                val trial = CoordinatorClient(baseUrl, token).createTrial(request, idempotencyKey)
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    createTrialButton.isEnabled = true
                    applyCoordinatorTrial(trial)
                }
            } catch (error: Exception) {
                coordinatorFailure("Create trial", error) { createTrialButton.isEnabled = true }
            }
        }
    }

    private fun fetchCoordinatorTrial() {
        currentClockEstimate()
        val (baseUrl, token) = coordinatorCredentials()
        require(baseUrl == clockBaseUrl) { "Synchronize the clock again after changing the coordinator URL" }
        val requestedTrialId = coordinatorTrialId.text.toString().trim()
        fetchTrialButton.isEnabled = false
        networkExecutor.execute {
            try {
                val trial = CoordinatorClient(baseUrl, token).getTrial(requestedTrialId)
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    fetchTrialButton.isEnabled = true
                    applyCoordinatorTrial(trial)
                }
            } catch (error: Exception) {
                coordinatorFailure("Fetch trial", error) { fetchTrialButton.isEnabled = true }
            }
        }
    }

    private fun applyCoordinatorTrial(trial: CoordinatorTrial) {
        runInputAction {
            require(controller.isReady) { "Preload the licensed asset and wait for ready" }
            require(deviceLabel.text.toString().isNotBlank()) { "Device label is required" }
            require(assetId.text.toString().trim() == trial.assetId) { "Trial asset label does not match this phone" }
            require(ProbeInput.assetSha256(assetSha256.text.toString()) == trial.assetSha256) {
                "Trial asset hash does not match this phone"
            }
            val estimate = currentClockEstimate()
            val targetElapsed = estimate.elapsedRealtimeForServerUnix(trial.effectiveAtUnixMs)
            require(targetElapsed - SystemClock.elapsedRealtime() >= MINIMUM_SCHEDULE_LEAD_MS) {
                "Trial start is too close or has passed"
            }
            coordinatorTrialId.setText(trial.id)
            trialId.setText(trial.id)
            playbackPositionMs.setText(trial.requestedPositionMs.toString())
            startWallTimeMs.setText(trial.effectiveAtUnixMs.toString())
            val target = CoordinationTarget(trial.effectiveAtUnixMs, targetElapsed)
            activeCoordinatorTarget = target
            record(
                category = "coordination",
                name = "coordinator_trial_applied",
                targetWallTimeMs = target.wallTimeMs,
                targetElapsedRealtimeMs = target.elapsedRealtimeMs,
                detail = "trial_id=${trial.id};asset_id=${trial.assetId};clock_uncertainty_ms=${estimate.uncertaintyMs}",
            )
            controller.schedulePlay(target, trial.requestedPositionMs)
        }
    }

    private fun coordinatorCredentials(): Pair<String, String> {
        val baseUrl = ProbeInput.coordinatorBaseUrl(coordinatorUrl.text.toString())
        val token = coordinatorToken.text.toString()
        require(token.isNotBlank()) { "Coordinator bearer token is required" }
        return baseUrl to token
    }

    private fun currentClockEstimate(): ClockEstimate =
        clockEstimate ?: throw IllegalStateException("Take seven coordinator time samples first")

    private fun coordinatorFailure(label: String, error: Exception, cleanup: () -> Unit) {
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            cleanup()
            record(
                category = "coordinator",
                name = "request_failed",
                detail = "operation=$label;error_type=${error.javaClass.simpleName}",
            )
            toast("$label failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun runInputAction(action: () -> Unit) {
        try {
            action()
        } catch (error: IllegalArgumentException) {
            toast(error.message ?: "Invalid input")
        } catch (error: IllegalStateException) {
            toast(error.message ?: "Player is not ready")
        }
    }

    private fun requireInput(condition: Boolean, message: String, action: () -> Unit) {
        if (!condition) {
            toast(message)
            return
        }
        action()
    }

    @Suppress("DEPRECATION")
    private fun launchExport(requestCode: Int, prefix: String) {
        val safeTrial = trialId.text.toString().trim().replace(Regex("[^A-Za-z0-9._-]"), "_").take(40)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/x-ndjson"
            putExtra(Intent.EXTRA_TITLE, "$prefix-${safeTrial.ifBlank { "trial" }}-${System.currentTimeMillis()}.ndjson")
        }
        startActivityForResult(intent, requestCode)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val RAW_EXPORT_REQUEST = 1001
        private const val SYNC_EXPORT_REQUEST = 1002
        private const val DEFAULT_LEAD_TIME_MS = 30_000L
        private const val COORDINATOR_LEAD_TIME_MS = 25_000L
        private const val MINIMUM_SCHEDULE_LEAD_MS = 500L
        private const val POSITION_SAMPLE_INTERVAL_MS = 1_000L
        private const val CLOCK_SAMPLE_COUNT = 7
    }
}
