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
)

class RelayNode(
    joinCode: String,
    private val onFrame: (AudioFrame) -> Unit = {},
    private val onEvent: (String) -> Unit = {},
    private val handshakeTimeoutMillis: Long = 5_000,
    private val writeTimeoutMillis: Long = 5_000,
    private val queueCapacity: Int = 25,
    private val acceptSession: (UUID) -> Boolean = { true },
) : Closeable {
    val nodeId: UUID = UUID.randomUUID()
    val sessionId: UUID = UUID.randomUUID()

    private val key = PcmWire.key(joinCode)
    private val lock = Any()
    private val receiveLock = Any()
    private val publishLock = Any()
    private val peers = LinkedHashSet<Peer>()
    private val highestSequence = linkedMapOf(sessionId to -1L)
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

    init {
        require(handshakeTimeoutMillis in 1..30_000)
        require(writeTimeoutMillis in 1..30_000)
        require(queueCapacity in 1..25)
        watchdog.scheduleAtFixedRate({ checkDeadlines() }, 50, 50, TimeUnit.MILLISECONDS)
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

    fun publish(pcm: ByteArray, capturedNanos: Long = System.nanoTime().coerceAtLeast(0)): Boolean {
        return synchronized(publishLock) {
            require(pcm.size == PcmFormat.FRAME_BYTES)
            require(capturedNanos >= 0)
            val frame = synchronized(lock) {
                if (closed || nextSequence == Long.MAX_VALUE) return false
                val frame = AudioFrame(sessionId, nextSequence++, capturedNanos, 0, pcm.copyOf())
                highestSequence[sessionId] = frame.sequence
                publishedFrames++
                frame
            }
            forward(frame, null)
            true
        }
    }

    fun snapshot(): RelaySnapshot = synchronized(lock) {
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
        )
    }

    override fun close() {
        val connected = synchronized(lock) {
            if (closed) return
            closed = true
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
            }
            forward(frame, source)
            onFrame(frame.copy(pcm = frame.pcm.copyOf()))
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
            if (!peer.queue.offer(outgoing)) peer.markSlow()
        }
    }

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
        val now = System.nanoTime()
        val expired = synchronized(lock) {
            peers.filter { it.overflowed.get() || it.deadlineNanos != 0L && now - it.deadlineNanos >= 0 }
        }
        expired.forEach {
            it.fail(when {
                it.overflowed.get() -> "Slow peer queue full"
                it.authenticated -> "Peer write timeout"
                else -> "Peer handshake timeout"
            })
        }
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
        val stopped = AtomicBoolean(false)
        val overflowed = AtomicBoolean(false)
        @Volatile var authenticated = false
        @Volatile var remoteId: UUID? = null
        @Volatile var deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(handshakeTimeoutMillis)
        @Volatile private var readerThread: Thread? = null
        @Volatile private var writerThread: Thread? = null

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
                    while (!stopped.get()) receive(PcmWire.readFrame(input), this)
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
                    val frame = queue.take()
                    deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(writeTimeoutMillis)
                    PcmWire.writeFrame(output, frame)
                    deadlineNanos = 0
                    synchronized(lock) { sentFrames++ }
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
            if (!stopped.compareAndSet(false, true)) return
            synchronized(lock) {
                peers.remove(this)
                if (!authenticated) rejectedPeers++
                lastError = message
            }
            closeStreams()
            event(message)
        }

        fun shutdown() {
            if (!stopped.compareAndSet(false, true)) return
            synchronized(lock) { peers.remove(this) }
            closeStreams()
        }

        private fun closeStreams() {
            queue.clear()
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
    }
}
