package dev.pocketdisco.phase0

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper

data class OutputTrackStatus(
    val trackLabel: String,
    val requestedDeviceId: Int?,
    val preferenceAccepted: Boolean?,
    val actualDeviceIds: Set<Int>,
    val underrunCount: Int,
    val framePosition: Long?,
    val frameNanoTime: Long?,
)

data class MultiOutputStatus(
    val mode: AndroidRouteMode,
    val route: OutputRouteResult,
    val tracks: List<OutputTrackStatus>,
)

data class MultiOutputProbeEvent(
    val name: String,
    val elapsedRealtimeMs: Long,
    val status: MultiOutputStatus?,
    val detail: String = "",
)

class MultiOutputController(
    context: Context,
    private val elapsedRealtimeMs: () -> Long,
    private val onEvent: (MultiOutputProbeEvent) -> Unit,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val scheduler = MonotonicScheduler(elapsedRealtimeMs)
    private val handler = Handler(Looper.getMainLooper())
    private val signal = ClickSignal.create()
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private val audioFormat = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(signal.sampleRateHz)
        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(audioAttributes)
        .setOnAudioFocusChangeListener(::onAudioFocusChange)
        .build()
    private val managedTracks = mutableListOf<ManagedTrack>()
    private val routeTracker = OutputRouteTracker()
    private var mode: AndroidRouteMode? = null
    private val pollRoutes = object : Runnable {
        override fun run() {
            if (!observeRoute("route_sample")) return
            handler.postDelayed(this, ROUTE_POLL_INTERVAL_MS)
        }
    }

    fun scheduleDual(
        firstDeviceId: Int,
        secondDeviceId: Int,
        targetElapsedRealtimeMs: Long,
    ) {
        require(targetElapsedRealtimeMs > elapsedRealtimeMs()) { "Start target has already passed" }
        val devices = currentOutputDevices()
        val descriptors = devices.map(::describe)
        val selection = MultiOutputSelector.select(descriptors, firstDeviceId, secondDeviceId)
        require(selection is MultiOutputSelection.Valid) {
            "Invalid output selection: ${(selection as MultiOutputSelection.Invalid).reason.name.lowercase()}"
        }
        val byId = devices.associateBy(AudioDeviceInfo::getId)
        val targets = selection.targets.map { descriptor ->
            requireNotNull(byId[descriptor.id]) { "Output ${descriptor.id} disconnected" }
        }
        schedule(
            routeMode = AndroidRouteMode.DUAL_TRACK,
            targets = targets.mapIndexed { index, device ->
                TrackTarget(label = "track_${index + 1}", device = device)
            },
            targetElapsedRealtimeMs = targetElapsedRealtimeMs,
        )
    }

    fun scheduleSystemRoute(targetElapsedRealtimeMs: Long) {
        require(targetElapsedRealtimeMs > elapsedRealtimeMs()) { "Start target has already passed" }
        schedule(
            routeMode = AndroidRouteMode.SYSTEM_GROUP,
            targets = listOf(TrackTarget(label = "system", device = null)),
            targetElapsedRealtimeMs = targetElapsedRealtimeMs,
        )
    }

    fun status(): MultiOutputStatus {
        val routeMode = requireNotNull(mode) { "No output probe is active" }
        val tracks = managedTracks.map(::trackStatus)
        val route = OutputRouteAssessment.evaluate(
            observations = tracks.map {
                TrackRouteObservation(
                    trackLabel = it.trackLabel,
                    requestedDeviceId = it.requestedDeviceId,
                    actualDeviceIds = it.actualDeviceIds,
                )
            },
            mode = routeMode,
        )
        return MultiOutputStatus(routeMode, route, tracks)
    }

    fun stop(reason: String = "operator") {
        scheduler.cancel()
        handler.removeCallbacks(pollRoutes)
        val hadTracks = managedTracks.isNotEmpty()
        val stoppedStatus = if (hadTracks) runCatching(::status).getOrNull() else null
        managedTracks.forEach { managed ->
            runCatching {
                managed.track.removeOnRoutingChangedListener(managed.routingListener)
            }
            runCatching {
                if (managed.track.playState != AudioTrack.PLAYSTATE_STOPPED) {
                    managed.track.stop()
                }
            }
            runCatching(managed.track::release)
        }
        managedTracks.clear()
        mode = null
        routeTracker.reset()
        runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
        if (hadTracks) emit("playback_stopped", stoppedStatus, "reason=$reason")
    }

    fun release() {
        stop("released")
    }

    private fun schedule(
        routeMode: AndroidRouteMode,
        targets: List<TrackTarget>,
        targetElapsedRealtimeMs: Long,
    ) {
        stop("replaced")
        require(
            audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
        ) { "Audio focus was not granted" }
        mode = routeMode
        try {
            targets.forEach { target -> managedTracks += buildTrack(target) }
        } catch (error: Exception) {
            stop("prepare_failed")
            throw error
        }
        emit(
            name = "playback_scheduled",
            status = status(),
            detail = "target_elapsed_realtime_ms=$targetElapsedRealtimeMs",
        )
        scheduler.scheduleAt(targetElapsedRealtimeMs) { actualElapsedRealtimeMs ->
            val firstCallNs = System.nanoTime()
            attemptPlaybackStart(
                playActions = managedTracks.map { managed -> managed.track::play },
                afterStarted = {
                    val finalCallNs = System.nanoTime()
                    val playbackStatus = status()
                    emit(
                        name = "playback_started",
                        status = playbackStatus,
                        detail = "command_delta_ms=${actualElapsedRealtimeMs - targetElapsedRealtimeMs};" +
                            "play_call_span_ns=${finalCallNs - firstCallNs}",
                    )
                    if (!stopIfRouteLost(playbackStatus)) {
                        handler.removeCallbacks(pollRoutes)
                        handler.postDelayed(pollRoutes, ROUTE_WARMUP_MS)
                    }
                },
                onFailure = ::handlePlaybackStartFailure,
            )
        }
    }

    private fun buildTrack(target: TrackTarget): ManagedTrack {
        val track = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(signal.samples.size * Short.SIZE_BYTES)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            val preferenceAccepted = target.device?.let(track::setPreferredDevice)
            val written = track.write(signal.samples, 0, signal.samples.size, AudioTrack.WRITE_BLOCKING)
            check(written == signal.samples.size) { "AudioTrack wrote $written of ${signal.samples.size} samples" }
            check(track.setLoopPoints(0, signal.samples.size, -1) == AudioTrack.SUCCESS) {
                "AudioTrack rejected loop points"
            }
            track.setVolume(SAFE_TRACK_VOLUME)
            lateinit var listener: AudioRouting.OnRoutingChangedListener
            listener = AudioRouting.OnRoutingChangedListener {
                if (managedTracks.any { it.track === track }) {
                    observeRoute("routing_changed")
                }
            }
            track.addOnRoutingChangedListener(listener, handler)
            return ManagedTrack(
                label = target.label,
                requestedDeviceId = target.device?.id,
                preferenceAccepted = preferenceAccepted,
                track = track,
                routingListener = listener,
            )
        } catch (error: Exception) {
            track.release()
            throw error
        }
    }

    private fun trackStatus(managed: ManagedTrack): OutputTrackStatus {
        val timestamp = AudioTimestamp()
        val hasTimestamp = managed.track.getTimestamp(timestamp)
        return OutputTrackStatus(
            trackLabel = managed.label,
            requestedDeviceId = managed.requestedDeviceId,
            preferenceAccepted = managed.preferenceAccepted,
            actualDeviceIds = routedDeviceIds(managed.track),
            underrunCount = managed.track.underrunCount,
            framePosition = timestamp.framePosition.takeIf { hasTimestamp },
            frameNanoTime = timestamp.nanoTime.takeIf { hasTimestamp },
        )
    }

    @Suppress("DEPRECATION")
    private fun routedDeviceIds(track: AudioTrack): Set<Int> = if (Build.VERSION.SDK_INT >= 36) {
        track.routedDevices.map(AudioDeviceInfo::getId).toSet()
    } else {
        setOfNotNull(track.routedDevice?.id)
    }

    private fun currentOutputDevices(): List<AudioDeviceInfo> =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()

    private fun describe(device: AudioDeviceInfo) = OutputDeviceDescriptor(
        id = device.id,
        label = device.productName.toString(),
        androidType = device.type,
        transport = OutputTransport.fromAndroidType(device.type),
        targetRole = OutputTargetRole.fromAndroidType(device.type),
    )

    private fun onAudioFocusChange(change: Int) {
        if (change < 0) {
            emit("audio_focus_lost", managedTracks.takeIf { it.isNotEmpty() }?.let { status() }, "change=$change")
            stop("audio_focus_lost")
        }
    }

    private fun observeRoute(eventName: String): Boolean {
        if (managedTracks.isEmpty()) return false
        val currentStatus = status()
        emit(eventName, currentStatus)
        return !stopIfRouteLost(currentStatus)
    }

    private fun stopIfRouteLost(currentStatus: MultiOutputStatus): Boolean {
        if (!routeTracker.observe(currentStatus.route.state)) return false
        emit("route_lost", currentStatus)
        stop("route_lost")
        return true
    }

    private fun handlePlaybackStartFailure(error: Exception) {
        val failureStatus = runCatching(::status).getOrNull()
        try {
            emit(
                name = "playback_start_failed",
                status = failureStatus,
                detail = "error=${error.javaClass.simpleName}",
            )
        } finally {
            stop("start_failed")
        }
    }

    private fun emit(
        name: String,
        status: MultiOutputStatus?,
        detail: String = "",
    ) {
        onEvent(MultiOutputProbeEvent(name, elapsedRealtimeMs(), status, detail))
    }

    private data class TrackTarget(
        val label: String,
        val device: AudioDeviceInfo?,
    )

    private data class ManagedTrack(
        val label: String,
        val requestedDeviceId: Int?,
        val preferenceAccepted: Boolean?,
        val track: AudioTrack,
        val routingListener: AudioRouting.OnRoutingChangedListener,
    )

    companion object {
        private const val SAFE_TRACK_VOLUME = 0.2f
        private const val ROUTE_WARMUP_MS = 500L
        private const val ROUTE_POLL_INTERVAL_MS = 1_000L
    }
}

internal fun attemptPlaybackStart(
    playActions: List<() -> Unit>,
    afterStarted: () -> Unit,
    onFailure: (Exception) -> Unit,
): Boolean = try {
    playActions.forEach { it() }
    afterStarted()
    true
} catch (error: Exception) {
    onFailure(error)
    false
}
