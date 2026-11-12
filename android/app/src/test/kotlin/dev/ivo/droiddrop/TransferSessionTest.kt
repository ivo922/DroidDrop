package dev.ivo.droiddrop

import dev.ivo.droiddrop.core.protocol.Frame
import dev.ivo.droiddrop.core.protocol.Message
import dev.ivo.droiddrop.core.session.MessageSender
import dev.ivo.droiddrop.core.transfer.DirectoryDestination
import dev.ivo.droiddrop.core.transfer.FileReceiver
import dev.ivo.droiddrop.core.transfer.FileSender
import dev.ivo.droiddrop.core.transfer.PartialStore
import dev.ivo.droiddrop.core.transfer.TransferOutcome
import dev.ivo.droiddrop.core.transfer.TransferStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CopyOnWriteArrayList

class TransferSessionTest {
    @TempDir
    lateinit var dir: File

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val incoming = CopyOnWriteArrayList<TransferStatus?>()
    private val outcomes = CopyOnWriteArrayList<TransferOutcome>()
    private val id = "11111111-2222-3333-4444-555555555555"

    private class FakeLink(private val failure: IOException? = null) : MessageSender {
        val messages = CopyOnWriteArrayList<Message>()

        override fun send(message: Message) {
            failure?.let { throw it }
            messages += message
        }

        override fun send(frame: Frame) {
            failure?.let { throw it }
        }
    }

    private lateinit var partials: PartialStore
    private lateinit var session: TransferSession

    private fun newSession(): TransferSession {
        partials = PartialStore(File(dir, "partials"))
        return TransferSession(
            scope,
            FileReceiver(partials, DirectoryDestination(File(dir, "out"))),
            FileSender(),
            { incoming += it },
            { outcomes += it },
        ).also { session = it }
    }

    private fun sha(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    @AfterEach
    fun tearDown() {
        session.close()
        scope.cancel()
    }

    @Test
    fun routesIncomingFileToDestination() {
        val link = FakeLink()
        val bytes = "hello".toByteArray()
        newSession().connected(link)

        assertTrue(session.handle(Message.FileOffer(id, "hello.txt", 5, sha(bytes), "text/plain")))
        assertEquals(Message.FileAccept(id, 0), link.messages.last())
        session.chunk(Frame.Chunk(id, 0, bytes))
        assertTrue(session.handle(Message.FileDone(id)))

        assertEquals(Message.FileResult(id, true), link.messages.last())
        val path = File(File(dir, "out"), "hello.txt").absolutePath
        assertEquals(listOf<TransferOutcome>(TransferOutcome.Received("hello.txt", path)), outcomes)
        assertEquals("hello", File(path).readText())
    }

    @Test
    fun nonFileMessagesAreNotHandled() {
        newSession().connected(FakeLink())
        assertFalse(session.handle(Message.Clip("c", "text/plain", "x")))
        assertFalse(session.handle(Message.Pong(1)))
    }

    @Test
    fun linkFailureDoesNotThrow() {
        newSession().connected(FakeLink(IOException("broken pipe")))
        assertTrue(session.handle(Message.FileOffer(id, "hello.txt", 5, sha("hello".toByteArray()), "text/plain")))
    }

    @Test
    fun closeKeepsPartialAndReportsIdle() {
        val bytes = ByteArray(10) { it.toByte() }
        newSession().connected(FakeLink())
        session.handle(Message.FileOffer(id, "ten.bin", 10, sha(bytes), "application/octet-stream"))
        session.chunk(Frame.Chunk(id, 0, bytes.copyOf(5)))

        session.close()

        assertNull(incoming.last())
        assertEquals(5L, partials.file(sha(bytes), 10).length())
    }
}
