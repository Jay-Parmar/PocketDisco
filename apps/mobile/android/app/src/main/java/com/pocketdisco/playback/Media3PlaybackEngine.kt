package com.pocketdisco.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

@androidx.annotation.OptIn(UnstableApi::class)
class Media3PlaybackEngine(private val context: Context) : PlaybackEngine {
    override var listener: PlaybackEngine.Listener? = null
    private var released = false
    private val player = ExoPlayer.Builder(context)
        .setLooper(Looper.getMainLooper())
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                listener?.onInterrupted()
            }
        }
    }
    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
                listener?.onInterrupted()
            }
            listener?.onChanged()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ||
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY
            ) listener?.onInterrupted()
        }

        override fun onPlayerError(error: PlaybackException) {
            listener?.onError()
        }
    }

    init {
        check(Looper.myLooper() == Looper.getMainLooper())
        player.addListener(playerListener)
        try {
            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(noisyReceiver, filter)
            }
        } catch (error: Exception) {
            player.release()
            throw error
        }
    }

    override val state get() = when (player.playbackState) {
        Player.STATE_READY -> EngineState.READY
        Player.STATE_BUFFERING -> EngineState.BUFFERING
        Player.STATE_ENDED -> EngineState.ENDED
        else -> EngineState.IDLE
    }
    override val isPlaying get() = player.isPlaying
    override val positionMs get() = player.currentPosition.coerceAtLeast(0)
    override val durationMs get() = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }

    override fun prepare(positionMs: Long) {
        player.playWhenReady = false
        player.setMediaItem(MediaItem.fromUri("asset:///demo_pulse.m4a"), positionMs)
        player.prepare()
    }

    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun seek(positionMs: Long) = player.seekTo(positionMs)

    override fun release() {
        if (released) return
        released = true
        listener = null
        try {
            context.unregisterReceiver(noisyReceiver)
        } finally {
            player.removeListener(playerListener)
            player.release()
        }
    }
}
