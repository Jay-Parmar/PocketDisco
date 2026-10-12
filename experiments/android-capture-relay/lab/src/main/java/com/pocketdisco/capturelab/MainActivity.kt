package com.pocketdisco.capturelab

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var code: EditText
    private lateinit var metrics: TextView
    private lateinit var summary: TextView
    private lateinit var message: TextView
    private var sourcePackage = SOURCE_PACKAGE
    private var pendingConsent = false
    private var bluetoothAction: (() -> Unit)? = null
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            LabRuntime.stop(context)
            refresh("Audio route disconnected. Session stopped.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sourcePackage = savedInstanceState?.getString("source") ?: SOURCE_PACKAGE
        pendingConsent = savedInstanceState?.getBoolean("consent") ?: false
        window.decorView.systemUiVisibility = 0
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
            setBackgroundColor(NIGHT)
        }
        val scroll = ScrollView(this).apply {
            addView(column)
            fitsSystemWindows = true
            isFillViewport = true
            setBackgroundColor(NIGHT)
        }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(scroll)
        label(column, "POCKETDISCO / CAPTURE LAB", 12f, LIME, true)
        label(column, "Pass the sound.", 34f, INK, true)
        label(column, "A small sound experiment. A few phones. One signal.", 16f)
        pulse(column)
        label(column, "INTERNAL TEST  /  NOT SYNCHRONIZED", 12f, CORAL, true)
        label(column, "Generated or authorized audio only. Receivers lag behind the source.", 14f)

        val sessionCard = panel(column)
        label(sessionCard, "01  /  SESSION", 12f, VIOLET, true)
        label(sessionCard, "Share your signal", 22f, INK, true)
        label(sessionCard, "Use the same code on every phone. Only share it with your test group.", 14f)
        val preferences = getPreferences(MODE_PRIVATE)
        code = EditText(this).apply {
            id = View.generateViewId()
            contentDescription = "Session code"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextColor(INK)
            setHintTextColor(MUTED)
            background = rounded(RAISED, 12)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            minHeight = dp(56)
            setText(preferences.getString("code", null) ?: LabRuntime.newCode())
            sessionCard.addView(this, spaced())
        }
        actionRow(sessionCard, "Copy session code", "New session code", {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("PocketDisco session", code.text.toString().trim()))
            refresh("Session code copied. Share it only with your test group.")
        }, {
            check(!LabRuntime.active) { "Stop the current session first" }
            code.setText(LabRuntime.newCode())
        })

        val linkCard = panel(column)
        label(linkCard, "02  /  HOST + RECEIVE", 12f, VIOLET, true)
        label(linkCard, "Make the connection", 22f, INK, true)
        label(linkCard, "USB / TCP", 12f, LIME, true)
        label(linkCard, "Phone to emulator through this PC. A software test, not a Bluetooth link.", 14f)
        actionRow(linkCard, "Host USB/TCP test", "Receive USB/TCP test", {
            configure().listenTcp(); refresh("Loopback host started. Connect the emulator next.")
        }, {
            val links = configure()
            LabRuntime.receive(this)
            links.connectEmulator()
            refresh("Connecting through the PC. Keep this receiver screen open.")
        })
        label(linkCard, "PAIRED BLUETOOTH", 12f, LIME, true)
        actionRow(linkCard, "Host paired Bluetooth", "Receive paired Bluetooth", {
            withBluetooth { configure().listenBluetooth(); refresh("Paired Bluetooth listener started.") }
        }, { withBluetooth { chooseBluetoothPeer() } })
        label(linkCard, "Pair in Android settings first. Up to 3 links and 3 hops. A receiver can host the next link. Physical hardware testing is pending.", 14f)

        val captureCard = panel(column)
        label(captureCard, "03  /  CAPTURE", 12f, VIOLET, true)
        label(captureCard, "Choose what plays", 22f, INK, true)
        val sources = RadioGroup(this).apply { captureCard.addView(this) }
        listOf("Generated source" to SOURCE_PACKAGE, "Music app local test file" to MUSIC_PACKAGE).forEach { (title, name) ->
            sources.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = title
                textSize = 15f
                minHeight = dp(52)
                setTextColor(INK)
                buttonTintList = ColorStateList.valueOf(LIME)
                isChecked = sourcePackage == name
                setOnCheckedChangeListener { _, checked -> if (checked) sourcePackage = name }
            })
        }
        action(captureCard, "Start capture", LIME, NIGHT) {
            check(!CaptureBus.snapshot().running) { "Capture is already running" }
            configure()
            LabRuntime.stopReceiver()
            requestCapture()
        }
        action(captureCard, "Open generated source") {
            val launch = packageManager.getLaunchIntentForPackage(SOURCE_PACKAGE)
                ?: error("Install the companion Test Source APK first")
            startActivity(launch)
        }
        label(captureCard, "Android asks for consent. Only the selected app's playback audio is read. No screen pixels or microphone audio. Blocked sources stay silent.", 14f)

        val checkCard = panel(column)
        label(checkCard, "04  /  SESSION CHECK", 12f, VIOLET, true)
        message = label(checkCard, "Ready", 18f, INK, true)
        summary = label(checkCard, "", 16f, LIME)
        label(checkCard, "Refresh for current counters. Output counters do not prove audible synchronization.", 13f)
        actionRow(checkCard, "Refresh metrics", "Stop session", { refresh() }, {
            LabRuntime.stop(this); refresh("Session stopped")
        }, rightColor = CORAL)
        val details = action(checkCard, "Show details") {}
        metrics = label(checkCard, "", 12f).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            visibility = View.GONE
        }
        details.setOnClickListener {
            val visible = metrics.visibility != View.VISIBLE
            metrics.visibility = if (visible) View.VISIBLE else View.GONE
            details.text = if (visible) "Hide details" else "Show details"
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        }
        refresh()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun spaced() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(8)
    }

    private fun panel(parent: LinearLayout) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(PANEL, 24)
        setPadding(dp(18), dp(18), dp(18), dp(18))
        parent.addView(this, spaced().apply { topMargin = dp(18) })
    }

    private fun label(parent: LinearLayout, title: String, size: Float = 16f, color: Int = MUTED, bold: Boolean = false) = TextView(this).apply {
        text = title
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(0, dp(5), 0, dp(7))
        parent.addView(this)
    }

    private fun action(parent: LinearLayout, title: String, color: Int = RAISED, ink: Int = INK, click: () -> Unit) = Button(this).apply {
        text = title
        textSize = 14f
        isAllCaps = false
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(ink)
        minHeight = dp(56)
        minimumHeight = dp(56)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        backgroundTintList = null
        background = RippleDrawable(ColorStateList.valueOf(0x336F768F), rounded(color, 14), null)
        setOnClickListener { perform(click) }
        parent.addView(this, spaced())
    }

    private fun actionRow(parent: LinearLayout, left: String, right: String, first: () -> Unit, second: () -> Unit, rightColor: Int = RAISED) {
        val horizontal = resources.configuration.screenWidthDp >= 360 && resources.configuration.fontScale <= 1.3f
        val row = LinearLayout(this).apply {
            orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            parent.addView(this)
        }
        val firstButton = action(row, left, VIOLET, NIGHT, first)
        val secondButton = action(row, right, rightColor, if (rightColor == CORAL) NIGHT else INK, second)
        if (horizontal) {
            firstButton.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                topMargin = dp(8)
                marginEnd = dp(4)
            }
            secondButton.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                topMargin = dp(8)
                marginStart = dp(4)
            }
        }
    }

    private fun pulse(parent: LinearLayout) {
        val strip = LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        listOf(12, 24, 42, 28, 54, 38, 18, 34, 48, 22, 12, 30, 44, 26, 14, 34, 20, 10).forEachIndexed { index, height ->
            strip.addView(View(this).apply { background = rounded(if (index < 9) LIME else VIOLET, 4) },
                LinearLayout.LayoutParams(0, dp(height), 1f).apply { marginEnd = dp(5) })
        }
        parent.addView(strip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(72)))
    }

    private fun configure(): LabLinks {
        val value = code.text.toString().trim()
        val links = LabRuntime.configure(this, value)
        getPreferences(MODE_PRIVATE).edit().putString("code", value).apply()
        return links
    }

    private fun requestCapture() {
        val permissions = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        pendingConsent = true
        if (permissions.isNotEmpty()) requestPermissions(permissions.toTypedArray(), CAPTURE_PERMISSIONS)
        else showCaptureConsent()
    }

    private fun showCaptureConsent() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else manager.createScreenCaptureIntent()
        @Suppress("DEPRECATION")
        startActivityForResult(intent, CAPTURE_CONSENT)
    }

    @Deprecated("Activity result API for the standalone native lab")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != CAPTURE_CONSENT) return
        pendingConsent = false
        if (resultCode == RESULT_OK && data != null) {
            perform {
                CaptureService.start(this, resultCode, data, sourcePackage)
                refresh("Capture starting. Open the generated source and play its test tone.")
            }
        } else refresh("Capture cancelled. No audio was captured.")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAPTURE_PERMISSIONS) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                showCaptureConsent()
            } else {
                pendingConsent = false
                refresh("Playback capture permission denied")
            }
        }
        if (requestCode == BLUETOOTH_PERMISSION) {
            val action = bluetoothAction
            bluetoothAction = null
            if (Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                if (action != null) perform(action)
            } else refresh("Bluetooth permission denied")
        }
    }

    private fun withBluetooth(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            bluetoothAction = action
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), BLUETOOTH_PERMISSION)
        } else action()
    }

    @SuppressLint("MissingPermission")
    private fun chooseBluetoothPeer() {
        val adapter = getSystemService(BluetoothManager::class.java).adapter ?: error("Bluetooth is unavailable")
        check(adapter.isEnabled) { "Enable Bluetooth in Android settings first" }
        val devices = adapter.bondedDevices.sortedBy { it.name ?: it.address }
        check(devices.isNotEmpty()) { "Pair the other phone in Android settings first" }
        AlertDialog.Builder(this)
            .setTitle("Choose the upstream phone")
            .setItems(devices.map { it.name ?: it.address }.toTypedArray()) { _, index ->
                perform {
                    val links = configure()
                    LabRuntime.receive(this)
                    links.connectBluetooth(devices[index].address)
                    refresh("Connecting paired Bluetooth. Keep the receiver open.")
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun perform(action: () -> Unit) {
        try {
            action()
        } catch (error: Exception) {
            refresh(error.message ?: "Action failed")
        }
    }

    private fun refresh(note: String? = null) {
        if (!::metrics.isInitialized) return
        message.text = note ?: LabRuntime.status
        val snapshot = LabRuntime.snapshot()
        val capture = snapshot.getJSONObject("capture")
        val relay = snapshot.getJSONObject("relay")
        summary.text = "${relay.optInt("peers")} peers  /  ${capture.optLong("frames")} captured frames\n" +
            "${relay.optLong("receivedFrames")} received  /  ${capture.optLong("nonSilentFrames")} signal frames"
        metrics.text = snapshot.toString(2)
        LabRuntime.writeReport(this)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("source", sourcePackage)
        outState.putBoolean("consent", pendingConsent)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !pendingConsent && !CaptureBus.snapshot().running) LabRuntime.stop(this)
    }

    override fun onDestroy() {
        unregisterReceiver(noisy)
        super.onDestroy()
    }

    companion object {
        private val NIGHT = Color.rgb(16, 19, 31)
        private val PANEL = Color.rgb(29, 33, 50)
        private val RAISED = Color.rgb(40, 45, 67)
        private val INK = Color.rgb(246, 244, 252)
        private val MUTED = Color.rgb(195, 196, 214)
        private val LIME = Color.rgb(201, 242, 113)
        private val VIOLET = Color.rgb(183, 165, 255)
        private val CORAL = Color.rgb(255, 161, 144)
        private const val SOURCE_PACKAGE = "com.pocketdisco.capturelab.source"
        private const val MUSIC_PACKAGE = "app.revanced.android.apps.youtube.music"
        private const val CAPTURE_PERMISSIONS = 100
        private const val CAPTURE_CONSENT = 101
        private const val BLUETOOTH_PERMISSION = 102
    }
}
