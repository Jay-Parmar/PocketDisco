package com.pocketdisco.capturelab.protocol

import java.io.Closeable
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

data class RelaySnapshot(
    val peers: Int,
    val pendingPeers: Int,
    val publishedFrames: Long,
    val receivedFrames: Long,
    val sentFrames: Long,
    val droppedFrames: Long,
    val rejectedPeers: Long,
    val queuedFrames: Int,
    val lastError: String?,
    val timedFrames: Long = 0,
    val unscheduledFrames: Long = 0,
    val clockReady: Boolean = false,
    val clockUncertaintyNanos: Long? = null,
    val clockAgeMillis: Long? = null,
    val clockSamples: Int = 0,
    // Local probe writes only. Forwarded probes are excluded.
    val clockProbesSent: Long = 0,
    // Replies passed peer, nonce and freshness checks before estimator acceptance or rejection.
    val clockRepliesAccepted: Long = 0,
    val clockRepliesRejected: Long = 0,
    // Pending local probes that exceeded their reply deadline, excluding disconnects and clock resets.
    val clockProbeTimeouts: Long = 0,
    // Last estimator reply, including rejected replies. Round trip excludes root processing time.
    val clockLastRoundTripNanos: Long? = null,
    val clockLastElapsedNanos: Long? = null,
)

class RelayNode(
    joinCode: String,
    private val onFrame: (AudioFrame) -> Unit = {},
    private val onEvent: (String) -> Unit = {},
    private val handshakeTimeoutMillis: Long = 5_000,
    private val writeTimeoutMillis: Long = 5_000,
    private val queueCapacity: Int = 25,
    private val acceptSession: (UUID) -> Boolean = { true },
    private val onTimedFrame: (TimedAudioFrame) -> Unit = {},
    private val clockNanos: () -> Long = System::nanoTime,
) : Closeable {
    val nodeId: UUID = UUID.randomUUID()
    val sessionId: UUID = UUID.randomUUID()

    private val key = PcmWire.key(joinCode)
    private val lock = Any()
    private val receiveLock = Any()
    private val publishLock = Any()
    private val peers = LinkedHashSet<Peer>()
    private val highestSequence = linkedMapOf(sessionId to -1L)
    private val rootClock = RootClock()
    private val clockRoutes = LinkedHashMap<UUID, ClockRoute>()
    private var clockSession: UUID? = null
    private var clockParent: Peer? = null
    private var pendingClock: PendingClock? = null
    private var nextProbeAt = 0L
    private var clockProbes = 0
    private var sourceStartedAt: Long? = null
    private val watchdog = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "capture-relay-watchdog").apply { isDaemon = true }
    }
    private var closed = false
    private var nextSequence = 0L
    private var publishedFrames = 0L
    private var receivedFrames = 0L
    private var sentFrames = 0L
    private var droppedFrames = 0L
    private var rejectedPeers = 0L
    private var lastError: String? = null
    private var timedFrames = 0L
    private var unscheduledFrames = 0L
    private var clockProbesSent = 0L
    private var clockProbeTimeouts = 0L

    init {
        require(handshakeTimeoutMillis in 1..30_000)
        require(writeTimeoutMillis in 1..30_000)
        require(queueCapacity in 1..25)
        watchdog.scheduleAtFixedRate({ checkDeadlines(); probeClock() }, 50, 50, TimeUnit.MILLISECONDS)
    }

    // closeTransport must unblock both streams. Callbacks must not block.
    fun attach(input: InputStream, output: OutputStream, closeTransport: () -> Unit): Boolean {
        val peer = Peer(input, output, closeTransport)
        val accepted = synchronized(lock) {
            if (closed || peers.size >= MAX_PEERS) {
                rejectedPeers++
                lastError = if (closed) "Relay closed" else "Peer limit reached"
                false
            } else {
                peers.add(peer)
                true
            }
        }
        if (accepted) peer.start() else peer.shutdown()
        return accepted
    }

    fun publish(pcm: ByteArray, capturedNanos: Long = clockNanos().coerceAtLeast(0)): Boolean {
        return synchronized(publishLock) {
            require(pcm.size == PcmFormat.FRAME_BYTES)
            require(capturedNanos >= 0)
            val frame = synchronized(lock) {
                if (closed || nextSequence == Long.MAX_VALUE) return false
                val anchor = sourceStartedAt ?: capturedNanos
                if (anchor > Long.MAX_VALUE - PcmFormat.PLAYOUT_DELAY_NANOS ||
                    nextSequence > (Long.MAX_VALUE - PcmFormat.PLAYOUT_DELAY_NANOS - anchor) / PcmFormat.FRAME_NANOS
                ) return false
                sourceStartedAt = anchor
                val timestamp = anchor + nextSequence * PcmFormat.FRAME_NANOS
                val frame = AudioFrame(sessionId, nextSequence++, timestamp, 0, pcm.copyOf())
                highestSequence[sessionId] = frame.sequence
                publishedFrames++
                frame
            }
            forward(frame, null)
            true
        }
    }

    fun snapshot(): RelaySnapshot = synchronized(lock) {
        val estimate = rootClock.estimate(clockNanos())
        RelaySnapshot(
            peers.count { it.authenticated },
            peers.count { !it.authenticated },
            publishedFrames,
            receivedFrames,
            sentFrames,
            droppedFrames,
            rejectedPeers,
            peers.sumOf { it.queue.size },
            lastError,
            timedFrames,
            unscheduledFrames,
            !closed && clockParent?.available() == true && estimate?.ready == true,
            estimate?.uncertaintyNanos,
            estimate?.ageNanos?.let { TimeUnit.NANOSECONDS.toMillis(it) },
            estimate?.samples ?: 0,
            clockProbesSent,
            rootClock.acceptedReplies,
            rootClock.rejectedReplies,
            clockProbeTimeouts,
            rootClock.lastRoundTripNanos,
            rootClock.lastElapsedNanos,
        )
    }

    override fun close() {
        val connected = synchronized(lock) {
            if (closed) return
            closed = true
            clearClock()
            peers.toList().also { peers.clear() }
        }
        connected.forEach { it.shutdown() }
        watchdog.shutdownNow()
    }

    private fun receive(frame: AudioFrame, source: Peer) {
        synchronized(receiveLock) {
            synchronized(lock) {
                if (closed || source.stopped.get()) return
                val previous = highestSequence[frame.sessionId]
                if (frame.sessionId == sessionId || previous != null && frame.sequence <= previous) {
                    droppedFrames++
                    return
                }
            }
            if (!acceptSession(frame.sessionId)) {
                synchronized(lock) { droppedFrames++ }
                return
            }
            synchronized(lock) {
                if (closed || source.stopped.get()) return
                val previous = highestSequence[frame.sessionId]
                if (previous == null && highestSequence.size >= MAX_SESSIONS) {
                    throw IOException("Stream limit reached")
                }
                highestSequence[frame.sessionId] = frame.sequence
                receivedFrames++
                if (clockSession == null) {
                    clockSession = frame.sessionId
                    clockParent = source
                } else if (clockSession == frame.sessionId && clockParent?.available() != true) {
                    clearClock()
                    clockParent = source
                    clockProbes = 0
                    nextProbeAt = 0
                }
            }
            forward(frame, source)
            onFrame(frame.copy(pcm = frame.pcm.copyOf()))
            deliverTimed(frame)
        }
    }

    private fun forward(frame: AudioFrame, source: Peer?) {
        if (frame.hops >= PcmFormat.MAX_HOPS) return
        val targets = synchronized(lock) {
            if (closed) return
            peers.filter { it !== source && it.authenticated && !it.stopped.get() && !it.overflowed.get() }
        }
        val outgoing = frame.copy(hops = frame.hops + 1)
        for (peer in targets) {
            peer.offerAudio(outgoing)
        }
    }

    private fun deliverTimed(frame: AudioFrame) {
        val timed = synchronized(lock) {
            if (closed) return
            val now = clockNanos()
            val estimate = rootClock.estimate(now)
            if (frame.sessionId != clockSession || clockParent?.available() != true || estimate?.ready != true) {
                unscheduledFrames++
                return
            }
            val deadline = try {
                Math.subtractExact(frame.capturedNanos + PcmFormat.PLAYOUT_DELAY_NANOS, estimate.offsetNanos)
            } catch (_: ArithmeticException) {
                unscheduledFrames++
                return
            }
            if (deadline <= now || deadline - now > MAX_TIMED_LEAD_NANOS) {
                unscheduledFrames++
                return
            }
            timedFrames++
            TimedAudioFrame(frame.copy(pcm = frame.pcm.copyOf()), deadline, estimate.uncertaintyNanos)
        }
        onTimedFrame(timed)
    }

    private fun probeClock() {
        val work = synchronized(lock) {
            if (closed) return
            val now = clockNanos()
            clockRoutes.entries.removeAll { now < it.value.createdAt || now - it.value.createdAt > PROBE_TIMEOUT_NANOS }
            pendingClock?.let {
                if (now < it.sentNanos || now - it.sentNanos > PROBE_TIMEOUT_NANOS) {
                    if (now >= it.sentNanos) clockProbeTimeouts++
                    pendingClock = null
                }
            }
            val parent = clockParent ?: return
            val session = clockSession ?: return
            if (!parent.available() || pendingClock != null || now < nextProbeAt) return
            val request = ClockRequest(session, UUID.randomUUID(), now, 1)
            pendingClock = PendingClock(request.requestId, now, parent)
            clockProbes++
            nextProbeAt = now + if (clockProbes < 7) INITIAL_PROBE_INTERVAL_NANOS else PROBE_INTERVAL_NANOS
            parent to request
        }
        work.first.offerControl(work.second, localProbe = true)
    }

    private fun receiveClockRequest(request: ClockRequest, source: Peer) {
        val receivedAt = clockNanos()
        val work = synchronized(lock) {
            if (closed || !source.available()) return
            source.checkControlRate(receivedAt)
            if (request.sessionId == sessionId && publishedFrames > 0) {
                source to ClockReply(
                    sessionId, request.requestId, request.sentNanos, receivedAt, clockNanos(), request.hops,
                )
            } else {
                val parent = clockParent ?: return
                if (request.sessionId != clockSession || parent === source || !parent.available() ||
                    request.hops >= PcmFormat.MAX_HOPS || clockRoutes.containsKey(request.requestId) ||
                    clockRoutes.size >= MAX_CLOCK_ROUTES || pendingClock?.requestId == request.requestId
                ) return
                clockRoutes[request.requestId] = ClockRoute(
                    request.sessionId, request.sentNanos, source, parent, request.hops, receivedAt,
                )
                parent to request.copy(hops = request.hops + 1)
            }
        }
        work.first.offerControl(work.second)
    }

    private fun receiveClockReply(reply: ClockReply, source: Peer) {
        val now = clockNanos()
        val work = synchronized(lock) {
            if (closed || !source.available()) return
            source.checkControlRate(now)
            val pending = pendingClock
            if (pending?.requestId == reply.requestId) {
                if (source !== pending.parent || reply.sessionId != clockSession || reply.hops != 1 ||
                    reply.sentNanos != pending.sentNanos || now < pending.sentNanos ||
                    now - pending.sentNanos > PROBE_TIMEOUT_NANOS
                ) return
                pendingClock = null
                rootClock.add(pending.sentNanos, reply.rootReceivedNanos, reply.rootSentNanos, now)
                return
            }
            val route = clockRoutes[reply.requestId] ?: return
            if (source !== route.parent || reply.sessionId != route.sessionId ||
                reply.sentNanos != route.sentNanos || reply.hops != route.hops + 1 ||
                now < route.createdAt || now - route.createdAt > PROBE_TIMEOUT_NANOS ||
                !route.child.available()
            ) return
            clockRoutes.remove(reply.requestId)
            route.child to reply.copy(hops = reply.hops - 1)
        }
        work.first.offerControl(work.second)
    }

    private fun clearClock() {
        rootClock.reset()
        pendingClock = null
        clockParent = null
        clockRoutes.clear()
    }

    private fun removePeer(peer: Peer) {
        peers.remove(peer)
        clockRoutes.entries.removeAll { it.value.parent === peer || it.value.child === peer }
        if (clockParent === peer) clearClock()
    }

    private class PendingClock(val requestId: UUID, val sentNanos: Long, val parent: Peer)
    private class QueuedControl(val packet: RelayPacket, val localProbe: Boolean)
    private class ClockRoute(
        val sessionId: UUID,
        val sentNanos: Long,
        val child: Peer,
        val parent: Peer,
        val hops: Int,
        val createdAt: Long,
    )

    private fun authenticated(peer: Peer, remoteId: UUID): Boolean = synchronized(lock) {
        if (closed || peer.stopped.get()) return false
        if (peers.any { it !== peer && it.remoteId == remoteId }) {
            throw IOException("Peer already connected")
        }
        peer.remoteId = remoteId
        peer.authenticated = true
        peer.deadlineNanos = 0
        true
    }

    private fun checkDeadlines() {
        val connected = synchronized(lock) { peers.toList() }
        connected.forEach { it.checkDeadline() }
    }

    private fun event(message: String) {
        try {
            onEvent(message)
        } catch (_: RuntimeException) {
            synchronized(lock) { lastError = "Event callback failed" }
        }
    }

    private inner class Peer(
        input: InputStream,
        private val output: OutputStream,
        private val closeTransport: () -> Unit,
    ) {
        private val input = BufferedInputStream(input, 4_096)
        val queue = ArrayBlockingQueue<AudioFrame>(queueCapacity)
        private val controls = ArrayBlockingQueue<QueuedControl>(MAX_CLOCK_ROUTES)
        private val queued = Semaphore(0)
        val stopped = AtomicBoolean(false)
        val overflowed = AtomicBoolean(false)
        @Volatile var authenticated = false
        @Volatile var remoteId: UUID? = null
        var deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(handshakeTimeoutMillis)
        @Volatile private var readerThread: Thread? = null
        @Volatile private var writerThread: Thread? = null
        private var controlWindowAt = 0L
        private var controlCount = 0

        fun available() = authenticated && !stopped.get() && !overflowed.get()

        fun offerAudio(frame: AudioFrame) {
            if (!available()) return
            if (queue.offer(frame)) queued.release() else markSlow()
        }

        fun offerControl(packet: RelayPacket, localProbe: Boolean = false) {
            if (!available()) return
            if (controls.offer(QueuedControl(packet, localProbe))) queued.release() else markSlow()
        }

        fun checkControlRate(now: Long) {
            if (now < controlWindowAt || now - controlWindowAt >= PROBE_INTERVAL_NANOS) {
                controlWindowAt = now
                controlCount = 0
            }
            if (++controlCount > 128) throw IOException("Clock request limit reached")
        }

        fun start() {
            val reader = thread(start = false, name = "capture-relay-reader", isDaemon = true) {
                try {
                    if (stopped.get()) return@thread
                    val remoteId = PcmWire.authenticate(input, output, nodeId, key)
                    if (!authenticated(this, remoteId)) return@thread
                    event("Peer connected")
                    if (stopped.get()) return@thread
                    val writer = thread(start = false, name = "capture-relay-writer", isDaemon = true) { writeLoop() }
                    writerThread = writer
                    writer.start()
                    while (!stopped.get()) {
                        when (val packet = PcmWire.readPacket(input)) {
                            is PcmPacket -> receive(packet.frame, this)
                            is ClockRequest -> receiveClockRequest(packet, this)
                            is ClockReply -> receiveClockReply(packet, this)
                        }
                    }
                } catch (_: EOFException) {
                    fail("Peer disconnected")
                } catch (error: IOException) {
                    fail(error.message ?: "Peer read failed")
                } catch (_: RuntimeException) {
                    fail("Peer callback failed")
                } finally {
                    shutdown()
                }
            }
            readerThread = reader
            reader.start()
        }

        private fun writeLoop() {
            try {
                while (!stopped.get()) {
                    queued.acquire()
                    if (stopped.get()) break
                    val control = controls.poll()
                    val packet = control?.packet ?: queue.poll()?.let(::PcmPacket) ?: continue
                    synchronized(lock) {
                        if (stopped.get()) return
                        deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(writeTimeoutMillis)
                    }
                    PcmWire.writePacket(output, packet)
                    synchronized(lock) {
                        deadlineNanos = 0
                        if (packet is PcmPacket) sentFrames++
                        if (control?.localProbe == true) clockProbesSent++
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: IOException) {
                fail(error.message ?: "Peer write failed")
            } finally {
                shutdown()
            }
        }

        fun markSlow() {
            if (overflowed.compareAndSet(false, true)) {
                synchronized(lock) { droppedFrames++ }
                queue.clear()
            }
        }

        fun fail(message: String) {
            if (!synchronized(lock) { markFailed(message) }) return
            closeStreams()
            event(message)
        }

        fun checkDeadline() {
            val message = synchronized(lock) {
                if (closed || stopped.get()) return
                val expired = deadlineExpired(System.nanoTime()) { deadlineNanos }
                val message = when {
                    overflowed.get() -> "Slow peer queue full"
                    !expired -> return
                    authenticated -> "Peer write timeout"
                    else -> "Peer handshake timeout"
                }
                if (!markFailed(message)) return
                message
            }
            closeStreams()
            event(message)
        }

        private fun markFailed(message: String): Boolean {
            if (!stopped.compareAndSet(false, true)) return false
            removePeer(this)
            if (!authenticated) rejectedPeers++
            lastError = message
            return true
        }

        fun shutdown() {
            if (!stopped.compareAndSet(false, true)) return
            synchronized(lock) { removePeer(this) }
            closeStreams()
        }

        private fun closeStreams() {
            queue.clear()
            controls.clear()
            readerThread?.interrupt()
            writerThread?.interrupt()
            try {
                closeTransport()
            } catch (_: Exception) {
                synchronized(lock) { lastError = "Transport close failed" }
            }
        }
    }

    companion object {
        const val MAX_PEERS = 3
        const val MAX_SESSIONS = 32
        private const val MAX_CLOCK_ROUTES = 32
        private const val PROBE_TIMEOUT_NANOS = 1_000_000_000L
        private const val INITIAL_PROBE_INTERVAL_NANOS = 100_000_000L
        private const val PROBE_INTERVAL_NANOS = 1_000_000_000L
        private const val MAX_TIMED_LEAD_NANOS = 3_000_000_000L
    }
}

internal inline fun deadlineExpired(nowNanos: Long, readDeadline: () -> Long): Boolean {
    val deadline = readDeadline()
    return deadline != 0L && nowNanos - deadline >= 0
}
