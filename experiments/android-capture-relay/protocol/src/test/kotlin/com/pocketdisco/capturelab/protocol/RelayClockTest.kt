package com.pocketdisco.capturelab.protocol

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayClockTest {
    private val resources = mutableListOf<Closeable>()
    private val code = "0123456789abcdef0123456789abcdef"
    private val pcm = ByteArray(PcmFormat.FRAME_BYTES) { (it % 127).toByte() }

    @After
    fun closeResources() {
        resources.asReversed().forEach { runCatching { it.close() } }
    }

    @Test
    fun `source timeline follows sample cadence rather than publish jitter`() {
        val frames = CopyOnWriteArrayList<AudioFrame>()
        val a = node()
        val b = node(onFrame = { frames.add(it) })
        connect(a, b)
        a.publish(pcm, 1_000_000_000)
        a.publish(pcm, 1_027_000_000)
        a.publish(pcm, 1_030_000_000)
        await { frames.size == 3 }
        assertEquals(listOf(1_000_000_000L, 1_020_000_000L, 1_040_000_000L), frames.map { it.capturedNanos })
        assertFalse(node().publish(pcm, Long.MAX_VALUE))
        val last = node()
        assertTrue(last.publish(pcm, Long.MAX_VALUE - PcmFormat.PLAYOUT_DELAY_NANOS))
        assertFalse(last.publish(pcm))
    }

    @Test
    fun `three hops share one root deadline despite unrelated receiver clocks`() {
        val deliveries = List(3) { CopyOnWriteArrayList<TimedAudioFrame>() }
        val offsets = listOf(2_000_000_000L, -3_000_000_000L, 70_000_000_000L)
        val source = node()
        val receivers = offsets.mapIndexed { index, offset ->
            node(onTimedFrame = { deliveries[index].add(it) }, clockNanos = { System.nanoTime() + offset })
        }
        (listOf(source) + receivers).zipWithNext().forEach { (a, b) -> connect(a, b) }
        source.publish(pcm)
        await { receivers.all { it.snapshot().clockReady } }
        source.publish(pcm)
        await { deliveries.all { it.size == 1 } }
        deliveries.forEachIndexed { index, frames ->
            val timed = frames.single()
            val rootDeadline = timed.frame.capturedNanos + PcmFormat.PLAYOUT_DELAY_NANOS
            assertTrue(abs(timed.playAtNanos - offsets[index] - rootDeadline) <= timed.uncertaintyNanos + 1)
            assertEquals(index + 1, timed.frame.hops)
            assertArrayEquals(pcm, timed.frame.pcm)
            assertEquals(1L, receivers[index].snapshot().timedFrames)
            assertEquals(1L, receivers[index].snapshot().unscheduledFrames)
        }
        assertEquals(deliveries[0].single().frame.capturedNanos, deliveries[2].single().frame.capturedNanos)
    }

    @Test
    fun `raw callbacks cannot change scheduled PCM`() {
        val timed = CopyOnWriteArrayList<TimedAudioFrame>()
        val source = node()
        val receiver = node(onFrame = { it.pcm.fill(0) }, onTimedFrame = { timed.add(it) })
        connect(source, receiver)
        source.publish(pcm)
        await { receiver.snapshot().clockReady }
        source.publish(pcm)
        await { timed.size == 1 }
        assertArrayEquals(pcm, timed.single().frame.pcm)
    }

    @Test
    fun `reconnecting the same source discards the old mapping and warms a fresh one`() {
        val timed = CopyOnWriteArrayList<TimedAudioFrame>()
        val source = node()
        val receiver = node(onTimedFrame = { timed.add(it) })
        val first = connect(source, receiver)
        source.publish(pcm)
        await { receiver.snapshot().clockReady }
        first.close()
        await { receiver.snapshot().peers == 0 && source.snapshot().peers == 0 }
        assertFalse(receiver.snapshot().clockReady)
        connect(source, receiver)
        source.publish(pcm)
        await { receiver.snapshot().receivedFrames == 2L }
        assertFalse(receiver.snapshot().clockReady)
        await { receiver.snapshot().clockReady }
        source.publish(pcm)
        await { timed.size == 1 }
        assertEquals(source.sessionId, timed.single().frame.sessionId)
        assertEquals(2L, timed.single().frame.sequence)
    }

    @Test
    fun `stale clocks continue forwarding raw frames without timed output`() {
        val jump = AtomicLong()
        val timed = CopyOnWriteArrayList<TimedAudioFrame>()
        val raw = CopyOnWriteArrayList<AudioFrame>()
        val source = node()
        val relay = node(onTimedFrame = { timed.add(it) }, clockNanos = { System.nanoTime() + jump.get() })
        val receiver = node(onFrame = { raw.add(it) })
        connect(source, relay)
        connect(relay, receiver)
        source.publish(pcm)
        await { relay.snapshot().clockReady && receiver.snapshot().clockReady }
        jump.set(6_000_000_000)
        assertFalse(relay.snapshot().clockReady)
        source.publish(pcm)
        await { raw.size == 2 }
        assertTrue(timed.isEmpty())
        assertEquals(2L, relay.snapshot().unscheduledFrames)
        assertTrue(receiver.snapshot().clockReady)
    }

    @Test
    fun `a late join gets a future frame without waiting for sequence zero`() {
        val source = node()
        repeat(20) { source.publish(pcm) }
        val timed = CopyOnWriteArrayList<TimedAudioFrame>()
        val receiver = node(onTimedFrame = { timed.add(it) })
        connect(source, receiver)
        source.publish(pcm)
        await { receiver.snapshot().clockReady }
        source.publish(pcm)
        await { timed.size == 1 }
        assertEquals(21L, timed.single().frame.sequence)
        assertTrue(timed.single().playAtNanos > System.nanoTime())
    }

    @Test
    fun `a second source cannot replace the chosen source clock`() {
        val timed = CopyOnWriteArrayList<TimedAudioFrame>()
        val source = node()
        val receiver = node(onTimedFrame = { timed.add(it) })
        connect(source, receiver)
        source.publish(pcm)
        await { receiver.snapshot().clockReady }
        val other = rawPeer(receiver)
        PcmWire.writeFrame(other.getOutputStream(), AudioFrame(UUID.randomUUID(), 0, System.nanoTime(), 1, pcm))
        await { receiver.snapshot().receivedFrames == 2L }
        assertTrue(timed.isEmpty())
        source.publish(pcm)
        await { timed.size == 1 }
        assertEquals(source.sessionId, timed.single().frame.sessionId)
    }

    @Test
    fun `wrong peer session nonce and replay cannot establish a clock`() {
        val receiver = node()
        val source = rawPeer(receiver)
        val other = rawPeer(receiver)
        val session = UUID.randomUUID()
        PcmWire.writeFrame(source.getOutputStream(), AudioFrame(session, 0, System.nanoTime(), 1, pcm))
        val request = PcmWire.readPacket(source.getInputStream()) as ClockRequest
        val reply = reply(request)
        PcmWire.writePacket(other.getOutputStream(), reply)
        PcmWire.writePacket(source.getOutputStream(), reply.copy(sessionId = UUID.randomUUID()))
        PcmWire.writePacket(source.getOutputStream(), reply.copy(requestId = UUID.randomUUID()))
        PcmWire.writePacket(source.getOutputStream(), reply.copy(sentNanos = request.sentNanos + 1))
        PcmWire.writePacket(source.getOutputStream(), reply)
        PcmWire.writePacket(source.getOutputStream(), reply)
        PcmWire.writePacket(source.getOutputStream(), reply)
        PcmWire.writeFrame(source.getOutputStream(), AudioFrame(session, 1, System.nanoTime(), 1, pcm))
        await { receiver.snapshot().receivedFrames == 2L }
        assertFalse(receiver.snapshot().clockReady)
        repeat(2) {
            val next = PcmWire.readPacket(source.getInputStream()) as ClockRequest
            PcmWire.writePacket(source.getOutputStream(), reply(next))
        }
        await { receiver.snapshot().clockReady }
    }

    @Test
    fun `a clock request does not escape its hop cap or create another source route`() {
        val relay = node()
        val source = rawPeer(relay)
        val child = rawPeer(relay)
        val session = UUID.randomUUID()
        PcmWire.writeFrame(source.getOutputStream(), AudioFrame(session, 0, System.nanoTime(), 1, pcm))
        val localProbe = PcmWire.readPacket(source.getInputStream()) as ClockRequest
        PcmWire.writePacket(source.getOutputStream(), reply(localProbe))
        val request = ClockRequest(session, UUID.randomUUID(), System.nanoTime(), 3)
        PcmWire.writePacket(child.getOutputStream(), request)
        PcmWire.writePacket(child.getOutputStream(), request.copy(sessionId = UUID.randomUUID(), hops = 1))
        PcmWire.writeFrame(child.getOutputStream(), AudioFrame(UUID.randomUUID(), 0, System.nanoTime(), 3, pcm))
        await { relay.snapshot().receivedFrames == 2L }
        val next = PcmWire.readPacket(source.getInputStream()) as ClockRequest
        assertTrue(next.requestId != request.requestId)
        assertEquals(session, next.sessionId)
        assertEquals(1, next.hops)
    }

    @Test
    fun `old handshake version is rejected before authentication`() {
        val receiver = node()
        val (local, remote) = socketPair()
        receiver.attach(local.getInputStream(), local.getOutputStream(), local::close)
        val reader = DataInputStream(remote.getInputStream())
        val magic = reader.readInt()
        assertEquals(2, reader.readUnsignedByte())
        DataOutputStream(remote.getOutputStream()).apply {
            writeInt(magic)
            writeByte(1)
            flush()
        }
        await { receiver.snapshot().rejectedPeers == 1L }
        assertEquals("Unsupported handshake version", receiver.snapshot().lastError)
    }

    private fun reply(request: ClockRequest): ClockReply {
        val now = System.nanoTime()
        return ClockReply(request.sessionId, request.requestId, request.sentNanos, now, now, request.hops)
    }

    private fun node(
        onFrame: (AudioFrame) -> Unit = {},
        onTimedFrame: (TimedAudioFrame) -> Unit = {},
        clockNanos: () -> Long = System::nanoTime,
    ): RelayNode = RelayNode(code, onFrame = onFrame, onTimedFrame = onTimedFrame, clockNanos = clockNanos)
        .also(resources::add)

    private fun connect(first: RelayNode, second: RelayNode): Socket {
        val countFirst = first.snapshot().peers
        val countSecond = second.snapshot().peers
        val (local, remote) = socketPair()
        assertTrue(first.attach(local.getInputStream(), local.getOutputStream(), local::close))
        assertTrue(second.attach(remote.getInputStream(), remote.getOutputStream(), remote::close))
        await { first.snapshot().peers == countFirst + 1 && second.snapshot().peers == countSecond + 1 }
        return local
    }

    private fun rawPeer(node: RelayNode): Socket {
        val count = node.snapshot().peers
        val (local, remote) = socketPair()
        assertTrue(node.attach(local.getInputStream(), local.getOutputStream(), local::close))
        PcmWire.authenticate(remote.getInputStream(), remote.getOutputStream(), UUID.randomUUID(), PcmWire.key(code))
        await { node.snapshot().peers == count + 1 }
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
}
