package com.pocketdisco.capturelab

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.pocketdisco.capturelab.protocol.RelayNode
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

object LabRuntime {
    private val main = Handler(Looper.getMainLooper())
    private var reportTask: Runnable? = null
    private var code = ""
    private var generation = 0L
    private var receivedSession: java.util.UUID? = null
    private var node: RelayNode? = null
    private var links: LabLinks? = null
    private var speaker: PcmSpeaker? = null
    private var sentProof = PcmProof()
    private var receivedProof = PcmProof()
    @Volatile var active = false
        private set
    @Volatile var status = "Ready. Use generated or authorized audio only."
        private set

    fun newCode(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    @Synchronized
    fun configure(context: Context, joinCode: String): LabLinks {
        val appContext = context.applicationContext
        require(joinCode.matches(Regex("[0-9a-fA-F]{32}"))) { "Enter the host's 32-character session code" }
        if (active) {
            check(code.equals(joinCode, ignoreCase = true)) { "Stop the current session before changing its code" }
            return checkNotNull(links)
        }
        check(!CaptureBus.snapshot().running) { "Wait for the previous capture to stop" }
        speaker?.close()
        speaker = null
        code = joinCode.lowercase()
        generation++
        val currentGeneration = generation
        sentProof = PcmProof()
        receivedProof = PcmProof()
        receivedSession = null
        val nextNode = RelayNode(code, onFrame = { frame ->
            synchronized(this) {
                if (active && generation == currentGeneration) {
                    if (receivedSession == null) receivedSession = frame.sessionId
                    if (receivedSession == frame.sessionId) {
                        receivedProof.add(frame.pcm)
                        speaker?.offer(frame.pcm)
                    } else {
                        status = "Only one source can play. Stop the session before switching sources."
                    }
                }
            }
        }, onEvent = { event ->
            synchronized(this) {
                if (active && generation == currentGeneration) status = event
            }
        })
        node = nextNode
        links = LabLinks(appContext, nextNode) { status = it }
        CaptureBus.onPcm = { pcm ->
            synchronized(this) {
                if (active && generation == currentGeneration && nextNode.publish(pcm)) sentProof.add(pcm)
            }
        }
        CaptureBus.onStopped = {
            synchronized(this) {
                if (generation == currentGeneration) stop(appContext)
            }
        }
        active = true
        reportTask = object : Runnable {
            override fun run() {
                if (!active) return
                writeReport(appContext)
                main.postDelayed(this, 1000)
            }
        }.also { main.post(it) }
        return checkNotNull(links)
    }

    @Synchronized
    fun receive(context: Context) {
        check(!CaptureBus.snapshot().running) { "Stop capture before starting receiver playback" }
        if (speaker == null) speaker = PcmSpeaker(context.applicationContext) { status = it }
        speaker?.start()
    }

    @Synchronized
    fun stopReceiver() {
        check(receivedSession == null) { "Stop the receiver session before starting a new source" }
        speaker?.close()
        speaker = null
    }

    @Synchronized
    fun stop(context: Context) {
        val appContext = context.applicationContext
        val stoppedGeneration = generation
        active = false
        CaptureBus.onPcm = null
        CaptureBus.onStopped = if (CaptureBus.snapshot().running) {
            {
                synchronized(this) {
                    if (!active && generation == stoppedGeneration) {
                        CaptureBus.onStopped = null
                        writeReport(appContext)
                    }
                }
            }
        } else null
        CaptureService.stop(context)
        links?.close()
        speaker?.close()
        reportTask?.let { main.removeCallbacks(it) }
        reportTask = null
        status = "Session stopped"
        writeReport(context)
    }

    @Synchronized
    fun snapshot(): JSONObject {
        val capture = CaptureBus.snapshot()
        val relay = node?.snapshot()
        return JSONObject().apply {
            put("diagnostic", true)
            put("sampleRate", 16000)
            put("frameMillis", 20)
            put("synchronizedPlayback", false)
            put("active", active)
            put("receivedSession", receivedSession?.toString() ?: "")
            put("status", status)
            put("capture", JSONObject().apply {
                put("running", capture.running)
                put("status", capture.status)
                put("frames", capture.frames)
                put("nonSilentFrames", capture.nonSilentFrames)
                put("peak", capture.peak)
            })
            put("relay", JSONObject().apply {
                put("peers", relay?.peers ?: 0)
                put("pendingPeers", relay?.pendingPeers ?: 0)
                put("publishedFrames", relay?.publishedFrames ?: 0)
                put("receivedFrames", relay?.receivedFrames ?: 0)
                put("sentFrames", relay?.sentFrames ?: 0)
                put("droppedFrames", relay?.droppedFrames ?: 0)
                put("rejectedPeers", relay?.rejectedPeers ?: 0)
                put("queuedFrames", relay?.queuedFrames ?: 0)
                put("lastError", relay?.lastError ?: "")
            })
            put("output", JSONObject().apply {
                put("writtenBytes", speaker?.writtenBytes?.get() ?: 0)
                put("playbackHeadFrames", speaker?.playbackHeadFrames ?: 0)
                put("droppedFrames", speaker?.droppedFrames?.get() ?: 0)
            })
            put("sentPcm", sentProof.snapshot())
            put("receivedPcm", receivedProof.snapshot())
        }
    }

    fun writeReport(context: Context) {
        File(context.filesDir, "report.json").writeText(snapshot().toString(2))
    }
}

private class PcmProof {
    private val digest = MessageDigest.getInstance("SHA-256")
    private val audibleDigest = MessageDigest.getInstance("SHA-256")
    private var frames = 0L
    private var nonSilentFrames = 0L
    private var peak = 0
    private var prefixHash = ""
    private var audibleHash = ""

    @Synchronized
    fun add(pcm: ByteArray) {
        var framePeak = 0
        for (index in pcm.indices step 2) {
            val value = ((pcm[index].toInt() and 255) or (pcm[index + 1].toInt() shl 8)).toShort().toInt()
            framePeak = maxOf(framePeak, kotlin.math.abs(value))
        }
        frames++
        if (framePeak > 32) {
            nonSilentFrames++
            if (nonSilentFrames <= 100) audibleDigest.update(pcm)
            if (nonSilentFrames == 100L) audibleHash = audibleDigest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        peak = maxOf(peak, framePeak)
        if (frames <= 100) digest.update(pcm)
        if (frames == 100L) prefixHash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    @Synchronized
    fun snapshot() = JSONObject().apply {
        put("frames", frames)
        put("nonSilentFrames", nonSilentFrames)
        put("peak", peak)
        put("first100FramesSha256", prefixHash)
        put("first100AudibleFramesSha256", audibleHash)
    }
}
