package dev.ivo.droiddrop

import dev.ivo.droiddrop.ClipLink.Result
import dev.ivo.droiddrop.core.protocol.Frame
import dev.ivo.droiddrop.core.protocol.Message
import dev.ivo.droiddrop.core.session.MessageSender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class ClipLinkTest {
    private class FakeSender(var fail: Boolean = false) : MessageSender {
        val messages = mutableListOf<Message>()

        override fun send(message: Message) {
            if (fail) throw IOException("socket closed")
            messages += message
        }

        override fun send(frame: Frame) = throw UnsupportedOperationException()
    }

    private val link = ClipLink()
    private val sender = FakeSender()

    @Test
    fun sendsThroughAttachedSender() {
        link.attach(sender)
        assertEquals(Result.Sent, link.send("hi", automatic = false))
        val clip = sender.messages.single() as Message.Clip
        assertEquals("hi", clip.text)
        assertEquals("text/plain", clip.mime)
    }

    @Test
    fun sendWithoutSessionIsNotConnected() {
        assertEquals(Result.NotConnected, link.send("hi", automatic = false))
    }

    @Test
    fun detachClearsSender() {
        link.attach(sender)
        link.detach()
        assertEquals(Result.NotConnected, link.send("hi", automatic = false))
        assertTrue(sender.messages.isEmpty())
    }

    @Test
    fun emptyBeatsNotConnected() {
        assertEquals(Result.Empty, link.send("", automatic = false))
    }

    @Test
    fun tooLargeBeatsNotConnected() {
        assertEquals(Result.TooLarge, link.send("a".repeat(262_145), automatic = false))
    }

    @Test
    fun writeFailureIsFailed() {
        link.attach(FakeSender(fail = true))
        assertEquals(Result.Failed, link.send("hi", automatic = false))
    }

    @Test
    fun automaticEchoIsSkipped() {
        link.outbox.noteRemote("m")
        link.attach(sender)
        assertEquals(Result.Skipped, link.send("m", automatic = true))
        assertEquals(Result.Skipped, link.send("pw", automatic = true, sensitive = true))
        assertTrue(sender.messages.isEmpty())
    }

    @Test
    fun toastCopy() {
        assertEquals("Sent to MacBook", ClipLink.toast(Result.Sent, "MacBook", automatic = false))
        assertEquals("Not connected to MacBook.", ClipLink.toast(Result.NotConnected, "MacBook", automatic = false))
        assertEquals("No text to send.", ClipLink.toast(Result.Empty, "MacBook", automatic = false))
        assertEquals("Too much text to send (limit 256 KB).", ClipLink.toast(Result.TooLarge, "MacBook", automatic = false))
        assertEquals("Could not send to MacBook.", ClipLink.toast(Result.Failed, "MacBook", automatic = false))
        assertNull(ClipLink.toast(Result.Skipped, "MacBook", automatic = false))
    }

    @Test
    fun toastWhenUnpaired() {
        assertEquals("Pair DroidDrop with your Mac first.", ClipLink.toast(Result.NotConnected, null, automatic = false))
    }

    @Test
    fun automaticSendsAreSilent() {
        assertNull(ClipLink.toast(Result.Sent, "MacBook", automatic = true))
        assertNull(ClipLink.toast(Result.NotConnected, "MacBook", automatic = true))
    }
}
