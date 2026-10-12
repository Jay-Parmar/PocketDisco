package com.pocketdisco.capturelab

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
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
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 36)
            setBackgroundColor(Color.rgb(249, 246, 237))
        }
        val scroll = ScrollView(this).apply {
            addView(column)
            fitsSystemWindows = true
        }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            }
            insets
        }
        setContentView(scroll)
        fun label(text: String, size: Float = 16f) = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(Color.rgb(25, 58, 47))
            setPadding(0, 12, 0, 12)
            column.addView(this)
        }
        fun button(text: String, action: () -> Unit) = Button(this).apply {
            this.text = text
            isAllCaps = false
            minHeight = 52
            setOnClickListener { perform(action) }
            column.addView(this)
        }
        label("PocketDisco Capture Lab", 26f)
        label("Internal test. Use generated or authorized audio only. Receivers lag the source; synchronized playback is not implemented.")
        label("1. Use the same session code on each phone")
        val preferences = getPreferences(MODE_PRIVATE)
        code = EditText(this).apply {
            id = View.generateViewId()
            contentDescription = "Session code"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
            setText(preferences.getString("code", null) ?: LabRuntime.newCode())
            column.addView(this)
        }
        button("New session code") {
            check(!LabRuntime.active) { "Stop the current session first" }
            code.setText(LabRuntime.newCode())
        }
        label("2. Choose a link")
        button("Host USB/TCP test") { configure().listenTcp(); refresh("Loopback host started. Connect the emulator next.") }
        button("Receive USB/TCP test") {
            val links = configure()
            LabRuntime.receive(this)
            links.connectEmulator()
            refresh("Connecting through the PC. Keep this receiver screen open.")
        }
        button("Host paired Bluetooth") { withBluetooth { configure().listenBluetooth(); refresh("Paired Bluetooth listener started.") } }
        button("Receive paired Bluetooth") { withBluetooth { chooseBluetoothPeer() } }
        label("Bluetooth: pair in Android settings first. Maximum three direct links, three hops. A receiver can also host a downstream link. Hardware validation is pending.")
        label("3. Select the source app, then approve capture")
        val sources = RadioGroup(this).apply { column.addView(this) }
        listOf("Generated source" to SOURCE_PACKAGE, "Music app local test file" to MUSIC_PACKAGE).forEach { (title, name) ->
            sources.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = title
                isChecked = sourcePackage == name
                setOnCheckedChangeListener { _, checked -> if (checked) sourcePackage = name }
            })
        }
        button("Start capture") {
            check(!CaptureBus.snapshot().running) { "Capture is already running" }
            configure()
            LabRuntime.stopReceiver()
            requestCapture()
        }
        button("Open generated source") {
            val launch = packageManager.getLaunchIntentForPackage(SOURCE_PACKAGE)
                ?: error("Install the companion Test Source APK first")
            startActivity(launch)
        }
        label("Android asks for screen/audio consent. This lab reads only playback audio from the selected app, not screen pixels or microphone audio. Sources that block capture remain silent.")
        button("Stop session") { LabRuntime.stop(this); refresh("Session stopped") }
        button("Refresh metrics") { refresh() }
        message = label("Ready")
        metrics = label("", 13f).apply { setTextIsSelectable(true) }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        }
        refresh()
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
        metrics.text = LabRuntime.snapshot().toString(2)
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
        private const val SOURCE_PACKAGE = "com.pocketdisco.capturelab.source"
        private const val MUSIC_PACKAGE = "app.revanced.android.apps.youtube.music"
        private const val CAPTURE_PERMISSIONS = 100
        private const val CAPTURE_CONSENT = 101
        private const val BLUETOOTH_PERMISSION = 102
    }
}
