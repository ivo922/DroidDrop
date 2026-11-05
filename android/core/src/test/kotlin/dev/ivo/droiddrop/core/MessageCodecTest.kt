package dev.ivo.droiddrop.core

import dev.ivo.droiddrop.core.pairing.Base64Url
import dev.ivo.droiddrop.core.protocol.Message
import dev.ivo.droiddrop.core.protocol.MessageCodec
import dev.ivo.droiddrop.core.protocol.ProtocolException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MessageCodecTest {
    private val fixture = Fixtures.load("messages")

    private fun roundTrip(json: String) =
        Json.parseToJsonElement(MessageCodec.encode(MessageCodec.decode(json.toByteArray())).decodeToString())

    @Test
    fun validMessagesRoundTrip() {
        for (entry in fixture.list("valid")) {
            val json = entry.getValue("json")
            assertEquals(json, roundTrip(json.toString()), entry.str("name"))
        }
    }

    @Test
    fun unknownMembersAreDropped() {
        for (entry in fixture.list("normalized")) {
            assertEquals(entry.getValue("output"), roundTrip(entry.getValue("input").toString()), entry.str("name"))
        }
    }

    @Test
    fun unknownTypesDecodeAsUnknown() {
        for (entry in fixture.list("unknown")) {
            assertEquals(Message.Unknown(entry.str("type")), MessageCodec.decode(entry.getValue("json").toString().toByteArray()))
        }
    }

    @Test
    fun invalidMessagesAreRejected() {
        for (entry in fixture.list("invalid")) {
            assertThrows<ProtocolException>(entry.str("name")) { MessageCodec.decode(entry.str("raw").toByteArray()) }
        }
        for (entry in fixture.list("invalidHex")) {
            assertThrows<ProtocolException>(entry.str("name")) { MessageCodec.decode(entry.str("hex").hexToBytes()) }
        }
    }

    @Test
    fun nullOptionalIsAbsentButNonScalarOptionalIsRejected() {
        val transferId = "0f8e2d1c-3b4a-4c5d-8e6f-7a8b9c0d1e2f"
        assertEquals(
            Message.FileResult(transferId, ok = true),
            MessageCodec.decode("""{"type":"file_result","transferId":"$transferId","ok":true,"reason":null}""".toByteArray()),
        )
        assertThrows<ProtocolException> {
            MessageCodec.decode("""{"type":"file_result","transferId":"$transferId","ok":false,"reason":{}}""".toByteArray())
        }
    }

    @Test
    fun base64UrlRoundTripsAndIsStrict() {
        val bytes = ByteArray(32) { it.toByte() }
        val text = Base64Url.encode(bytes)
        assertEquals("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", text)
        assertArrayEquals(bytes, Base64Url.decode32(text))
        assertNull(Base64Url.decode32("$text="))
        assertNull(Base64Url.decode32(text.dropLast(1)))
        assertNull(Base64Url.decode("ab+/"))
    }
}
