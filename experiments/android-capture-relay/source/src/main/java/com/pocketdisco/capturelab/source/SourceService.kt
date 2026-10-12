package com.pocketdisco.capturelab.source

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.IBinder

class SourceService : Service() {
    private var track: AudioTrack? = null
    private var focus: AudioFocusRequest? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Generated test tone", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || intent == null) {
            stopPlayback("Stopped")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        stopPlayback("Starting")
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            val allow = intent.getBooleanExtra(EXTRA_ALLOW, false)
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setAllowedCapturePolicy(if (allow) AudioAttributes.ALLOW_CAPTURE_BY_ALL else AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                .build()
            val manager = getSystemService(AudioManager::class.java)
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { change ->
                    if (change < 0) {
                        stopPlayback("Stopped after losing audio focus")
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
                .build()
            focus = request
            check(manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Audio focus was not granted" }
            val pcm = GeneratedTone.pcm(4)
            val player = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(GeneratedTone.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size)
                .build()
            track = player
            check(player.write(pcm, 0, pcm.size) == pcm.size) { "Could not load the generated tone" }
            check(player.setLoopPoints(0, pcm.size / 2, -1) == AudioTrack.SUCCESS) { "Could not loop the generated tone" }
            player.play()
            status = if (allow) "Playing generated tone: capture allowed" else "Playing generated tone: capture blocked"
        } catch (error: Exception) {
            stopPlayback(error.message?.take(160) ?: "Playback failed")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun stopPlayback(message: String) {
        track?.let { player ->
            runCatching { player.stop() }
            player.release()
        }
        track = null
        focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focus = null
        status = message
    }

    override fun onDestroy() {
        if (track != null || focus != null) stopPlayback("Stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, SourceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("PocketDisco generated tone")
            .setContentText("Quiet test signal. Not third-party music.")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        const val EXTRA_ALLOW = "allow_capture"
        private const val CHANNEL = "generated-tone"
        private const val NOTIFICATION_ID = 1102
        private const val ACTION_STOP = "com.pocketdisco.capturelab.source.STOP"

        @Volatile
        var status = "Stopped"
            private set
    }
}
