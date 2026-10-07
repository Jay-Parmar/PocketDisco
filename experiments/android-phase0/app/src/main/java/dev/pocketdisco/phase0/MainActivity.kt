package dev.pocketdisco.phase0

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.open_licensed_audio).setOnClickListener {
            startActivity(Intent(this, LicensedAudioActivity::class.java))
        }
        findViewById<Button>(R.id.open_youtube).setOnClickListener {
            startActivity(Intent(this, YouTubeActivity::class.java))
        }
    }
}
