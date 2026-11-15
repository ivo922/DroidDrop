package dev.ivo.droiddrop.core

import dev.ivo.droiddrop.core.clipboard.ClipOutbox
import dev.ivo.droiddrop.core.clipboard.ClipOutbox.Decision
import dev.ivo.droiddrop.core.protocol.Message
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClipOutboxTest {
    private val id = "6f1c0d2e-8a4b-4c3d-9e5f-0a1b2c3d4e5f"
    private val outbox = ClipOutbox(makeId = { id })

    private fun send(text: String) = Decision.Send(Message.Clip(id, "text/plain", text))

    @Test
    fun sendsPlainText() {
        assertEquals(send("hi"), outbox.decide("hi", automatic = false))
    }

    @Test
    fun emptyIsNotSent() {
        assertEquals(Decision.Empty, outbox.decide("", automatic = false))
    }

    @Test
    fun tooLargeCountsUtf8Bytes() {
        assertInstanceOf(Decision.Send::class.java, outbox.decide("a".repeat(262_144), automatic = false))
        assertEquals(Decision.TooLarge, outbox.decide("a".repeat(262_145), automatic = false))
        assertEquals(Decision.TooLarge, outbox.decide("€".repeat(87_382), automatic = false)) // 262 146 bytes
    }

    @Test
    fun tooLargeCountsEncodedFrame() {
        // 200 000 bytes of text, but each U+0001 is six bytes of JSON.
        assertEquals(Decision.TooLarge, outbox.decide("\u0001".repeat(200_000), automatic = false))
    }

    @Test
    fun automaticSendSkipsTextFromMac() {
        outbox.noteRemote("from mac")
        assertEquals(Decision.FromMac, outbox.decide("from mac", automatic = true))
        assertEquals(send("other"), outbox.decide("other", automatic = true))
    }

    @Test
    fun explicitSendIgnoresTextFromMac() {
        outbox.noteRemote("from mac")
        assertEquals(send("from mac"), outbox.decide("from mac", automatic = false))
    }

    @Test
    fun automaticSendSkipsSensitive() {
        assertEquals(Decision.Sensitive, outbox.decide("hunter2", automatic = true, sensitive = true))
        assertEquals(send("hunter2"), outbox.decide("hunter2", automatic = false, sensitive = true))
    }

    @Test
    fun defaultIdIsLowercaseUuid() {
        val decision = ClipOutbox().decide("x", automatic = false)
        assertInstanceOf(Decision.Send::class.java, decision)
        val clipId = (decision as Decision.Send).clip.id
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$").matches(clipId), clipId)
    }
}
