package com.pocketdisco.capturelab.protocol

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object PcmFormat {
    const val SAMPLE_RATE = 16_000
    const val CHANNELS = 1
    const val BYTES_PER_SAMPLE = 2
    const val FRAME_MILLIS = 20
    const val FRAME_BYTES = 640
    const val MAX_HOPS = 3
    const val FRAME_NANOS = 20_000_000L
    const val PLAYOUT_DELAY_NANOS = 1_500_000_000L
}

data class AudioFrame(
    val sessionId: UUID,
    val sequence: Long,
    val capturedNanos: Long,
    val hops: Int,
    val pcm: ByteArray,
)

internal sealed interface RelayPacket
internal data class PcmPacket(val frame: AudioFrame) : RelayPacket
internal data class ClockRequest(
    val sessionId: UUID,
    val requestId: UUID,
    val sentNanos: Long,
    val hops: Int,
) : RelayPacket
internal data class ClockReply(
    val sessionId: UUID,
    val requestId: UUID,
    val sentNanos: Long,
    val rootReceivedNanos: Long,
    val rootSentNanos: Long,
    val hops: Int,
) : RelayPacket

internal object PcmWire {
    private const val HELLO_MAGIC = 0x50444348
    private const val FRAME_MAGIC = 0x50444346
    private const val REQUEST_MAGIC = 0x50444351
    private const val REPLY_MAGIC = 0x50444352
    private const val VERSION = 2
    private const val NONCE_BYTES = 32
    private const val PROOF_BYTES = 32
    private const val FRAME_HEADER_BYTES = 47
    private val proofContext = "PocketDisco capture relay v2".toByteArray(Charsets.US_ASCII)

    fun key(joinCode: String): ByteArray {
        require(joinCode.length == 32 && joinCode.all { it.digitToIntOrNull(16) != null }) {
            "Use a 32-character hexadecimal join code"
        }
        return ByteArray(16) { index ->
            joinCode.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    fun authenticate(
        input: InputStream,
        output: OutputStream,
        nodeId: UUID,
        key: ByteArray,
    ): UUID {
        val reader = DataInputStream(input)
        val writer = DataOutputStream(output)
        val local = Hello(nodeId, ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) })
        writer.writeInt(HELLO_MAGIC)
        writer.writeByte(VERSION)
        writer.writeUuid(local.nodeId)
        writer.write(local.nonce)
        writer.flush()

        checkWire(reader.readInt() == HELLO_MAGIC, "Invalid handshake")
        checkWire(reader.readUnsignedByte() == VERSION, "Unsupported handshake version")
        val remoteId = reader.readUuid()
        checkWire(remoteId != nodeId, "Self connection")
        val remote = Hello(remoteId, ByteArray(NONCE_BYTES).also(reader::readFully))
        writer.write(proof(key, local, remote))
        writer.flush()

        val remoteProof = ByteArray(PROOF_BYTES).also(reader::readFully)
        checkWire(MessageDigest.isEqual(proof(key, remote, local), remoteProof), "Join code mismatch")
        return remoteId
    }

    fun writeFrame(output: OutputStream, frame: AudioFrame) {
        validate(frame.sequence, frame.capturedNanos, frame.hops, frame.pcm.size)
        val bytes = ByteBuffer.allocate(FRAME_HEADER_BYTES + PcmFormat.FRAME_BYTES).apply {
            putInt(FRAME_MAGIC)
            put(VERSION.toByte())
            putInt(PcmFormat.SAMPLE_RATE)
            put(PcmFormat.CHANNELS.toByte())
            put(PcmFormat.BYTES_PER_SAMPLE.toByte())
            put(PcmFormat.FRAME_MILLIS.toByte())
            putLong(frame.sessionId.mostSignificantBits)
            putLong(frame.sessionId.leastSignificantBits)
            putLong(frame.sequence)
            putLong(frame.capturedNanos)
            put(frame.hops.toByte())
            putShort(frame.pcm.size.toShort())
            put(frame.pcm)
        }
        output.write(bytes.array())
        output.flush()
    }

    fun readFrame(input: InputStream): AudioFrame {
        return (readPacket(input) as? PcmPacket)?.frame ?: throw IOException("Expected PCM frame")
    }

    fun writePacket(output: OutputStream, packet: RelayPacket) {
        if (packet is PcmPacket) {
            writeFrame(output, packet.frame)
            return
        }
        val bytes = when (packet) {
            is ClockRequest -> {
                validateClock(packet.sentNanos, packet.hops)
                ByteBuffer.allocate(46).apply {
                    putInt(REQUEST_MAGIC)
                    put(VERSION.toByte())
                    putUuid(packet.sessionId)
                    putUuid(packet.requestId)
                    putLong(packet.sentNanos)
                    put(packet.hops.toByte())
                }
            }
            is ClockReply -> {
                validateClock(packet.sentNanos, packet.hops)
                checkWire(packet.rootReceivedNanos >= 0 && packet.rootSentNanos >= packet.rootReceivedNanos,
                    "Invalid root timestamp")
                ByteBuffer.allocate(62).apply {
                    putInt(REPLY_MAGIC)
                    put(VERSION.toByte())
                    putUuid(packet.sessionId)
                    putUuid(packet.requestId)
                    putLong(packet.sentNanos)
                    putLong(packet.rootReceivedNanos)
                    putLong(packet.rootSentNanos)
                    put(packet.hops.toByte())
                }
            }
            is PcmPacket -> error("PCM is written above")
        }
        output.write(bytes.array())
        output.flush()
    }

    fun readPacket(input: InputStream): RelayPacket {
        val reader = DataInputStream(input)
        val magic = reader.readInt()
        checkWire(magic == FRAME_MAGIC || magic == REQUEST_MAGIC || magic == REPLY_MAGIC, "Invalid frame magic")
        checkWire(reader.readUnsignedByte() == VERSION, "Unsupported frame version")
        if (magic != FRAME_MAGIC) {
            val sessionId = reader.readUuid()
            val requestId = reader.readUuid()
            val sentNanos = reader.readLong()
            if (magic == REQUEST_MAGIC) {
                val hops = reader.readUnsignedByte()
                validateClock(sentNanos, hops)
                return ClockRequest(sessionId, requestId, sentNanos, hops)
            }
            val rootReceivedNanos = reader.readLong()
            val rootSentNanos = reader.readLong()
            val hops = reader.readUnsignedByte()
            validateClock(sentNanos, hops)
            checkWire(rootReceivedNanos >= 0 && rootSentNanos >= rootReceivedNanos,
                "Invalid root timestamp")
            return ClockReply(sessionId, requestId, sentNanos, rootReceivedNanos, rootSentNanos, hops)
        }
        checkWire(reader.readInt() == PcmFormat.SAMPLE_RATE, "Unsupported sample rate")
        checkWire(reader.readUnsignedByte() == PcmFormat.CHANNELS, "Unsupported channel count")
        checkWire(reader.readUnsignedByte() == PcmFormat.BYTES_PER_SAMPLE, "Unsupported sample size")
        checkWire(reader.readUnsignedByte() == PcmFormat.FRAME_MILLIS, "Unsupported frame duration")
        val sessionId = reader.readUuid()
        val sequence = reader.readLong()
        val capturedNanos = reader.readLong()
        val hops = reader.readUnsignedByte()
        val size = reader.readUnsignedShort()
        validate(sequence, capturedNanos, hops, size)
        val pcm = ByteArray(PcmFormat.FRAME_BYTES).also(reader::readFully)
        return PcmPacket(AudioFrame(sessionId, sequence, capturedNanos, hops, pcm))
    }

    private fun validate(sequence: Long, capturedNanos: Long, hops: Int, size: Int) {
        checkWire(sequence >= 0, "Invalid frame sequence")
        checkWire(capturedNanos in 0..(Long.MAX_VALUE - PcmFormat.PLAYOUT_DELAY_NANOS), "Invalid frame timestamp")
        checkWire(hops in 1..PcmFormat.MAX_HOPS, "Invalid hop count")
        checkWire(size == PcmFormat.FRAME_BYTES, "Invalid PCM length")
    }

    private fun validateClock(sentNanos: Long, hops: Int) {
        checkWire(sentNanos >= 0, "Invalid clock timestamp")
        checkWire(hops in 1..PcmFormat.MAX_HOPS, "Invalid clock hop count")
    }

    private fun ByteBuffer.putUuid(value: UUID) {
        putLong(value.mostSignificantBits)
        putLong(value.leastSignificantBits)
    }

    private fun proof(key: ByteArray, sender: Hello, receiver: Hello): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update(proofContext)
        for (hello in listOf(sender, receiver)) {
            mac.update(uuidBytes(hello.nodeId))
            mac.update(hello.nonce)
        }
        return mac.doFinal()
    }

    private fun uuidBytes(value: UUID): ByteArray =
        ByteBuffer.allocate(16).putLong(value.mostSignificantBits)
            .putLong(value.leastSignificantBits).array()

    private fun DataOutputStream.writeUuid(value: UUID) {
        writeLong(value.mostSignificantBits)
        writeLong(value.leastSignificantBits)
    }

    private fun DataInputStream.readUuid() = UUID(readLong(), readLong())

    private fun checkWire(valid: Boolean, message: String) {
        if (!valid) throw IOException(message)
    }

    private data class Hello(val nodeId: UUID, val nonce: ByteArray)
}
