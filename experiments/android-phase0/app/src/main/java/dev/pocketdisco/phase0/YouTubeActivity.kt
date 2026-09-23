package dev.pocketdisco.phase0

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.roundToLong

@SuppressLint("SetTextI18n")
class YouTubeActivity : Activity() {
    private lateinit var recorder: TelemetryRecorder
    private lateinit var webView: WebView
    private lateinit var deviceLabel: EditText
    private lateinit var trialId: EditText
    private lateinit var videoId: EditText
    private lateinit var playlistId: EditText
    private lateinit var seekSeconds: EditText
    private lateinit var coordinatorUrl: EditText
    private lateinit var coordinatorToken: EditText
    private lateinit var coordinatorTrialId: EditText
    private lateinit var status: TextView
    private lateinit var clockStatus: TextView
    private lateinit var telemetryStatus: TextView
    private lateinit var playButton: Button
    private lateinit var syncClockButton: Button
    private lateinit var createTrialButton: Button
    private lateinit var fetchTrialButton: Button
    private lateinit var startScheduler: MonotonicScheduler
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private var playerInitialized = false
    private var iframeReady = false
    private var screenReceiverRegistered = false
    private var activityResumed = false
    private var windowFocused = false
    private var playbackArmed = false
    private var clockEstimate: ClockEstimate? = null
    private var clockBaseUrl: String? = null
    private var pendingYouTubeTrial: YouTubeControlTrial? = null
    private var activeYouTubeTarget: CoordinationTarget? = null
    private var scheduledCommandSent = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val eventName = when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> "screen_off"
                Intent.ACTION_SCREEN_ON -> "screen_on"
                Intent.ACTION_USER_PRESENT -> "user_present"
                else -> "screen_unknown"
            }
            record(category = "screen", name = eventName)
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                cancelScheduledTrial("screen_off")
                pausePlayer("screen_off")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_youtube)
        bindViews()
        recorder = TelemetryRecorder(System::currentTimeMillis, SystemClock::elapsedRealtime)
        startScheduler = MonotonicScheduler(SystemClock::elapsedRealtime)
        configureWebView()
        wireControls()
        record(category = "lifecycle", name = "activity_created")
    }

    override fun onStart() {
        super.onStart()
        registerScreenReceiver()
        if (::recorder.isInitialized) record(category = "lifecycle", name = "activity_started")
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        if (::webView.isInitialized) webView.onResume()
        if (::recorder.isInitialized) record(category = "lifecycle", name = "activity_resumed")
    }

    override fun onPause() {
        activityResumed = false
        cancelScheduledTrial("activity_paused")
        if (::recorder.isInitialized) record(category = "lifecycle", name = "activity_paused")
        pausePlayer("activity_paused")
        if (::webView.isInitialized) webView.onPause()
        super.onPause()
    }

    override fun onStop() {
        if (::recorder.isInitialized) record(category = "lifecycle", name = "activity_stopped")
        unregisterScreenReceiver()
        super.onStop()
    }

    override fun onUserLeaveHint() {
        if (::recorder.isInitialized) record(category = "lifecycle", name = "user_leave_hint")
        pausePlayer("user_leave_hint")
        super.onUserLeaveHint()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        windowFocused = hasFocus
        if (!hasFocus) cancelScheduledTrial("window_focus_lost")
        if (::recorder.isInitialized) {
            record(category = "lifecycle", name = "window_focus", detail = "has_focus=$hasFocus")
        }
    }

    override fun onDestroy() {
        unregisterScreenReceiver()
        if (::startScheduler.isInitialized) startScheduler.cancel()
        networkExecutor.shutdownNow()
        if (::recorder.isInitialized) record(category = "lifecycle", name = "activity_destroyed")
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface(BRIDGE_NAME)
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.removeAllViews()
            webView.destroy()
        }
        super.onDestroy()
    }

    @Deprecated("Activity result API keeps this throwaway probe dependency-light")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != EXPORT_REQUEST || resultCode != RESULT_OK) return
        val destination = data?.data ?: return
        try {
            record(category = "telemetry", name = "export_completed")
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
        webView = findViewById(R.id.youtube_webview)
        deviceLabel = findViewById(R.id.device_label)
        trialId = findViewById(R.id.trial_id)
        videoId = findViewById(R.id.video_id)
        playlistId = findViewById(R.id.playlist_id)
        seekSeconds = findViewById(R.id.seek_seconds)
        coordinatorUrl = findViewById(R.id.coordinator_url)
        coordinatorToken = findViewById(R.id.coordinator_token)
        coordinatorTrialId = findViewById(R.id.coordinator_trial_id)
        status = findViewById(R.id.youtube_status)
        clockStatus = findViewById(R.id.clock_status)
        telemetryStatus = findViewById(R.id.telemetry_status)
        playButton = findViewById(R.id.play_youtube)
        syncClockButton = findViewById(R.id.sync_coordinator_clock)
        createTrialButton = findViewById(R.id.create_youtube_trial)
        fetchTrialButton = findViewById(R.id.fetch_youtube_trial)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.setBackgroundColor(Color.BLACK)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            safeBrowsingEnabled = true
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.addJavascriptInterface(YouTubeBridge(), BRIDGE_NAME)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val target = request?.url ?: return true
                if (request.isForMainFrame) {
                    record(
                        category = "youtube",
                        name = "top_level_navigation_requested",
                        detail = "scheme=${target.scheme};host=${target.host.orEmpty()}",
                    )
                    openExternal(target)
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                record(category = "youtube", name = "page_finished")
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    record(
                        category = "youtube",
                        name = "page_error",
                        detail = "code=${error?.errorCode}",
                    )
                }
            }
        }
    }

    private fun wireControls() {
        findViewById<Button>(R.id.initialize_youtube).setOnClickListener {
            runInputAction {
                val applicationId = packageName
                val origin = ProbeInput.youtubeAppOrigin(applicationId)
                playerInitialized = true
                iframeReady = false
                playbackArmed = false
                cancelScheduledTrial("player_reinitialized")
                pendingYouTubeTrial = null
                playButton.isEnabled = false
                status.text = "Loading official YouTube IFrame player"
                record(
                    category = "youtube",
                    name = "player_initializing",
                    detail = "app_id=$applicationId",
                )
                webView.loadDataWithBaseURL(
                    "$origin/",
                    IFrameHtmlFactory.create(applicationId),
                    "text/html",
                    Charsets.UTF_8.name(),
                    null,
                )
            }
        }
        findViewById<Button>(R.id.cue_video).setOnClickListener {
            runInputAction {
                val id = ProbeInput.videoId(videoId.text.toString())
                playbackArmed = false
                cancelScheduledTrial("manual_video_cue")
                pendingYouTubeTrial = null
                evaluate("window.phase0.cueVideo(${JsonString.quote(id)}, 0);")
            }
        }
        findViewById<Button>(R.id.cue_playlist).setOnClickListener {
            runInputAction {
                val id = ProbeInput.playlistId(playlistId.text.toString())
                playbackArmed = false
                cancelScheduledTrial("manual_playlist_cue")
                pendingYouTubeTrial = null
                evaluate("window.phase0.cuePlaylist(${JsonString.quote(id)}, 0);")
            }
        }
        playButton.setOnClickListener {
            evaluate("window.phase0.play();")
        }
        findViewById<Button>(R.id.pause_youtube).setOnClickListener {
            pausePlayer("manual")
        }
        findViewById<Button>(R.id.seek_youtube).setOnClickListener {
            runInputAction {
                val seconds = seekSeconds.text.toString().trim().toDoubleOrNull()
                    ?: throw IllegalArgumentException("Enter a seek position in seconds")
                require(seconds >= 0.0 && seconds.isFinite()) { "Seek position must be a finite positive number" }
                evaluate("window.phase0.seek($seconds);")
            }
        }
        findViewById<Button>(R.id.mark_ad).setOnClickListener {
            record(category = "youtube", name = "ad_observed", detail = "manual_observation=true")
        }
        syncClockButton.setOnClickListener { runInputAction(::synchronizeCoordinatorClock) }
        createTrialButton.setOnClickListener { runInputAction(::createYouTubeTrial) }
        fetchTrialButton.setOnClickListener { runInputAction(::fetchYouTubeTrial) }
        findViewById<Button>(R.id.export_telemetry).setOnClickListener {
            launchExport()
        }
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
                            name = "youtube_coordinator_time_sample",
                            detail = "index=$index;rtt_ms=${sample.roundTripTimeMs};" +
                                "network_rtt_ms=${sample.networkRoundTripTimeMs};" +
                                "offset_ms=${sample.serverToElapsedOffsetMs}",
                        )
                    }
                    record(
                        category = "clock",
                        name = "youtube_coordinator_clock_estimated",
                        detail = "samples=${estimate.sampleCount};" +
                            "best_network_rtt_ms=${estimate.bestNetworkRoundTripTimeMs};" +
                            "uncertainty_ms=${estimate.uncertaintyMs};" +
                            "offset_ms=${estimate.serverToElapsedOffsetMs}",
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

    private fun createYouTubeTrial() {
        require(iframeReady) { "Initialize the YouTube player first" }
        val estimate = currentClockEstimate()
        val (baseUrl, token) = coordinatorCredentials()
        require(baseUrl == clockBaseUrl) { "Synchronize the clock again after changing the coordinator URL" }
        val (itemType, itemId) = selectedYouTubeItem()
        val request = YouTubeControlTrialRequest(
            itemType = itemType,
            itemId = itemId,
            requestedPositionMs = requestedPositionMs(),
            effectiveAtUnixMs = estimate.serverUnixForElapsedRealtime(SystemClock.elapsedRealtime()) +
                COORDINATOR_LEAD_TIME_MS,
        )
        val idempotencyKey = UUID.randomUUID().toString()
        createTrialButton.isEnabled = false
        networkExecutor.execute {
            try {
                val trial = CoordinatorClient(baseUrl, token).createYouTubeTrial(request, idempotencyKey)
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    createTrialButton.isEnabled = true
                    applyYouTubeTrial(trial)
                }
            } catch (error: Exception) {
                coordinatorFailure("Create YouTube trial", error) { createTrialButton.isEnabled = true }
            }
        }
    }

    private fun fetchYouTubeTrial() {
        require(iframeReady) { "Initialize the YouTube player first" }
        currentClockEstimate()
        val (baseUrl, token) = coordinatorCredentials()
        require(baseUrl == clockBaseUrl) { "Synchronize the clock again after changing the coordinator URL" }
        val requestedTrialId = coordinatorTrialId.text.toString().trim()
        fetchTrialButton.isEnabled = false
        networkExecutor.execute {
            try {
                val trial = CoordinatorClient(baseUrl, token).getYouTubeTrial(requestedTrialId)
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    fetchTrialButton.isEnabled = true
                    applyYouTubeTrial(trial)
                }
            } catch (error: Exception) {
                coordinatorFailure("Fetch YouTube trial", error) { fetchTrialButton.isEnabled = true }
            }
        }
    }

    private fun applyYouTubeTrial(trial: YouTubeControlTrial) {
        runInputAction {
            require(iframeReady) { "Initialize the YouTube player first" }
            require(deviceLabel.text.toString().isNotBlank()) { "Device label is required" }
            val estimate = currentClockEstimate()
            val targetElapsed = estimate.elapsedRealtimeForServerUnix(trial.effectiveAtUnixMs)
            require(targetElapsed - SystemClock.elapsedRealtime() >= MINIMUM_PREPARE_LEAD_MS) {
                "YouTube trial start is too close or has passed"
            }
            cancelScheduledTrial("trial_replaced")
            playbackArmed = false
            pendingYouTubeTrial = trial
            coordinatorTrialId.setText(trial.id)
            trialId.setText(trial.id)
            seekSeconds.setText((trial.requestedPositionMs / 1_000.0).toString())
            when (trial.itemType) {
                YouTubeItemType.VIDEO -> {
                    videoId.setText(trial.itemId)
                    playlistId.text.clear()
                }
                YouTubeItemType.PLAYLIST -> {
                    playlistId.setText(trial.itemId)
                    videoId.text.clear()
                }
            }
            playButton.isEnabled = false
            evaluate(trial.cueScript())
            record(
                category = "coordination",
                name = "youtube_trial_prepared",
                detail = "trial_id=${trial.id};item_type=${trial.itemType.wireValue};" +
                    "item_id=${trial.itemId};target_elapsed_ms=$targetElapsed;" +
                    "clock_uncertainty_ms=${estimate.uncertaintyMs}",
            )
            status.text = "Trial cued. Tap Ready to play in the WebView to arm it."
        }
    }

    private fun selectedYouTubeItem(): Pair<YouTubeItemType, String> {
        val video = videoId.text.toString().trim()
        val playlist = playlistId.text.toString().trim()
        require(video.isBlank() != playlist.isBlank()) { "Enter one YouTube video or playlist ID" }
        return if (video.isNotBlank()) {
            YouTubeItemType.VIDEO to ProbeInput.videoId(video)
        } else {
            YouTubeItemType.PLAYLIST to ProbeInput.playlistId(playlist)
        }
    }

    private fun requestedPositionMs(): Long {
        val seconds = seekSeconds.text.toString().trim().toDoubleOrNull()
            ?: throw IllegalArgumentException("Enter a seek position in seconds")
        require(seconds >= 0.0 && seconds.isFinite()) { "Seek position must be a finite positive number" }
        return (seconds * 1_000.0).roundToLong()
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
                name = "youtube_request_failed",
                detail = "operation=$label;error_type=${error.javaClass.simpleName}",
            )
            toast("$label failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun pausePlayer(reason: String) {
        if (!playerInitialized || !::webView.isInitialized) return
        evaluate(
            "window.phase0 && window.phase0.pause(${JsonString.quote(reason)});",
            requireReady = false,
        )
    }

    private fun evaluate(script: String, requireReady: Boolean = true) {
        if (!playerInitialized) {
            toast("Initialize the player first")
            return
        }
        if (requireReady && !iframeReady) {
            toast("Wait for the IFrame player to report ready")
            return
        }
        webView.evaluateJavascript(script, null)
    }

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(screenReceiver, filter)
        }
        screenReceiverRegistered = true
    }

    private fun unregisterScreenReceiver() {
        if (!screenReceiverRegistered) return
        unregisterReceiver(screenReceiver)
        screenReceiverRegistered = false
    }

    private fun openExternal(uri: Uri) {
        if (uri.scheme != "https" && uri.scheme != "http") return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            toast("No app can open this link")
        }
    }

    private fun onBridgeEvent(name: String, detail: String) {
        val safeName = name.take(100)
        record(category = "youtube", name = safeName, detail = detail.take(4_000))
        when (safeName) {
            "iframe_ready" -> {
                iframeReady = true
                status.text = "Player ready. Cue media, then tap Ready to play below the player."
            }
            "user_ready_gesture" -> {
                playbackArmed = false
                playButton.isEnabled = false
                status.text = "Priming playback with the direct WebView gesture"
            }
            "playback_armed" -> {
                playbackArmed = true
                if (pendingYouTubeTrial == null) {
                    playButton.isEnabled = true
                    status.text = "Playback armed. Native Play is available."
                } else {
                    playButton.isEnabled = false
                    runInputAction(::schedulePendingYouTubeTrial)
                }
            }
            "readiness_reset", "autoplay_blocked" -> {
                playbackArmed = false
                cancelScheduledTrial(safeName)
                playButton.isEnabled = false
                status.text = if (safeName == "autoplay_blocked") {
                    "Autoplay blocked. Tap Ready to play again."
                } else {
                    "Media cued. Tap Ready to play below the player."
                }
            }
            "player_error" -> {
                playbackArmed = false
                cancelScheduledTrial("player_error")
                status.text = "YouTube reported a playback error. See telemetry."
            }
            "player_state" -> recordScheduledPlaying(detail)
        }
    }

    private fun schedulePendingYouTubeTrial() {
        val trial = pendingYouTubeTrial ?: throw IllegalStateException("No YouTube trial is prepared")
        val estimate = currentClockEstimate()
        val plan = YouTubeStartPlanner.plan(
            trial = trial,
            clock = estimate,
            nowElapsedRealtimeMs = SystemClock.elapsedRealtime(),
            state = currentStartState(),
        )
        val target = CoordinationTarget(trial.effectiveAtUnixMs, plan.targetElapsedRealtimeMs)
        activeYouTubeTarget = target
        scheduledCommandSent = false
        record(
            category = "coordination",
            name = "youtube_trial_scheduled",
            targetWallTimeMs = target.wallTimeMs,
            targetElapsedRealtimeMs = target.elapsedRealtimeMs,
            detail = "trial_id=${trial.id};position_ms=${plan.requestedPositionMs}",
        )
        status.text = "Playback armed for trial ${trial.id.take(8)}"
        startScheduler.scheduleAt(plan.targetElapsedRealtimeMs) { actualElapsedRealtimeMs ->
            try {
                YouTubeStartPlanner.requireReady(trial, currentStartState())
                scheduledCommandSent = true
                evaluate("window.phase0.play();")
                record(
                    category = "coordination",
                    name = "youtube_play_command_sent",
                    targetWallTimeMs = target.wallTimeMs,
                    targetElapsedRealtimeMs = target.elapsedRealtimeMs,
                    detail = "trial_id=${trial.id};actual_elapsed_ms=$actualElapsedRealtimeMs;" +
                        "lateness_ms=${actualElapsedRealtimeMs - target.elapsedRealtimeMs}",
                )
                status.text = "Scheduled YouTube play command sent"
            } catch (error: IllegalArgumentException) {
                scheduledCommandSent = false
                activeYouTubeTarget = null
                record(
                    category = "coordination",
                    name = "youtube_trial_cancelled",
                    detail = "trial_id=${trial.id};reason=execution_guard",
                )
                status.text = error.message ?: "Scheduled YouTube start cancelled"
            }
        }
    }

    private fun currentStartState() = YouTubeStartState(
        iframeReady = iframeReady,
        playbackArmed = playbackArmed,
        activityResumed = activityResumed,
        windowFocused = windowFocused,
        preparedTrialId = pendingYouTubeTrial?.id,
    )

    private fun cancelScheduledTrial(reason: String) {
        if (::startScheduler.isInitialized) startScheduler.cancel()
        val trial = pendingYouTubeTrial
        if (activeYouTubeTarget != null && !scheduledCommandSent && ::recorder.isInitialized) {
            record(
                category = "coordination",
                name = "youtube_trial_cancelled",
                detail = "trial_id=${trial?.id.orEmpty()};reason=$reason",
            )
        }
        activeYouTubeTarget = null
        scheduledCommandSent = false
    }

    private fun recordScheduledPlaying(detail: String) {
        val target = activeYouTubeTarget ?: return
        if (!scheduledCommandSent) return
        val event = try {
            JSONObject(detail)
        } catch (_: Exception) {
            return
        }
        if (event.optInt("state", -1) != 1 || event.optBoolean("arming", false)) return
        val trial = pendingYouTubeTrial ?: return
        record(
            category = "coordination",
            name = "youtube_trial_playing",
            targetWallTimeMs = target.wallTimeMs,
            targetElapsedRealtimeMs = target.elapsedRealtimeMs,
            detail = "trial_id=${trial.id}",
        )
        activeYouTubeTarget = null
        scheduledCommandSent = false
        status.text = "YouTube trial playing"
    }

    private fun record(
        category: String,
        name: String,
        detail: String = "",
        targetWallTimeMs: Long? = null,
        targetElapsedRealtimeMs: Long? = null,
    ) {
        val event = recorder.record(
            deviceLabel = deviceLabel.text.toString(),
            trialId = trialId.text.toString(),
            category = category,
            name = name,
            targetWallTimeMs = targetWallTimeMs,
            targetElapsedRealtimeMs = targetElapsedRealtimeMs,
            detail = detail,
        )
        telemetryStatus.text = "${event.sequence}. ${event.category}/${event.name}\n${event.detail}"
    }

    private fun runInputAction(action: () -> Unit) {
        try {
            action()
        } catch (error: IllegalArgumentException) {
            toast(error.message ?: "Invalid input")
        } catch (error: IllegalStateException) {
            toast(error.message ?: "YouTube player is not ready")
        }
    }

    @Suppress("DEPRECATION")
    private fun launchExport() {
        val safeTrial = trialId.text.toString().trim().replace(Regex("[^A-Za-z0-9._-]"), "_").take(40)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/x-ndjson"
            putExtra(Intent.EXTRA_TITLE, "youtube-${safeTrial.ifBlank { "trial" }}-${System.currentTimeMillis()}.ndjson")
        }
        startActivityForResult(intent, EXPORT_REQUEST)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private inner class YouTubeBridge {
        @JavascriptInterface
        fun onEvent(name: String, detail: String) {
            runOnUiThread { onBridgeEvent(name, detail) }
        }
    }

    companion object {
        private const val BRIDGE_NAME = "PocketDiscoBridge"
        private const val EXPORT_REQUEST = 2001
        private const val COORDINATOR_LEAD_TIME_MS = 25_000L
        private const val MINIMUM_PREPARE_LEAD_MS = 500L
        private const val CLOCK_SAMPLE_COUNT = 7
    }
}
