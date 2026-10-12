package com.pocketdisco.capturelab

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean

class CaptureService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var session: Session? = null
    private var requestedCaptureId: Long? = null

    private class Session(val projection: MediaProjection) {
        val stopped = AtomicBoolean(false)
        val recorderLock = Any()
        var recorder: AudioRecord? = null
        var captureId = 0L
        var stopStatus = "Capture stopped"
        var workerStarted = false
        @Volatile var workerFinished = false
        lateinit var callback: MediaProjection.Callback
        lateinit var worker: Thread
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Audio capture test", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finishSession("Capture stopped")
            return START_NOT_STICKY
        }
        if (session != null) return START_NOT_STICKY
        val captureId = intent?.getLongExtra(EXTRA_CAPTURE_ID, 0L) ?: 0L
        if (!CaptureBus.isRunning(captureId)) {
            stopSelf()
            return START_NOT_STICKY
        }
        requestedCaptureId = captureId

        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                ?: Activity.RESULT_CANCELED
            val consent = if (Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra(EXTRA_CONSENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(EXTRA_CONSENT)
            }
            require(resultCode == Activity.RESULT_OK && consent != null) { "Capture consent is missing" }
            val sourcePackage = intent?.getStringExtra(EXTRA_SOURCE)
            require(sourcePackage in SOURCES) { "Select a supported test source" }
            val uid = packageManager.getApplicationInfo(requireNotNull(sourcePackage), 0).uid
            require(uid != Process.myUid()) { "The receiver cannot be its own capture source" }
            val projection = requireNotNull(
                getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, consent),
            ) { "Android did not grant a capture session" }
            val active = Session(projection)
            active.callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    if (session === active) finishSession("Capture permission revoked")
                }
            }
            active.captureId = captureId
            session = active
            projection.registerCallback(active.callback, main)
            active.worker = Thread({ capture(active, uid) }, "capture-pcm")
            active.worker.start()
            active.workerStarted = true
        } catch (error: Exception) {
            finishSession(error.message?.take(160) ?: "Capture could not start")
        }
        return START_NOT_STICKY
    }

    private fun capture(active: Session, uid: Int) {
        var failure: String? = null
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("Audio capture permission is not granted")
            }
            val configuration = AudioPlaybackCaptureConfiguration.Builder(active.projection)
                .addMatchingUid(uid)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .build()
            val format = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val minimum = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            check(minimum > 0) { "This phone does not support the test capture format" }
            synchronized(active.recorderLock) {
                if (active.stopped.get()) return
                val recorder = AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(maxOf(minimum, FRAME_BYTES * 8))
                    .setAudioPlaybackCaptureConfig(configuration)
                    .build()
                active.recorder = recorder
                check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Capture recorder did not initialize" }
                recorder.startRecording()
                check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Capture did not start" }
            }
            CaptureBus.ready(active.captureId)
            val recorder = requireNotNull(active.recorder)
            var frame = ByteArray(FRAME_BYTES)
            var filled = 0
            while (!active.stopped.get()) {
                val count = recorder.read(frame, filled, frame.size - filled, AudioRecord.READ_BLOCKING)
                if (active.stopped.get()) break
                check(count >= 0) { "Capture read failed ($count)" }
                if (count == 0) continue
                filled += count
                if (filled == frame.size) {
                    CaptureBus.record(active.captureId, frame)
                    frame = ByteArray(FRAME_BYTES)
                    filled = 0
                }
            }
        } catch (error: Exception) {
            if (!active.stopped.get()) failure = error.message?.take(160) ?: "Capture failed"
        } finally {
            synchronized(active.recorderLock) {
                active.recorder?.let { recorder ->
                    runCatching { recorder.stop() }
                    runCatching { recorder.release() }
                }
                active.recorder = null
            }
            active.workerFinished = true
            val status = failure ?: "Capture stopped"
            main.post {
                if (session === active) {
                    if (active.stopped.get()) completeStop(active) else finishSession(status)
                }
            }
        }
    }

    private fun finishSession(status: String) {
        val active = session
        if (active != null) {
            if (!active.stopped.compareAndSet(false, true)) return
            active.stopStatus = status
            synchronized(active.recorderLock) {
                active.recorder?.let { runCatching { it.stop() } }
            }
            runCatching { active.projection.unregisterCallback(active.callback) }
            runCatching { active.projection.stop() }
            if (!active.workerStarted || active.workerFinished) completeStop(active)
        } else {
            val captureId = requestedCaptureId
            requestedCaptureId = null
            if (captureId != null) CaptureBus.end(status, captureId)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun completeStop(active: Session) {
        if (session !== active) return
        session = null
        requestedCaptureId = null
        CaptureBus.end(active.stopStatus, active.captureId)
    }

    override fun onDestroy() {
        if (session != null || requestedCaptureId != null) finishSession("Capture stopped")
        super.onDestroy()
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("PocketDisco capture test")
            .setContentText("Selected app audio only. No microphone or screen recording.")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_BYTES = 640
        private const val CHANNEL = "capture-test"
        private const val NOTIFICATION_ID = 1101
        private const val ACTION_STOP = "com.pocketdisco.capturelab.STOP_CAPTURE"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_CONSENT = "projection_consent"
        private const val EXTRA_SOURCE = "source_package"
        private const val EXTRA_CAPTURE_ID = "capture_id"
        private val SOURCES = setOf(
            "com.pocketdisco.capturelab.source",
            "app.revanced.android.apps.youtube.music",
        )

        @Synchronized
        fun start(context: Context, resultCode: Int, data: Intent, sourcePackage: String) {
            if (CaptureBus.snapshot().running) return
            val captureId = CaptureBus.begin()
            try {
                context.startForegroundService(
                    Intent(context, CaptureService::class.java)
                        .putExtra(EXTRA_RESULT_CODE, resultCode)
                        .putExtra(EXTRA_CONSENT, data)
                        .putExtra(EXTRA_SOURCE, sourcePackage)
                        .putExtra(EXTRA_CAPTURE_ID, captureId),
                )
            } catch (error: Exception) {
                CaptureBus.end(error.message?.take(160) ?: "Capture could not start", captureId)
                throw error
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CaptureService::class.java))
        }
    }
}
