package com.pocketdisco.capturelab.source

import android.app.Activity
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class SourceActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(255, 249, 238))
        }
        val padding = (24 * resources.displayMetrics.density).toInt()
        layout.setPadding(padding, padding, padding, padding)
        layout.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(padding, padding + insets.systemWindowInsetTop, padding, padding + insets.systemWindowInsetBottom)
            }
            insets
        }
        layout.addView(TextView(this).apply {
            text = "Test tone"
            textSize = 30f
            setTextColor(Color.rgb(40, 91, 69))
        })
        layout.addView(TextView(this).apply {
            text = "Generated audio only. Start quietly. Capture allowed and blocked use the same tone in separate players."
            textSize = 16f
            setPadding(0, padding / 2, 0, padding)
        })
        status = TextView(this).apply {
            id = R.id.source_status
            textSize = 16f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, padding)
        }
        layout.addView(status)
        button(layout, R.id.source_allow, "Play allowed") { play(true) }
        button(layout, R.id.source_block, "Play blocked") { play(false) }
        button(layout, R.id.source_stop, "Stop") {
            stopService(Intent(this, SourceService::class.java))
            refreshSoon()
        }
        button(layout, R.id.source_refresh, "Refresh status") { refresh() }
        button(layout, R.id.source_file, "Open generated file in music app") { openGeneratedFile() }
        setContentView(ScrollView(this).apply { addView(layout) })
        refresh()
    }

    private fun button(layout: LinearLayout, viewId: Int, title: String, action: () -> Unit) {
        layout.addView(Button(this).apply {
            id = viewId
            text = title
            isAllCaps = false
            setOnClickListener { action() }
        })
    }

    private fun play(allow: Boolean) {
        startForegroundService(Intent(this, SourceService::class.java).putExtra(SourceService.EXTRA_ALLOW, allow))
        refreshSoon()
    }

    private fun refreshSoon() {
        Handler(Looper.getMainLooper()).postDelayed({ if (!isDestroyed) refresh() }, 250)
    }

    private fun refresh() {
        status.text = SourceService.status
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) refresh()
    }

    private fun openGeneratedFile() {
        stopService(Intent(this, SourceService::class.java))
        revokeUriPermission(GeneratedAudioProvider.URI, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(GeneratedAudioProvider.URI, "audio/wav")
            component = ComponentName(
                "app.revanced.android.apps.youtube.music",
                "com.google.android.apps.youtube.music.audiopreview.AudioPreviewPlayerActivity",
            )
            clipData = ClipData.newRawUri("Generated test tone", GeneratedAudioProvider.URI)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            status.text = "The supplied music app is not available. No catalog audio was opened."
        }
    }
}
