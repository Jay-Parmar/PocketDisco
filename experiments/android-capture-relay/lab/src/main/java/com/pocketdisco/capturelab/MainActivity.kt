package com.pocketdisco.capturelab

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "PocketDisco Capture Lab\nInternal diagnostic, not a release app."
            textSize = 22f
            setPadding(24, 48, 24, 24)
        })
    }
}
