package dev.pocketdisco.phase0

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

data class PlaybackProbeEvent(
    val name: String,
    val observedElapsedRealtimeMs: Long,
    val positionMs: Long? = null,
    val targetWallTimeMs: Long? = null,
    val targetElapsedRealtimeMs: Long? = null,
    val detail: String = "",
)

data class TimedPlayerState(
    val positionMs: Long,
    val bufferedPositionMs: Long,
    val durationMs: Long?,
    val playbackState: Int,
    val isPlaying: Boolean,
    val targetWallTimeMs: Long?,
    val targetElapsedRealtimeMs: Long?,
)

class LicensedAudioController(
    context: Context,
    private val elapsedRealtimeMs: () -> Long,
    private val onEvent: (PlaybackProbeEvent) -> Unit,
) {
    private val scheduler = MonotonicScheduler(elapsedRealtimeMs)
    private val player = ExoPlayer.Builder(context).build()
    private var scheduledTarget: CoordinationTarget? = null
    private var lastStartTarget: CoordinationTarget? = null

    val isReady: Boolean
        get() = player.playbackState == Player.STATE_READY

    init {
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true,
        )
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                emit(
                    name = "playback_state",
                    detail = playbackStateName(playbackState),
                )
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val target = scheduledTarget ?: lastStartTarget
                emit(
                    name = if (isPlaying) "playback_started" else "playback_not_playing",
                    target = target,
                    detail = if (isPlaying && target != null) {
                        "start_delta_ms=${elapsedRealtimeMs() - target.elapsedRealtimeMs}"
                    } else {
                        ""
                    },
                )
                if (isPlaying) scheduledTarget = null
                if (isPlaying) lastStartTarget = target
            }

            override fun onPlayerError(error: PlaybackException) {
                emit(
                    name = "playback_error",
                    detail = "code=${error.errorCode};name=${error.errorCodeName}",
                )
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                emit(
                    name = "position_discontinuity",
                    detail = "reason=$reason;from_ms=${oldPosition.positionMs};to_ms=${newPosition.positionMs}",
                )
            }
        })
    }

    fun preload(assetUrl: String, assetLabel: String, positionMs: Long) {
        scheduler.cancel()
        scheduledTarget = null
        lastStartTarget = null
        player.stop()
        player.playWhenReady = false
        player.setMediaItem(MediaItem.fromUri(Uri.parse(assetUrl)), positionMs)
        player.prepare()
        emit(
            name = "preload_requested",
            detail = "asset_label=${assetLabel.take(100)};source_host=${Uri.parse(assetUrl).host.orEmpty()}",
        )
    }

    fun schedulePlay(target: CoordinationTarget, positionMs: Long) {
        require(isReady) { "Player must be ready before scheduling" }
        require(target.elapsedRealtimeMs > elapsedRealtimeMs()) { "Start target has already passed" }
        scheduler.cancel()
        player.pause()
        player.seekTo(positionMs)
        lastStartTarget = null
        scheduledTarget = target
        emit(name = "play_scheduled", target = target)
        scheduler.scheduleAt(target.elapsedRealtimeMs) { actualElapsedRealtimeMs ->
            emit(
                name = "play_command_issued",
                target = target,
                detail = "command_delta_ms=${actualElapsedRealtimeMs - target.elapsedRealtimeMs}",
            )
            player.play()
        }
    }

    fun playNow() {
        scheduler.cancel()
        scheduledTarget = null
        lastStartTarget = null
        emit(name = "play_now_requested")
        player.play()
    }

    fun pause() {
        scheduler.cancel()
        val target = scheduledTarget ?: lastStartTarget
        scheduledTarget = null
        emit(name = "pause_requested", target = target)
        player.pause()
    }

    fun seekTo(positionMs: Long) {
        emit(name = "seek_requested", detail = "to_ms=$positionMs")
        player.seekTo(positionMs)
    }

    fun timedState(): TimedPlayerState {
        val target = scheduledTarget ?: lastStartTarget
        return TimedPlayerState(
            positionMs = player.currentPosition,
            bufferedPositionMs = player.bufferedPosition,
            durationMs = player.duration.takeUnless { it == C.TIME_UNSET },
            playbackState = player.playbackState,
            isPlaying = player.isPlaying,
            targetWallTimeMs = target?.wallTimeMs,
            targetElapsedRealtimeMs = target?.elapsedRealtimeMs,
        )
    }

    fun release() {
        scheduler.cancel()
        player.release()
    }

    private fun emit(
        name: String,
        target: CoordinationTarget? = scheduledTarget ?: lastStartTarget,
        detail: String = "",
    ) {
        onEvent(
            PlaybackProbeEvent(
                name = name,
                observedElapsedRealtimeMs = elapsedRealtimeMs(),
                positionMs = player.currentPosition,
                targetWallTimeMs = target?.wallTimeMs,
                targetElapsedRealtimeMs = target?.elapsedRealtimeMs,
                detail = detail,
            ),
        )
    }

    companion object {
        fun playbackStateName(state: Int): String = when (state) {
            Player.STATE_IDLE -> "idle"
            Player.STATE_BUFFERING -> "buffering"
            Player.STATE_READY -> "ready"
            Player.STATE_ENDED -> "ended"
            else -> "unknown_$state"
        }
    }
}
