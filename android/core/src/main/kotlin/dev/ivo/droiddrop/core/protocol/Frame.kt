package dev.ivo.droiddrop.core.protocol

import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.UUID

sealed interface Frame {
    /** UTF-8 JSON control payload, not yet parsed. */
    class Control(val json: ByteArray) : Frame

    /** [transferId] is the lowercase UUID string. */
    class Chunk(val transferId: String, val offset: Long, val data: ByteArray) : Frame
}

object FrameLimits {
    const val MAX_CONTROL_PAYLOAD = 524_288
    const val MAX_CHUNK_DATA = 1_048_576
    internal const val CHUNK_HEADER = 16 + 8
    const val MAX_FRAME_LENGTH = 1L + CHUNK_HEADER + MAX_CHUNK_DATA
}

private const val KIND_CONTROL = 0x01
private const val KIND_CHUNK = 0x02

object FrameCodec {
    fun encode(frame: Frame): ByteArray {
        val (kind, payload) = when (frame) {
            is Frame.Control -> KIND_CONTROL to frame.json
            is Frame.Chunk -> {
                val uuid = UUID.fromString(frame.transferId)
                KIND_CHUNK to ByteBuffer.allocate(FrameLimits.CHUNK_HEADER + frame.data.size)
                    .putLong(uuid.mostSignificantBits)
                    .putLong(uuid.leastSignificantBits)
                    .putLong(frame.offset)
                    .put(frame.data)
                    .array()
            }
        }
        return ByteBuffer.allocate(5 + payload.size)
            .putInt(1 + payload.size)
            .put(kind.toByte())
            .put(payload)
            .array()
    }
}

/** Blocking frame reader. Not thread-safe; use from one reader thread. */
class FrameReader(input: InputStream) {
    private val input = DataInputStream(input)

    /** Returns the next frame, or null on clean end-of-stream between frames. */
    fun read(): Frame? {
        val first = input.read()
        if (first < 0) return null
        val length = (first.toLong() shl 24) or
            (input.readUnsignedByte().toLong() shl 16) or
            (input.readUnsignedByte().toLong() shl 8) or
            input.readUnsignedByte().toLong()
        if (length == 0L) throw ProtocolException("empty_frame")
        if (length > FrameLimits.MAX_FRAME_LENGTH) throw ProtocolException("frame_too_large")
        val kind = input.readUnsignedByte()
        val payloadLength = (length - 1).toInt()
        when (kind) {
            KIND_CONTROL -> if (payloadLength > FrameLimits.MAX_CONTROL_PAYLOAD) throw ProtocolException("frame_too_large")
            KIND_CHUNK -> if (payloadLength < FrameLimits.CHUNK_HEADER + 1) throw ProtocolException("chunk_too_short")
            else -> throw ProtocolException("unknown_kind")
        }
        val payload = ByteArray(payloadLength)
        input.readFully(payload)
        if (kind == KIND_CONTROL) return Frame.Control(payload)
        val buffer = ByteBuffer.wrap(payload)
        val transferId = UUID(buffer.getLong(), buffer.getLong()).toString()
        val offset = buffer.getLong()
        return Frame.Chunk(transferId, offset, payload.copyOfRange(FrameLimits.CHUNK_HEADER, payload.size))
    }
}
