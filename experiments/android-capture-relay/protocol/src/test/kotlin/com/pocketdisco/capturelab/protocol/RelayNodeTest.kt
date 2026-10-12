package com.pocketdisco.capturelab.protocol

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayNodeTest {
    private val resources = mutableListOf<Closeable>()
    private val code = "0123456789abcdef0123456789abcdef"
    private val pcm = ByteArray(PcmFormat.FRAME_BYTES) { (it % 127).toByte() }

    @After
    fun closeResources() {
        resources.asReversed().forEach {
            try { it.close() } catch (_: IOException) { }
        }
    }

    @Test
    fun `one source reaches two direct peers without self playback`() {
        val local = CopyOnWriteArrayList<AudioFrame>()
        val first = CopyOnWriteArrayList<AudioFrame>()
        val second = CopyOnWriteArrayList<AudioFrame>()
        val a = node { local.add(it) }
        val b = node { first.add(it) }
        val c = node { second.add(it) }
        connect(a, b)
        connect(a, c)

        assertTrue(a.publish(pcm, 400))
        await { first.size == 1 && second.size == 1 && a.snapshot().sentFrames == 2L }
        assertEquals(0, local.size)
        assertEquals(a.sessionId, first.single().sessionId)
        assertEquals(0L, first.single().sequence)
        assertEquals(400L, first.single().capturedNanos)
        assertEquals(1, first.single().hops)
        assertArrayEquals(pcm, first.single().pcm)
        assertArrayEquals(pcm, second.single().pcm)
        assertEquals(1L, a.snapshot().publishedFrames)
        assertEquals(0L, b.snapshot().sentFrames)
    }

    @Test
    fun `A B C forwarding preserves stream identity and sequence`() {
        val receivedB = CopyOnWriteArrayList<AudioFrame>()
        val receivedC = CopyOnWriteArrayList<AudioFrame>()
        val a = node()
        val b = node { receivedB.add(it) }
        val c = node { receivedC.add(it) }
        connect(a, b)
        connect(b, c)
        repeat(10) { a.publish(pcm, it.toLong()) }
        await { receivedB.size == 10 && receivedC.size == 10 }
        assertEquals((0L..9L).toList(), receivedC.map { it.sequence })
        assertTrue(receivedC.all { it.sessionId == a.sessionId && it.hops == 2 })
        assertEquals(0L, a.snapshot().receivedFrames)
        assertEquals(10L, b.snapshot().sentFrames)
    }

    @Test
    fun `A B C admission drops a second source before forwarding`() {
        val receivedB = CopyOnWriteArrayList<AudioFrame>()
        val receivedC = CopyOnWriteArrayList<AudioFrame>()
        val a = node()
        val b = RelayNode(code, onFrame = { receivedB.add(it) }, acceptSession = { it == a.sessionId })
            .also(resources::add)
        val c = node { receivedC.add(it) }
        connect(a, b)
        connect(b, c)
        a.publish(pcm)
        await { receivedC.size == 1 }

        val secondSource = rawPeer(a)
        PcmWire.writeFrame(secondSource.getOutputStream(), AudioFrame(UUID.randomUUID(), 0, 1, 1, pcm))
        await { b.snapshot().droppedFrames == 1L }
        a.publish(pcm)
        await { receivedC.size == 2 && b.snapshot().sentFrames == 2L }

        assertTrue(receivedB.all { it.sessionId == a.sessionId })
        assertTrue(receivedC.all { it.sessionId == a.sessionId })
        assertEquals(listOf(0L, 1L), receivedC.map { it.sequence })
        assertEquals(2L, b.snapshot().receivedFrames)
        assertEquals(2L, b.snapshot().sentFrames)
        assertEquals(2L, c.snapshot().receivedFrames)
    }

    @Test
    fun `admission runs outside state lock after self and duplicate rejection`() {
        val observer = Executors.newSingleThreadExecutor()
        val calls = AtomicInteger()
        lateinit var relay: RelayNode
        relay = RelayNode(code, acceptSession = {
            observer.submit(Callable { relay.snapshot() }).get(2, TimeUnit.SECONDS)
            calls.incrementAndGet()
            true
        }).also(resources::add)
        try {
            val peer = rawPeer(relay)
            val session = UUID.randomUUID()
            PcmWire.writeFrame(peer.getOutputStream(), AudioFrame(session, 0, 1, 1, pcm))
            PcmWire.writeFrame(peer.getOutputStream(), AudioFrame(session, 0, 1, 1, pcm))
            PcmWire.writeFrame(peer.getOutputStream(), AudioFrame(relay.sessionId, 0, 1, 1, pcm))
            await { relay.snapshot().droppedFrames == 2L }
            assertEquals(1, calls.get())
            assertEquals(1L, relay.snapshot().receivedFrames)
        } finally {
            observer.shutdownNow()
        }
    }

    @Test
    fun `cycles never replay frames or return audio to its origin`() {
        val deliveredA = CopyOnWriteArrayList<AudioFrame>()
        val deliveredB = CopyOnWriteArrayList<AudioFrame>()
        val deliveredC = CopyOnWriteArrayList<AudioFrame>()
        val a = node { deliveredA.add(it) }
        val b = node { deliveredB.add(it) }
        val c = node { deliveredC.add(it) }
        connect(a, b)
        connect(b, c)
        connect(c, a)
        repeat(10) { a.publish(pcm) }
        await { deliveredB.size == 10 && deliveredC.size == 10 }
        await { a.snapshot().droppedFrames + b.snapshot().droppedFrames + c.snapshot().droppedFrames >= 20L }
        assertEquals(0, deliveredA.size)
        assertEquals(10, deliveredB.map { it.sequence }.toSet().size)
        assertEquals(10, deliveredC.map { it.sequence }.toSet().size)
        assertTrue(a.snapshot().sentFrames <= 20)
        assertTrue(b.snapshot().sentFrames <= 10)
        assertTrue(c.snapshot().sentFrames <= 10)
    }

    @Test
    fun `hop three is delivered but never forwarded`() {
        val deliveries = List(5) { CopyOnWriteArrayList<AudioFrame>() }
        val nodes = deliveries.map { frames -> node { frames.add(it) } }
        nodes.zipWithNext().forEach { (a, b) -> connect(a, b) }
        nodes[0].publish(pcm)
        await { deliveries[3].size == 1 }
        assertEquals(listOf(1, 2, 3), deliveries.subList(1, 4).map { it.single().hops })
        assertEquals(0L, nodes[3].snapshot().sentFrames)
        assertEquals(0L, nodes[4].snapshot().receivedFrames)
        assertTrue(deliveries[4].isEmpty())
    }

    @Test
    fun `duplicates old sequences and origin echoes are dropped`() {
        val delivered = CopyOnWriteArrayList<AudioFrame>()
        val relay = node { delivered.add(it) }
        val remote = rawPeer(relay)
        val session = UUID.randomUUID()
        for (sequence in listOf(3L, 2L, 3L, 4L)) {
            PcmWire.writeFrame(remote.getOutputStream(), AudioFrame(session, sequence, 1, 1, pcm))
        }
        PcmWire.writeFrame(remote.getOutputStream(), AudioFrame(relay.sessionId, 500, 1, 1, pcm))
        await { relay.snapshot().droppedFrames == 3L }
        assertEquals(listOf(3L, 4L), delivered.map { it.sequence })
        assertEquals(2L, relay.snapshot().receivedFrames)
    }

    @Test
    fun `mismatched join codes never create active peers`() {
        val a = node()
        val b = RelayNode("fedcba9876543210fedcba9876543210").also(resources::add)
        val (left, right) = socketPair()
        a.attach(left.getInputStream(), left.getOutputStream(), left::close)
        b.attach(right.getInputStream(), right.getOutputStream(), right::close)
        await { a.snapshot().rejectedPeers == 1L && b.snapshot().rejectedPeers == 1L }
        assertEquals(0, a.snapshot().peers)
        assertEquals(0, b.snapshot().peers)
        assertEquals("Join code mismatch", a.snapshot().lastError)
    }

    @Test
    fun `reflecting a handshake proof does not authenticate`() {
        val relay = node()
        val (local, remote) = socketPair()
        relay.attach(local.getInputStream(), local.getOutputStream(), local::close)
        val reader = DataInputStream(remote.getInputStream())
        val writer = DataOutputStream(remote.getOutputStream())
        val magic = reader.readInt()
        val version = reader.readUnsignedByte()
        reader.readLong()
        reader.readLong()
        val nonce = ByteArray(32).also(reader::readFully)
        val otherId = UUID.randomUUID()
        writer.writeInt(magic)
        writer.writeByte(version)
        writer.writeLong(otherId.mostSignificantBits)
        writer.writeLong(otherId.leastSignificantBits)
        writer.write(nonce)
        writer.flush()
        val proof = ByteArray(32).also(reader::readFully)
        writer.write(proof)
        writer.flush()
        await { relay.snapshot().rejectedPeers == 1L }
        assertEquals("Join code mismatch", relay.snapshot().lastError)
        assertEquals(0, relay.snapshot().peers)
    }

    @Test
    fun `a peer cannot open a second connection to the same node`() {
        val a = node()
        val b = node()
        connect(a, b)
        val (left, right) = socketPair()
        a.attach(left.getInputStream(), left.getOutputStream(), left::close)
        b.attach(right.getInputStream(), right.getOutputStream(), right::close)
        await { a.snapshot().rejectedPeers == 1L && b.snapshot().rejectedPeers == 1L }
        assertEquals(1, a.snapshot().peers)
        assertEquals(1, b.snapshot().peers)
    }

    @Test
    fun `pending and active peers share a limit and handshakes expire`() {
        val relay = RelayNode(code, handshakeTimeoutMillis = 250).also(resources::add)
        val active = node()
        connect(relay, active)
        repeat(2) {
            val (left, _) = socketPair()
            assertTrue(relay.attach(left.getInputStream(), left.getOutputStream(), left::close))
        }
        val (extra, _) = socketPair()
        assertFalse(relay.attach(extra.getInputStream(), extra.getOutputStream(), extra::close))
        assertTrue(extra.isClosed)
        assertEquals(1, relay.snapshot().peers)
        assertEquals(2, relay.snapshot().pendingPeers)
        await { relay.snapshot().pendingPeers == 0 }
        assertEquals(3L, relay.snapshot().rejectedPeers)
        assertEquals(1, relay.snapshot().peers)
    }

    @Test
    fun `a malformed frame closes only that peer`() {
        val relay = node()
        val remote = rawPeer(relay)
        val healthyFrames = CopyOnWriteArrayList<AudioFrame>()
        val healthy = node { healthyFrames.add(it) }
        connect(relay, healthy)
        DataOutputStream(remote.getOutputStream()).apply { writeInt(123); flush() }
        await { relay.snapshot().peers == 1 }
        assertEquals("Invalid frame magic", relay.snapshot().lastError)
        relay.publish(pcm)
        await { healthyFrames.size == 1 }
    }

    @Test
    fun `stream memory is bounded without evicting replay protection`() {
        val relay = node()
        val remote = rawPeer(relay)
        val output = remote.getOutputStream()
        repeat(RelayNode.MAX_SESSIONS) {
            PcmWire.writeFrame(output, AudioFrame(UUID.randomUUID(), 0, 1, 1, pcm))
        }
        await { relay.snapshot().peers == 0 }
        assertEquals(RelayNode.MAX_SESSIONS - 1L, relay.snapshot().receivedFrames)
        assertEquals("Stream limit reached", relay.snapshot().lastError)
    }

    @Test
    fun `closed link can be replaced without recreating the relay`() {
        val frames = CopyOnWriteArrayList<AudioFrame>()
        val relay = node { frames.add(it) }
        rawPeer(relay).close()
        await { relay.snapshot().peers == 0 }
        val next = rawPeer(relay)
        PcmWire.writeFrame(next.getOutputStream(), AudioFrame(UUID.randomUUID(), 0, 1, 1, pcm))
        await { frames.size == 1 }
        assertEquals(1, relay.snapshot().peers)
    }

    @Test
    fun `slow peer queue is bounded and publisher does not wait for writes`() {
        val relay = RelayNode(code, queueCapacity = 2).also(resources::add)
        val (local, remote) = socketPair()
        val gate = GateOutput(local.getOutputStream())
        relay.attach(local.getInputStream(), gate) { gate.release(); local.close() }
        PcmWire.authenticate(remote.getInputStream(), remote.getOutputStream(), UUID.randomUUID(), PcmWire.key(code))
        await { relay.snapshot().peers == 1 }
        gate.enabled = true
        relay.publish(pcm)
        assertTrue(gate.entered.await(2, TimeUnit.SECONDS))
        relay.publish(pcm)
        relay.publish(pcm)
        assertEquals(2, relay.snapshot().queuedFrames)
        val started = System.nanoTime()
        relay.publish(pcm)
        val millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue("publish took $millis ms", millis < 1_000)
        await { relay.snapshot().peers == 0 }
        assertEquals(0, relay.snapshot().peers)
        assertEquals(0, relay.snapshot().queuedFrames)
        assertEquals(1L, relay.snapshot().droppedFrames)
        assertEquals("Slow peer queue full", relay.snapshot().lastError)
    }

    @Test
    fun `a blocked transport close never runs on the capture publisher`() {
        val relay = RelayNode(code, queueCapacity = 1).also(resources::add)
        val (local, remote) = socketPair()
        val gate = GateOutput(local.getOutputStream())
        val closing = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        relay.attach(local.getInputStream(), gate) {
            closing.countDown()
            releaseClose.await(2, TimeUnit.SECONDS)
            gate.release()
            local.close()
        }
        try {
            PcmWire.authenticate(remote.getInputStream(), remote.getOutputStream(), UUID.randomUUID(), PcmWire.key(code))
            await { relay.snapshot().peers == 1 }
            gate.enabled = true
            relay.publish(pcm)
            assertTrue(gate.entered.await(2, TimeUnit.SECONDS))
            relay.publish(pcm)
            val started = System.nanoTime()
            relay.publish(pcm)
            val millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue("publish waited $millis ms for transport close", millis < 1_000)
            assertTrue(closing.await(2, TimeUnit.SECONDS))
            assertTrue(relay.publish(pcm))
            assertEquals(0, relay.snapshot().queuedFrames)
        } finally {
            releaseClose.countDown()
            gate.release()
        }
    }

    @Test
    fun `blocked write is closed by watchdog even without more capture frames`() {
        val relay = RelayNode(code, writeTimeoutMillis = 100).also(resources::add)
        val (local, remote) = socketPair()
        val gate = GateOutput(local.getOutputStream())
        relay.attach(local.getInputStream(), gate) { gate.release(); local.close() }
        PcmWire.authenticate(remote.getInputStream(), remote.getOutputStream(), UUID.randomUUID(), PcmWire.key(code))
        await { relay.snapshot().peers == 1 }
        gate.enabled = true
        relay.publish(pcm)
        assertTrue(gate.entered.await(2, TimeUnit.SECONDS))
        await { relay.snapshot().peers == 0 }
        assertEquals("Peer write timeout", relay.snapshot().lastError)
    }

    @Test
    fun `caller and receiver mutations cannot alter relayed PCM`() {
        val bFrames = CopyOnWriteArrayList<AudioFrame>()
        val cFrames = CopyOnWriteArrayList<AudioFrame>()
        val a = node()
        val b = node { frame -> frame.pcm.fill(0); bFrames.add(frame) }
        val c = node { cFrames.add(it) }
        connect(a, b)
        connect(b, c)
        val source = pcm.copyOf()
        a.publish(source)
        source.fill(0)
        await { bFrames.size == 1 && cFrames.size == 1 }
        assertArrayEquals(pcm, cFrames.single().pcm)
    }

    @Test
    fun `close is idempotent and rejects work and new transports`() {
        val relay = node()
        val other = node()
        connect(relay, other)
        relay.close()
        relay.close()
        await { other.snapshot().peers == 0 }
        assertFalse(relay.publish(pcm))
        val closed = AtomicInteger()
        val (left, _) = socketPair()
        assertFalse(relay.attach(left.getInputStream(), left.getOutputStream()) { left.close(); closed.incrementAndGet() })
        assertEquals(1, closed.get())
        assertEquals(0, relay.snapshot().queuedFrames)
        assertEquals(0, relay.snapshot().pendingPeers)
    }

    @Test
    fun `repeated close during peer startup leaves no blocked workers`() {
        val initialWorkers = relayWorkers()
        repeat(40) { index ->
            val a = node()
            val b = node()
            if (index % 2 == 0) {
                connect(a, b)
            } else {
                val (left, right) = socketPair()
                a.attach(left.getInputStream(), left.getOutputStream(), left::close)
                b.attach(right.getInputStream(), right.getOutputStream(), right::close)
            }
            a.close()
            b.close()
        }
        await { (relayWorkers() - initialWorkers).isEmpty() }
    }

    @Test
    fun `closing from the authenticated event does not start a waiting writer`() {
        val initialWorkers = relayWorkers()
        val closed = CountDownLatch(1)
        lateinit var relay: RelayNode
        relay = RelayNode(code, onEvent = { event ->
            if (event == "Peer connected") {
                relay.close()
                closed.countDown()
            }
        }).also(resources::add)
        val (local, remote) = socketPair()
        relay.attach(local.getInputStream(), local.getOutputStream(), local::close)
        PcmWire.authenticate(remote.getInputStream(), remote.getOutputStream(), UUID.randomUUID(), PcmWire.key(code))
        assertTrue(closed.await(2, TimeUnit.SECONDS))
        assertEquals(0, relay.snapshot().peers)
        await { (relayWorkers() - initialWorkers).isEmpty() }
    }

    @Test
    fun `capture input and configuration are validated`() {
        val relay = node()
        assertThrows(IllegalArgumentException::class.java) { relay.publish(ByteArray(10)) }
        assertThrows(IllegalArgumentException::class.java) { relay.publish(pcm, -1) }
        assertThrows(IllegalArgumentException::class.java) { RelayNode(code, queueCapacity = 26) }
        assertThrows(IllegalArgumentException::class.java) { RelayNode(code, handshakeTimeoutMillis = 0) }
        assertThrows(IllegalArgumentException::class.java) { RelayNode(code, writeTimeoutMillis = 30_001) }
    }

    private fun node(onFrame: (AudioFrame) -> Unit = {}): RelayNode =
        RelayNode(code, onFrame).also(resources::add)

    private fun connect(a: RelayNode, b: RelayNode) {
        val countA = a.snapshot().peers
        val countB = b.snapshot().peers
        val (left, right) = socketPair()
        assertTrue(a.attach(left.getInputStream(), left.getOutputStream(), left::close))
        assertTrue(b.attach(right.getInputStream(), right.getOutputStream(), right::close))
        await { a.snapshot().peers == countA + 1 && b.snapshot().peers == countB + 1 }
    }

    private fun rawPeer(node: RelayNode): Socket {
        val previous = node.snapshot().peers
        val (local, remote) = socketPair()
        assertTrue(node.attach(local.getInputStream(), local.getOutputStream(), local::close))
        val remoteNode = PcmWire.authenticate(
            remote.getInputStream(), remote.getOutputStream(), UUID.randomUUID(), PcmWire.key(code),
        )
        assertEquals(node.nodeId, remoteNode)
        await { node.snapshot().peers == previous + 1 }
        return remote
    }

    private fun socketPair(): Pair<Socket, Socket> {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 2_000
            val client = Socket(InetAddress.getLoopbackAddress(), server.localPort).also(resources::add)
            client.soTimeout = 2_000
            client.tcpNoDelay = true
            val accepted = server.accept().also(resources::add)
            accepted.tcpNoDelay = true
            return accepted to client
        }
    }

    private fun await(check: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!check() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("Condition timed out", check())
    }

    private fun relayWorkers(): Set<Thread> = Thread.getAllStackTraces().keys.filter {
        it.isAlive && it.name in setOf("capture-relay-reader", "capture-relay-writer")
    }.toSet()

    private class GateOutput(private val target: OutputStream) : OutputStream() {
        @Volatile var enabled = false
        val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)

        override fun write(value: Int) {
            waitForGate()
            target.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            waitForGate()
            target.write(bytes, offset, length)
        }

        override fun flush() = target.flush()

        fun release() { released.countDown() }

        private fun waitForGate() {
            if (!enabled) return
            entered.countDown()
            try {
                if (!released.await(3, TimeUnit.SECONDS)) throw IOException("Test gate timeout")
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Test gate interrupted")
            }
        }
    }
}
