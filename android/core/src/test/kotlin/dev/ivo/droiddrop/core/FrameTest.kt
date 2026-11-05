package dev.ivo.droiddrop.core

import dev.ivo.droiddrop.core.protocol.Frame
import dev.ivo.droiddrop.core.protocol.FrameCodec
import dev.ivo.droiddrop.core.protocol.FrameLimits
import dev.ivo.droiddrop.core.protocol.FrameReader
import dev.ivo.droiddrop.core.protocol.ProtocolException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream

class FrameTest {
    private val fixture = Fixtures.load("frames")

    @Test
    fun limitsMatchFixture() {
        assertEquals(fixture.long("maxControlPayload"), FrameLimits.MAX_CONTROL_PAYLOAD.toLong())
        assertEquals(fixture.long("maxChunkData"), FrameLimits.MAX_CHUNK_DATA.toLong())
        assertEquals(fixture.long("maxFrameLength"), FrameLimits.MAX_FRAME_LENGTH)
    }

    @Test
    fun decodesValidFrames() {
        for (entry in fixture.list("valid")) {
            val name = entry.str("name")
            val frame = FrameReader(ByteArrayInputStream(entry.str("hex").hexToBytes())).read()
            when (entry.str("kind")) {
                "control" -> {
                    frame as Frame.Control
                    assertEquals(entry.getValue("json"), Json.parseToJsonElement(frame.json.decodeToString()), name)
                }
                "chunk" -> {
                    frame as Frame.Chunk
                    assertEquals(entry.str("transferId"), frame.transferId, name)
                    assertEquals(entry.long("offset"), frame.offset, name)
                    assertEquals(entry.str("dataHex"), frame.data.toHex(), name)
                }
            }
        }
    }

    @Test
    fun encodesValidFramesByteExact() {
        for (entry in fixture.list("valid")) {
            val frame = FrameReader(ByteArrayInputStream(entry.str("hex").hexToBytes())).read()!!
            assertEquals(entry.str("hex"), FrameCodec.encode(frame).toHex(), entry.str("name"))
        }
    }

    @Test
    fun decodesFramesDeliveredOneByteAtATime() {
        val entries = fixture.list("valid")
        val stream = entries.map { it.str("hex").hexToBytes() }.reduce(ByteArray::plus)
        val reader = FrameReader(OneByteAtATime(stream))
        repeat(entries.size) { reader.read()!! }
        assertNull(reader.read())
    }

    @Test
    fun cleanEofReturnsNullButTruncatedFrameThrows() {
        assertNull(FrameReader(ByteArrayInputStream(ByteArray(0))).read())
        assertThrows<EOFException> { FrameReader(ByteArrayInputStream(byteArrayOf(0, 0, 0))).read() }
    }

    @Test
    fun rejectsInvalidFrames() {
        for (entry in fixture.list("invalid")) {
            val error = assertThrows<ProtocolException>(entry.str("name")) {
                FrameReader(ByteArrayInputStream(entry.str("hex").hexToBytes())).read()
            }
            assertEquals(entry.str("error"), error.code, entry.str("name"))
        }
    }

    private class OneByteAtATime(private val bytes: ByteArray) : InputStream() {
        private var index = 0
        override fun read(): Int = if (index < bytes.size) bytes[index++].toInt() and 0xff else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val next = read()
            if (next < 0) return -1
            b[off] = next.toByte()
            return 1
        }
    }
}
