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

@SuppressLint("SetTextI18n")
class YouTubeActivity : Activity() {
    private lateinit var recorder: TelemetryRecorder
    private lateinit var webView: WebView
    private lateinit var deviceLabel: EditText
    private lateinit var trialId: EditText
    private lateinit var webOrigin: EditText
    private lateinit var videoId: EditText
    private lateinit var playlistId: EditText
    private lateinit var seekSeconds: EditText
    private lateinit var status: TextView
    private lateinit var telemetryStatus: TextView
    private lateinit var playButton: Button
    private var playerInitialized = false
    private var iframeReady = false
    private var screenReceiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val eventName = when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> "screen_off"
                Intent.ACTION_SCREEN_ON -> "screen_on"
                Intent.ACTION_USER_PRESENT -> "user_present"
                else -> "screen_unknown"
            }
            record(category = "screen", name = eventName)
            if (intent?.action == Intent.ACTION_SCREEN_OFF) pausePlayer("screen_off")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_youtube)
        bindViews()
        recorder = TelemetryRecorder(System::currentTimeMillis, SystemClock::elapsedRealtime)
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
        if (::webView.isInitialized) webView.onResume()
        if (::recorder.isInitialized) record(category = "lifecycle", name = "activity_resumed")
    }

    override fun onPause() {
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
        if (::recorder.isInitialized) {
            record(category = "lifecycle", name = "window_focus", detail = "has_focus=$hasFocus")
        }
    }

    override fun onDestroy() {
        unregisterScreenReceiver()
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
        webOrigin = findViewById(R.id.web_origin)
        videoId = findViewById(R.id.video_id)
        playlistId = findViewById(R.id.playlist_id)
        seekSeconds = findViewById(R.id.seek_seconds)
        status = findViewById(R.id.youtube_status)
        telemetryStatus = findViewById(R.id.telemetry_status)
        playButton = findViewById(R.id.play_youtube)
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
                val origin = ProbeInput.webOrigin(webOrigin.text.toString())
                playerInitialized = true
                iframeReady = false
                playButton.isEnabled = false
                status.text = "Loading official YouTube IFrame player"
                record(
                    category = "youtube",
                    name = "player_initializing",
                    detail = "origin_host=${Uri.parse(origin).host.orEmpty()}",
                )
                webView.loadDataWithBaseURL(
                    "$origin/",
                    IFrameHtmlFactory.create(origin),
                    "text/html",
                    Charsets.UTF_8.name(),
                    null,
                )
            }
        }
        findViewById<Button>(R.id.cue_video).setOnClickListener {
            runInputAction {
                val id = ProbeInput.videoId(videoId.text.toString())
                evaluate("window.phase0.cueVideo(${JsonString.quote(id)});")
            }
        }
        findViewById<Button>(R.id.cue_playlist).setOnClickListener {
            runInputAction {
                val id = ProbeInput.playlistId(playlistId.text.toString())
                evaluate("window.phase0.cuePlaylist(${JsonString.quote(id)});")
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
        findViewById<Button>(R.id.export_telemetry).setOnClickListener {
            launchExport()
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
                playButton.isEnabled = true
                status.text = "Readiness confirmed by direct WebView gesture"
            }
            "readiness_reset", "autoplay_blocked" -> {
                playButton.isEnabled = false
                status.text = if (safeName == "autoplay_blocked") {
                    "Autoplay blocked. Tap Ready to play again."
                } else {
                    "Media cued. Tap Ready to play below the player."
                }
            }
            "player_error" -> status.text = "YouTube reported a playback error. See telemetry."
        }
    }

    private fun record(category: String, name: String, detail: String = "") {
        val event = recorder.record(
            deviceLabel = deviceLabel.text.toString(),
            trialId = trialId.text.toString(),
            category = category,
            name = name,
            detail = detail,
        )
        telemetryStatus.text = "${event.sequence}. ${event.category}/${event.name}\n${event.detail}"
    }

    private fun runInputAction(action: () -> Unit) {
        try {
            action()
        } catch (error: IllegalArgumentException) {
            toast(error.message ?: "Invalid input")
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
    }
}
