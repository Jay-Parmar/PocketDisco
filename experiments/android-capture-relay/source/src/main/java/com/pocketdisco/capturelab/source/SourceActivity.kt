package com.pocketdisco.capturelab.source

import android.app.Activity
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
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
        window.decorView.systemUiVisibility = 0
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(NIGHT)
        }
        val padding = dp(20)
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
        label(layout, "POCKETDISCO / TEST SOURCE", 12f, LIME, true)
        label(layout, "Sound check.", 34f, INK, true)
        label(layout, "A quiet signal. A clear answer.", 17f)
        label(layout, "GENERATED AUDIO ONLY", 12f, VIOLET, true)
        label(layout, "Start at a low volume. These tones are made here, not taken from a music service.", 14f)
        val player = panel(layout)
        label(player, "01  /  CAPTURE POLICY", 12f, VIOLET, true)
        label(player, "Same tone. Different rules.", 23f, INK, true)
        status = TextView(this).apply {
            id = R.id.source_status
            textSize = 16f
            setTextColor(LIME)
            setPadding(0, dp(10), 0, dp(16))
        }
        player.addView(status)
        val horizontal = resources.configuration.screenWidthDp >= 360 && resources.configuration.fontScale <= 1.3f
        val policyRow = LinearLayout(this).apply {
            orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            player.addView(this)
        }
        val allowed = button(policyRow, R.id.source_allow, "Play allowed", LIME, NIGHT) { play(true) }
        val blocked = button(policyRow, R.id.source_block, "Play blocked", CORAL, NIGHT) { play(false) }
        if (horizontal) {
            allowed.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                topMargin = dp(8)
                marginEnd = dp(4)
            }
            blocked.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                topMargin = dp(8)
                marginStart = dp(4)
            }
        }
        label(player, "Allowed: the lab can capture the tone.\nBlocked: Android should return silence.", 14f)
        label(player, "These are technical capture settings, not copyright approval.", 13f, CORAL)
        button(player, R.id.source_stop, "Stop") {
            stopService(Intent(this, SourceService::class.java))
            refreshSoon()
        }
        button(player, R.id.source_refresh, "Refresh status") { refresh() }
        val localFile = panel(layout)
        label(localFile, "02  /  LOCAL FILE", 12f, VIOLET, true)
        label(localFile, "Try another player", 23f, INK, true)
        label(localFile, "Open our 24-second generated MP3 in your installed music app. No catalog track is opened.", 14f)
        button(localFile, R.id.source_file, "Open generated file in music app", VIOLET, NIGHT) { openGeneratedFile() }
        label(layout, "TEST SOURCE / INTERNAL ONLY", 12f, MUTED, true)
        setContentView(ScrollView(this).apply {
            setBackgroundColor(NIGHT)
            isFillViewport = true
            addView(layout)
        })
        refresh()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun label(parent: LinearLayout, title: String, size: Float = 16f, color: Int = MUTED, bold: Boolean = false) = TextView(this).apply {
        text = title
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(0, dp(5), 0, dp(7))
        parent.addView(this)
    }

    private fun panel(parent: LinearLayout) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(PANEL, 24)
        setPadding(dp(18), dp(18), dp(18), dp(18))
        parent.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(18)
            bottomMargin = dp(12)
        })
    }

    private fun button(layout: LinearLayout, viewId: Int, title: String, color: Int = RAISED, ink: Int = INK, action: () -> Unit): Button {
        val button = Button(this).apply {
            id = viewId
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
            setOnClickListener { action() }
        }
        layout.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        return button
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
        revokeUriPermission(GeneratedAudioProvider.MP3_URI, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(GeneratedAudioProvider.MP3_URI, "audio/mpeg")
            component = ComponentName(
                "app.revanced.android.apps.youtube.music",
                "com.google.android.apps.youtube.music.audiopreview.AudioPreviewPlayerActivity",
            )
            clipData = ClipData.newRawUri("Generated test pulse", GeneratedAudioProvider.MP3_URI)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            status.text = "The supplied music app is not available. No catalog audio was opened."
        }
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
    }
}
