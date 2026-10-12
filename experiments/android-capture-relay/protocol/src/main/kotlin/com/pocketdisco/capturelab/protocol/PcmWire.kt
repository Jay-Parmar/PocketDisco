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
}

data class AudioFrame(
    val sessionId: UUID,
    val sequence: Long,
    val capturedNanos: Long,
    val hops: Int,
    val pcm: ByteArray,
)

internal object PcmWire {
    private const val HELLO_MAGIC = 0x50444348
    private const val FRAME_MAGIC = 0x50444346
    private const val VERSION = 1
    private const val NONCE_BYTES = 32
    private const val PROOF_BYTES = 32
    private const val FRAME_HEADER_BYTES = 47
    private val proofContext = "PocketDisco capture relay v1".toByteArray(Charsets.US_ASCII)

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
        val reader = DataInputStream(input)
        checkWire(reader.readInt() == FRAME_MAGIC, "Invalid frame magic")
        checkWire(reader.readUnsignedByte() == VERSION, "Unsupported frame version")
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
        return AudioFrame(sessionId, sequence, capturedNanos, hops, pcm)
    }

    private fun validate(sequence: Long, capturedNanos: Long, hops: Int, size: Int) {
        checkWire(sequence >= 0, "Invalid frame sequence")
        checkWire(capturedNanos >= 0, "Invalid frame timestamp")
        checkWire(hops in 1..PcmFormat.MAX_HOPS, "Invalid hop count")
        checkWire(size == PcmFormat.FRAME_BYTES, "Invalid PCM length")
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
