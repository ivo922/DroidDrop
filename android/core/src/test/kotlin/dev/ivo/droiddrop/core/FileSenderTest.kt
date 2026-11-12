package dev.ivo.droiddrop.core

import dev.ivo.droiddrop.core.protocol.Frame
import dev.ivo.droiddrop.core.protocol.Message
import dev.ivo.droiddrop.core.session.MessageSender
import dev.ivo.droiddrop.core.transfer.Direction
import dev.ivo.droiddrop.core.transfer.FileSender
import dev.ivo.droiddrop.core.transfer.OutgoingFile
import dev.ivo.droiddrop.core.transfer.TransferOutcome
import dev.ivo.droiddrop.core.transfer.TransferStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class FileSenderTest {
    @TempDir lateinit var tmp: File
    private val mebibyte = 1_048_576
    private val otherId = "0f8e1c52-7a0b-4a43-9d1e-333333333333"
    private val releases = ConcurrentHashMap<String, AtomicInteger>()

    /**
     * Records messages and chunks in send order. With a [chunkGate], each chunk send blocks until a permit is released;
     * a send left waiting for 2 s fails like a dead socket, so a wrong sender cannot wedge the test.
     */
    class FakeLink(private val chunkGate: Semaphore? = null) : MessageSender {
        private val sent = Channel<Any>(Channel.UNLIMITED)
        override fun send(message: Message) {
            sent.trySend(message)
        }
        override fun send(frame: Frame) {
            sent.trySend(frame)
            if (chunkGate != null && !chunkGate.tryAcquire(2, TimeUnit.SECONDS)) throw IOException("chunk gate timed out")
        }
        suspend fun next(): Any = sent.receive()
        suspend fun chunk(): Frame.Chunk = next() as Frame.Chunk
        suspend fun offer(name: String? = null): Message.FileOffer {
            val offer = next() as Message.FileOffer
            if (name != null) assertEquals(name, offer.name)
            return offer
        }
    }

    /** One sender plus an outcome recorder, inside a scope whose children are cancelled when the test ends. */
    inner class Harness(private val scope: CoroutineScope) {
        val sender = FileSender()
        val outcomes = Channel<TransferOutcome>(Channel.UNLIMITED)
        val link = FakeLink()
        val replies = Channel<Message>(Channel.UNLIMITED)

        init {
            scope.launch(start = CoroutineStart.UNDISPATCHED) { sender.outcomes.collect { outcomes.send(it) } }
        }

        fun start(link: MessageSender = this.link, replies: Channel<Message> = this.replies): Job =
            scope.launch { sender.run(link, replies) }

        fun reply(message: Message) {
            replies.trySend(message)
        }
    }

    private fun sending(block: suspend Harness.() -> Unit) = runBlocking {
        withTimeout(5_000) {
            try {
                Harness(this).block()
            } finally {
                coroutineContext.cancelChildren()
            }
        }
    }

    private fun sample(count: Int) = ByteArray(count) { (it % 253).toByte() }

    private fun write(name: String, data: ByteArray): File = File(tmp, name).apply { writeBytes(data) }

    private fun outgoing(name: String, data: ByteArray, mime: String = "application/octet-stream"): OutgoingFile {
        val counter = releases.getOrPut(name) { AtomicInteger() }
        return OutgoingFile(name, mime, FileChannel.open(write(name, data).toPath())) { counter.incrementAndGet() }
    }

    private fun released(name: String) = releases[name]?.get() ?: 0

    private fun failed(name: String, reason: String) = TransferOutcome.Failed(Direction.OUTGOING, name, reason)

    @Test fun sendsFileInChunksThenDone() = sending {
        val data = sample(2_621_440)
        sender.enqueue(listOf(outgoing("report.pdf", data, "application/pdf")))
        start()
        val offer = link.offer()
        val id = offer.transferId
        assertEquals(Message.FileOffer(id, "report.pdf", 2_621_440, sha(data), "application/pdf"), offer)
        assertEquals(TransferStatus(Direction.OUTGOING, "report.pdf", 0, 2_621_440), sender.status.value)
        reply(Message.FileAccept(id, 0))
        val chunks = List(3) { link.chunk() }
        assertEquals(listOf(0L, 1_048_576L, 2_097_152L), chunks.map { it.offset })
        assertEquals(listOf(1_048_576, 1_048_576, 524_288), chunks.map { it.data.size })
        assertEquals(listOf(id, id, id), chunks.map { it.transferId })
        assertArrayEquals(data, chunks.map { it.data }.reduce(ByteArray::plus))
        assertEquals(Message.FileDone(id), link.next())
        assertEquals(TransferStatus(Direction.OUTGOING, "report.pdf", 2_621_440, 2_621_440), sender.status.value)
        assertEquals(0, released("report.pdf"))
        reply(Message.FileResult(id, true))
        assertEquals(TransferOutcome.Sent("report.pdf"), outcomes.receive())
        assertEquals(1, released("report.pdf"))
        assertEquals(0, sender.pending.value)
        assertNull(sender.status.value)
    }

    @Test fun resumesFromAcceptedOffset() = sending {
        val data = sample(2_621_440)
        sender.enqueue(listOf(outgoing("big.bin", data)))
        start()
        val id = link.offer().transferId
        reply(Message.FileAccept(id, mebibyte.toLong()))
        val chunks = List(2) { link.chunk() }
        assertEquals(listOf(1_048_576L, 2_097_152L), chunks.map { it.offset })
        assertArrayEquals(data.copyOfRange(mebibyte, data.size), chunks.map { it.data }.reduce(ByteArray::plus))
        assertEquals(Message.FileDone(id), link.next())
    }

    @Test fun zeroLengthFileSendsDoneWithoutChunks() = sending {
        sender.enqueue(listOf(outgoing("a.txt", ByteArray(0))))
        start()
        val offer = link.offer("a.txt")
        assertEquals(0L, offer.size)
        assertEquals(sha(ByteArray(0)), offer.sha256)
        reply(Message.FileAccept(offer.transferId, 0))
        assertEquals(Message.FileDone(offer.transferId), link.next())
    }

    @Test fun reoffersHeadAfterSessionEnds() = sending {
        val data = sample(2_621_440)
        sender.enqueue(listOf(outgoing("big.bin", data)))
        val first = start()
        val offer1 = link.offer("big.bin")
        reply(Message.FileAccept(offer1.transferId, 0))
        link.chunk()
        first.cancelAndJoin()
        assertEquals(0, released("big.bin"))
        assertEquals(1, sender.pending.value)
        assertNull(sender.status.value)

        val link2 = FakeLink()
        val replies2 = Channel<Message>(Channel.UNLIMITED)
        start(link2, replies2)
        val offer2 = link2.offer("big.bin")
        assertNotEquals(offer1.transferId, offer2.transferId)
        assertEquals(offer1.sha256, offer2.sha256)
        assertEquals(offer1.size, offer2.size)
        // A late accept for the first session's id must not start the new one.
        replies2.send(Message.FileAccept(offer1.transferId, 0))
        replies2.send(Message.FileAccept(offer2.transferId, 0))
        val chunks = List(3) { link2.chunk() }
        assertEquals(listOf(offer2.transferId), chunks.map { it.transferId }.distinct())
        assertArrayEquals(data, chunks.map { it.data }.reduce(ByteArray::plus))
        assertTrue(outcomes.tryReceive().isFailure)
        assertEquals(0, released("big.bin"))
    }

    @Test fun rejectAndFailedResultMoveToNextFile() = sending {
        sender.enqueue(listOf(outgoing("a.txt", "a".toByteArray()), outgoing("b.txt", "b".toByteArray())))
        assertEquals(2, sender.pending.value)
        start()
        val idA = link.offer("a.txt").transferId
        reply(Message.FileReject(idA, "busy"))
        assertEquals(failed("a.txt", "busy"), outcomes.receive())
        assertEquals(1, released("a.txt"))
        val idB = link.offer("b.txt").transferId
        reply(Message.FileAccept(idB, 0))
        link.chunk()
        assertEquals(Message.FileDone(idB), link.next())
        reply(Message.FileResult(idB, false, "hash_mismatch"))
        assertEquals(failed("b.txt", "hash_mismatch"), outcomes.receive())
        assertEquals(1, released("b.txt"))
        assertEquals(0, sender.pending.value)
    }

    @Test fun peerCancelFailsFileAndContinues() = sending {
        sender.enqueue(listOf(outgoing("a.txt", "a".toByteArray()), outgoing("b.txt", "b".toByteArray())))
        start()
        val idA = link.offer("a.txt").transferId
        reply(Message.FileAccept(idA, 0))
        link.chunk()
        assertEquals(Message.FileDone(idA), link.next())
        reply(Message.FileCancel(idA))
        assertEquals(failed("a.txt", "cancelled by receiver"), outcomes.receive())
        assertEquals(1, released("a.txt"))
        link.offer("b.txt")
    }

    @Test fun cancelAllSendsCancelAndEmptiesQueue() = sending {
        sender.enqueue(listOf("a.txt", "b.txt", "c.txt").map { outgoing(it, it.toByteArray()) })
        start()
        val id = link.offer("a.txt").transferId
        assertEquals(TransferStatus(Direction.OUTGOING, "a.txt", 0, 5, queued = 2), sender.status.value)
        reply(Message.FileAccept(id, 0))
        link.chunk()
        assertEquals(Message.FileDone(id), link.next())
        thread { sender.cancelAll() }.join()
        assertEquals(0, sender.pending.value)
        assertEquals(1, released("b.txt"))
        assertEquals(1, released("c.txt"))
        assertEquals(Message.FileCancel(id), link.next())
        assertEquals(failed("a.txt", "cancelled"), outcomes.receive())
        assertEquals(1, released("a.txt"))
        assertNull(sender.status.value)
        // b and c are gone: the next thing offered is a file queued after the cancel.
        sender.enqueue(listOf(outgoing("d.txt", "d".toByteArray())))
        link.offer("d.txt")
    }

    @Test fun cancelAllBetweenChunksStopsSending() = sending {
        val gate = Semaphore(0)
        val gated = FakeLink(gate)
        sender.enqueue(listOf(outgoing("big.bin", sample(3 * mebibyte))))
        start(gated)
        val id = gated.offer().transferId
        reply(Message.FileAccept(id, 0))
        assertEquals(0L, gated.chunk().offset) // the sender is now blocked writing this chunk
        sender.cancelAll()
        gate.release()
        assertEquals(Message.FileCancel(id), gated.next())
        assertEquals(failed("big.bin", "cancelled"), outcomes.receive())
        assertEquals(1, released("big.bin"))
    }

    @Test fun cancelAllWithoutSessionReleasesSilently() = sending {
        sender.enqueue(listOf(outgoing("a.txt", "a".toByteArray()), outgoing("b.txt", "b".toByteArray())))
        sender.cancelAll()
        assertEquals(0, sender.pending.value)
        assertEquals(1, released("a.txt"))
        assertEquals(1, released("b.txt"))
        sender.enqueue(listOf(outgoing("c.txt", "c".toByteArray())))
        start()
        val id = link.offer("c.txt").transferId
        reply(Message.FileReject(id, "busy"))
        // No outcome was reported for a or b.
        assertEquals(failed("c.txt", "busy"), outcomes.receive())
    }

    @Test fun ignoresRepliesForOtherIds() = sending {
        sender.enqueue(listOf(outgoing("a.bin", sample(1000)), outgoing("b.txt", "b".toByteArray())))
        start()
        val idA = link.offer("a.bin").transferId
        reply(Message.FileAccept(otherId, 0))
        reply(Message.FileCancel(otherId))
        reply(Message.FileResult(otherId, false, "nope"))
        reply(Message.FileReject(idA, "busy"))
        // Had the stray accept started a, a chunk would come before b's offer.
        link.offer("b.txt")
        assertEquals(failed("a.bin", "busy"), outcomes.receive())
    }

    @Test fun unreadableSourceFailsAndQueueContinues() = sending {
        val a = outgoing("a.txt", "a".toByteArray())
        a.channel.close()
        sender.enqueue(listOf(a, outgoing("b.txt", "b".toByteArray())))
        start()
        link.offer("b.txt")
        val outcome = outcomes.receive() as TransferOutcome.Failed
        assertEquals(Direction.OUTGOING, outcome.direction)
        assertEquals("a.txt", outcome.name)
        assertTrue(outcome.reason.isNotEmpty())
        assertEquals(1, released("a.txt"))
    }

    @Test fun fileChangedWhileSendingCancelsAndFails() = sending {
        sender.enqueue(listOf(outgoing("shrinks.bin", sample(2 * mebibyte))))
        start()
        val id = link.offer().transferId
        RandomAccessFile(File(tmp, "shrinks.bin"), "rw").use { it.setLength(10) } // truncated after hashing
        reply(Message.FileAccept(id, 0))
        assertEquals(Message.FileCancel(id), link.next())
        assertEquals(failed("shrinks.bin", "file changed or unreadable"), outcomes.receive())
        assertEquals(0, sender.pending.value)
    }

    @Test fun acceptPastSizeCancelsAndFails() = sending {
        sender.enqueue(listOf(outgoing("a.txt", "abc".toByteArray())))
        start()
        val id = link.offer().transferId
        reply(Message.FileAccept(id, 4))
        assertEquals(Message.FileCancel(id), link.next())
        assertEquals(failed("a.txt", "file changed or unreadable"), outcomes.receive())
    }

    @Test fun releaseIsIdempotent() {
        val count = AtomicInteger()
        val file = OutgoingFile("a.txt", "text/plain", FileChannel.open(write("a.txt", ByteArray(1)).toPath())) { count.incrementAndGet() }
        file.release()
        file.release()
        assertEquals(1, count.get())
        assertTrue(!file.channel.isOpen)
    }
}
