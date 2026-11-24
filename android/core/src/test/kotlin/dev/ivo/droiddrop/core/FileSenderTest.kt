package dev.ivo.droiddrop.core

import dev.ivo.droiddrop.core.protocol.BatchInfo
import dev.ivo.droiddrop.core.protocol.Frame
import dev.ivo.droiddrop.core.protocol.Message
import dev.ivo.droiddrop.core.session.MessageSender
import dev.ivo.droiddrop.core.transfer.BatchFailure
import dev.ivo.droiddrop.core.transfer.BatchProgress
import dev.ivo.droiddrop.core.transfer.BatchSummary
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
import kotlinx.coroutines.yield
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
import java.util.concurrent.CopyOnWriteArrayList
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
     * a send left waiting for 2 s fails like a socket closed at session end, so a wrong sender cannot wedge the test.
     * With [events], control messages and gate timeouts are also logged as "<label> <what>", across links.
     */
    class FakeLink(
        private val chunkGate: Semaphore? = null,
        private val events: MutableList<String>? = null,
        private val label: String = "",
        private val failBatchEnd: Boolean = false,
    ) : MessageSender {
        private val sent = Channel<Any>(Channel.UNLIMITED)
        override fun send(message: Message) {
            if (failBatchEnd && message is Message.BatchEnd) throw IOException("link down")
            events?.add("$label ${message::class.simpleName}")
            sent.trySend(message)
        }
        override fun send(frame: Frame) {
            sent.trySend(frame)
            if (chunkGate != null && !chunkGate.tryAcquire(2, TimeUnit.SECONDS)) {
                events?.add("$label gate timed out")
                throw IOException("chunk gate timed out")
            }
        }
        suspend fun next(): Any = sent.receive()
        fun poll(): Any? = sent.tryReceive().getOrNull()
        suspend fun chunk(): Frame.Chunk = next() as Frame.Chunk
        suspend fun offer(name: String? = null): Message.FileOffer {
            val offer = next() as Message.FileOffer
            if (name != null) assertEquals(name, offer.name)
            return offer
        }
    }

    /** One sender plus an outcome recorder, inside a scope whose children are cancelled when the test ends. */
    inner class Harness(
        private val scope: CoroutineScope,
        keepFailedForRetry: Boolean = false,
        collectSummaries: Boolean = true,
    ) {
        val sender = FileSender(keepFailedForRetry = keepFailedForRetry)
        val outcomes = Channel<TransferOutcome>(Channel.UNLIMITED)
        val summaries = Channel<BatchSummary>(Channel.UNLIMITED)
        val link = FakeLink()
        val replies = Channel<Message>(Channel.UNLIMITED)

        init {
            scope.launch(start = CoroutineStart.UNDISPATCHED) { sender.outcomes.collect { outcomes.send(it) } }
            if (collectSummaries) {
                scope.launch(start = CoroutineStart.UNDISPATCHED) { sender.summaries.collect { summaries.send(it) } }
            }
        }

        fun start(link: MessageSender = this.link, replies: Channel<Message> = this.replies): Job =
            scope.launch { sender.run(link, replies) }

        fun reply(message: Message) {
            replies.trySend(message)
        }
    }

    private fun sending(
        keepFailedForRetry: Boolean = false,
        collectSummaries: Boolean = true,
        block: suspend Harness.() -> Unit,
    ) = runBlocking {
        withTimeout(5_000) {
            try {
                Harness(this, keepFailedForRetry, collectSummaries).block()
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

    @Test fun nextRunWaitsForCancelledRunToWindDown() = sending {
        val events = CopyOnWriteArrayList<String>()
        val gated = FakeLink(Semaphore(0), events, "A")
        sender.enqueue(listOf(outgoing("big.bin", sample(3 * mebibyte))))
        val first = start(gated)
        val offerA = gated.offer("big.bin")
        reply(Message.FileAccept(offerA.transferId, 0))
        gated.chunk() // run A is now blocked writing chunk 0, where cancellation cannot reach it
        first.cancel() // the session ended; deliberately not joined
        val link2 = FakeLink(events = events, label = "B")
        val replies2 = Channel<Message>(Channel.UNLIMITED)
        start(link2, replies2)
        val offerB = link2.offer("big.bin")
        // B offered only after A's blocked write failed (the socket closing) and A wound down.
        assertEquals(listOf("A FileOffer", "A gate timed out", "B FileOffer"), events)
        assertTrue(first.isCancelled)
        assertNotEquals(offerA.transferId, offerB.transferId)
        assertEquals(offerA.sha256, offerB.sha256) // A's digest was reused, not recomputed
        assertEquals(0, released("big.bin"))
        assertEquals(TransferStatus(Direction.OUTGOING, "big.bin", 0, 3L * mebibyte), sender.status.value)

        sender.cancelAll()
        assertEquals(Message.FileCancel(offerB.transferId), link2.next())
        assertEquals(failed("big.bin", "cancelled"), outcomes.receive())
        assertEquals(1, released("big.bin"))
        assertTrue(outcomes.tryReceive().isFailure)
        assertEquals(listOf("A FileOffer", "A gate timed out", "B FileOffer", "B FileCancel"), events)
    }

    @Test fun rejectAndFailedResultMoveToNextFile() = sending {
        sender.enqueue(listOf(outgoing("a.txt", "a".toByteArray())))
        sender.enqueue(listOf(outgoing("b.txt", "b".toByteArray())))
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
        sender.enqueue(listOf(outgoing("a.txt", "a".toByteArray())))
        sender.enqueue(listOf(outgoing("b.txt", "b".toByteArray())))
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
        listOf("a.txt", "b.txt", "c.txt").forEach { sender.enqueue(listOf(outgoing(it, it.toByteArray()))) }
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
        sender.enqueue(listOf(outgoing("a.bin", sample(1000))))
        sender.enqueue(listOf(outgoing("b.txt", "b".toByteArray())))
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

    // ---- Batches (SPEC 9.2) ----

    private fun batchOf(vararg names: String) = names.map { outgoing(it, it.toByteArray()) }

    /** Offers, accepts, streams the single chunk, then answers with a file_result. Returns the offer. */
    private suspend fun Harness.complete(
        name: String,
        ok: Boolean = true,
        reason: String? = null,
        on: FakeLink = link,
        check: () -> Unit = {},
    ): Message.FileOffer {
        val offer = on.offer(name)
        check()
        reply(Message.FileAccept(offer.transferId, 0))
        on.chunk()
        assertEquals(Message.FileDone(offer.transferId), on.next())
        reply(Message.FileResult(offer.transferId, ok, reason))
        return offer
    }

    @Test fun twoFilesFormABatch() = sending {
        sender.enqueue(listOf(outgoing("a.txt", "12345".toByteArray()), outgoing("b.txt", "hello world".toByteArray())))
        start()
        val first = complete("a.txt")
        val id = first.batch!!.id
        assertEquals(BatchInfo(id, 0, 2, 16, 0), first.batch)
        val second = complete("b.txt")
        assertEquals(BatchInfo(id, 1, 2, 16, 5), second.batch)
        assertEquals(Message.BatchEnd(id, 2, 2), link.next())
        assertEquals(BatchSummary(Direction.OUTGOING, id, 2, 2), summaries.receive())
        assertEquals(0, sender.pending.value)
    }

    @Test fun batchSendsSmallestFirst() = sending {
        sender.enqueue(
            listOf(
                outgoing("big.txt", "hello world".toByteArray()),
                outgoing("small.txt", "12345".toByteArray()),
                outgoing("mid.txt", "1234567".toByteArray()),
            ),
        )
        start()
        val first = complete("small.txt")
        val id = first.batch!!.id
        assertEquals(BatchInfo(id, 0, 3, 23, 0), first.batch)
        assertEquals(BatchInfo(id, 1, 3, 23, 5), complete("mid.txt").batch)
        assertEquals(BatchInfo(id, 2, 3, 23, 12), complete("big.txt").batch)
    }

    @Test fun equalSizesKeepPickedOrder() = sending {
        sender.enqueue(listOf(outgoing("c.txt", "12345".toByteArray()), outgoing("a.txt", "abcde".toByteArray()), outgoing("b.txt", "vwxyz".toByteArray())))
        start()
        assertEquals(0L, complete("c.txt").batch!!.index)
        assertEquals(1L, complete("a.txt").batch!!.index)
        assertEquals(2L, complete("b.txt").batch!!.index)
    }

    @Test fun singleFileIsUnbatched() = sending {
        sender.enqueue(batchOf("a.txt"))
        start()
        assertNull(complete("a.txt").batch)
        assertEquals(TransferOutcome.Sent("a.txt"), outcomes.receive())
        sender.enqueue(batchOf("next.txt"))
        link.offer("next.txt") // a BatchEnd in between would fail the cast
        assertTrue(summaries.tryReceive().isFailure)
    }

    @Test fun statusCountsThroughBatch() = sending {
        sender.enqueue(listOf(outgoing("a.txt", "12345".toByteArray()), outgoing("b.txt", "hello world".toByteArray())))
        start()
        complete("a.txt")
        val offer = link.offer("b.txt")
        assertEquals(BatchProgress(1, 2, 5, 16), sender.status.value!!.batch)
        reply(Message.FileAccept(offer.transferId, 0))
        link.chunk()
        assertEquals(Message.FileDone(offer.transferId), link.next())
        assertEquals(BatchProgress(1, 2, 16, 16), sender.status.value!!.batch)
    }

    @Test fun progressClampedWhenFileGrew() = sending {
        sender.enqueue(listOf(outgoing("a.txt", "12345".toByteArray()), outgoing("b.txt", "hello world".toByteArray())))
        File(tmp, "a.txt").appendBytes("123456789".toByteArray()) // 14 bytes now, 5 at enqueue
        start()
        val offer = link.offer("a.txt")
        assertEquals(14L, offer.size)
        assertEquals(BatchInfo(offer.batch!!.id, 0, 2, 16, 0), offer.batch)
        reply(Message.FileAccept(offer.transferId, 0))
        link.chunk()
        assertEquals(Message.FileDone(offer.transferId), link.next())
        val progress = sender.status.value!!.batch!!
        assertEquals(BatchProgress(0, 2, 5, 16), progress)
    }

    @Test fun failureListedInSummary() = sending {
        sender.enqueue(batchOf("a.txt", "b.txt"))
        start()
        val id = complete("a.txt").batch!!.id
        complete("b.txt", ok = false, reason = "hash_mismatch")
        assertEquals(Message.BatchEnd(id, 2, 1), link.next())
        assertEquals(
            BatchSummary(Direction.OUTGOING, id, 2, 1, failures = listOf(BatchFailure("b.txt", "hash_mismatch"))),
            summaries.receive(),
        )
    }

    @Test fun retryKeepsFailedFilesOpen() = sending(keepFailedForRetry = true) {
        sender.enqueue(batchOf("a.txt", "b.txt"))
        start()
        complete("a.txt")
        complete("b.txt", ok = false, reason = "hash_mismatch")
        val summary = summaries.receive()
        assertEquals(listOf("b.txt"), summary.retry.map { it.name })
        assertEquals(0, released("b.txt"))
        assertEquals(1, released("a.txt"))
        summary.retry.forEach { it.release() }
        assertEquals(1, released("b.txt"))
    }

    @Test fun failedFileReleasedWithoutRetryKeeping() = sending {
        sender.enqueue(batchOf("a.txt", "b.txt"))
        start()
        complete("a.txt")
        complete("b.txt", ok = false, reason = "hash_mismatch")
        val summary = summaries.receive()
        assertTrue(summary.retry.isEmpty())
        assertEquals(1, released("b.txt"))
    }

    @Test fun reofferKeepsBatch() = sending {
        sender.enqueue(batchOf("a.txt", "b.txt"))
        val first = start()
        complete("a.txt")
        val offer1 = link.offer("b.txt")
        first.cancelAndJoin()
        val link2 = FakeLink()
        start(link2, Channel(Channel.UNLIMITED))
        val offer2 = link2.offer("b.txt")
        assertNotEquals(offer1.transferId, offer2.transferId)
        assertEquals(BatchInfo(offer1.batch!!.id, 1, 2, 10, 5), offer2.batch)
        assertEquals(offer1.batch, offer2.batch)
    }

    @Test fun batchEndWaitsForNextSession() = sending {
        sender.enqueue(batchOf("a.txt", "b.txt"))
        val failing = FakeLink(failBatchEnd = true)
        val first = start(failing)
        val id = complete("a.txt", on = failing).batch!!.id
        complete("b.txt", on = failing)
        summaries.receive()
        first.cancelAndJoin()
        sender.enqueue(batchOf("c.txt"))
        val link2 = FakeLink()
        start(link2, Channel(Channel.UNLIMITED))
        assertEquals(Message.BatchEnd(id, 2, 2), link2.next())
        link2.offer("c.txt")
    }

    @Test fun cancelOfflineQueuesBatchEnd() = sending {
        sender.enqueue(batchOf("a.txt", "b.txt"))
        val first = start()
        val id = complete("a.txt").batch!!.id
        link.offer("b.txt")
        first.cancelAndJoin()
        sender.cancelAll()
        assertEquals(BatchSummary(Direction.OUTGOING, id, 2, 1, cancelled = true), summaries.receive())
        assertEquals(1, released("b.txt"))
        val link2 = FakeLink()
        start(link2, Channel(Channel.UNLIMITED))
        assertEquals(Message.BatchEnd(id, 2, 1, cancelled = true), link2.next())
    }

    @Test fun cancelQueuedOnlyBatchIsSilent() = sending {
        sender.enqueue(batchOf("a.txt", "b.txt"))
        sender.cancelAll()
        assertEquals(1, released("a.txt"))
        assertEquals(1, released("b.txt"))
        start()
        sender.enqueue(batchOf("c.txt"))
        link.offer("c.txt") // no BatchEnd first
        assertTrue(summaries.tryReceive().isFailure)
    }

    @Test fun silentCancelDropsEverything() = sending {
        sender.enqueue(batchOf("a.txt", "b.txt"))
        start()
        val id = complete("a.txt").batch!!.id
        val offerB = link.offer("b.txt")
        sender.cancelAll(silent = true)
        assertEquals(Message.FileCancel(offerB.transferId), link.next())
        assertEquals(TransferOutcome.Sent("a.txt", BatchInfo(id, 0, 2, 10, 0)), outcomes.receive())
        assertEquals(
            TransferOutcome.Failed(Direction.OUTGOING, "b.txt", "cancelled", BatchInfo(id, 1, 2, 10, 5)),
            outcomes.receive(),
        )
        sender.enqueue(batchOf("c.txt"))
        link.offer("c.txt") // no BatchEnd first
        assertTrue(summaries.tryReceive().isFailure)
    }

    @Test fun silentCancelDropsPendingEnd() = sending {
        sender.enqueue(batchOf("a.txt", "b.txt"))
        val failing = FakeLink(failBatchEnd = true)
        val first = start(failing)
        complete("a.txt", on = failing)
        complete("b.txt", on = failing)
        summaries.receive()
        first.cancelAndJoin()
        sender.cancelAll(silent = true)
        val link2 = FakeLink()
        start(link2, Channel(Channel.UNLIMITED))
        sender.enqueue(batchOf("c.txt"))
        link2.offer("c.txt") // no BatchEnd first
    }

    @Test fun outcomesTaggedWithBatch() = sending {
        sender.enqueue(batchOf("a.txt", "b.txt"))
        start()
        val id = complete("a.txt").batch!!.id
        complete("b.txt")
        assertEquals(TransferOutcome.Sent("a.txt", BatchInfo(id, 0, 2, 10, 0)), outcomes.receive())
        assertEquals(TransferOutcome.Sent("b.txt", BatchInfo(id, 1, 2, 10, 5)), outcomes.receive())
    }

    @Test fun queuedCountsOnlyFilesAfterTheActiveBatch() = sending {
        sender.enqueue(batchOf("a.txt", "b.txt"))
        sender.enqueue(batchOf("c.txt"))
        start()
        complete("a.txt", on = link, check = { assertEquals(1, sender.status.value!!.queued) })
        complete("b.txt", on = link, check = { assertEquals(1, sender.status.value!!.queued) })
        assertTrue(link.next() is Message.BatchEnd)
        complete("c.txt", on = link, check = { assertEquals(0, sender.status.value!!.queued) })
    }

    @Test fun offlineCancelAfterOfferedFileGivesSummaryAndEnd() = sending {
        sender.enqueue(batchOf("a.txt", "b.txt"))
        val first = start()
        val id = link.offer("a.txt").batch!!.id
        first.cancelAndJoin() // dropped mid-file: offered, but no outcome
        sender.cancelAll()
        assertEquals(BatchSummary(Direction.OUTGOING, id, 2, 0, cancelled = true), summaries.receive())
        val link2 = FakeLink()
        start(link2, Channel(Channel.UNLIMITED))
        assertEquals(Message.BatchEnd(id, 2, 0, cancelled = true), link2.next())
    }

    @Test fun cancelWhileHashingFirstFileGivesNoSummaryOrEnd() = sending {
        val big = File(tmp, "big.bin")
        RandomAccessFile(big, "rw").use { it.setLength(512L * mebibyte) } // sparse; hashing takes a moment
        val counter = releases.getOrPut("big.bin") { AtomicInteger() }
        sender.enqueue(
            listOf(OutgoingFile("big.bin", "x/y", FileChannel.open(big.toPath())) { counter.incrementAndGet() }) + batchOf("b.txt"),
        )
        start()
        while (sender.status.value == null) yield() // published right before hashing starts
        sender.cancelAll()
        assertEquals(TransferOutcome.Failed::class, outcomes.receive()::class)
        sender.enqueue(batchOf("c.txt"))
        link.offer("c.txt") // no BatchEnd first
        assertTrue(summaries.tryReceive().isFailure)
    }

    @Test fun onlineCancelEndsBatchCancelled() = sending {
        sender.enqueue(batchOf("a.txt", "b.txt"))
        start()
        val id = complete("a.txt").batch!!.id
        val offerB = link.offer("b.txt")
        sender.cancelAll()
        assertEquals(Message.FileCancel(offerB.transferId), link.next())
        assertEquals(Message.BatchEnd(id, 2, 1, cancelled = true), link.next())
        assertEquals(BatchSummary(Direction.OUTGOING, id, 2, 1, cancelled = true), summaries.receive())
    }

    @Test fun keptFilesReleasedWhenBatchEndsCancelled() = sending(keepFailedForRetry = true) {
        sender.enqueue(batchOf("a.txt", "b.txt", "c.txt"))
        start()
        complete("a.txt", ok = false, reason = "hash_mismatch")
        link.offer("b.txt")
        sender.cancelAll()
        val summary = summaries.receive()
        assertTrue(summary.cancelled)
        assertTrue(summary.retry.isEmpty())
        assertEquals(listOf(BatchFailure("a.txt", "hash_mismatch")), summary.failures)
        assertEquals(1, released("a.txt"))
    }

    @Test fun keptFilesReleasedWhenBatchEndsSilent() = sending(keepFailedForRetry = true) {
        sender.enqueue(batchOf("a.txt", "b.txt", "c.txt"))
        start()
        complete("a.txt", ok = false, reason = "hash_mismatch")
        link.offer("b.txt")
        sender.cancelAll(silent = true)
        assertEquals(TransferOutcome.Failed::class, outcomes.receive()::class)
        assertEquals(TransferOutcome.Failed::class, outcomes.receive()::class)
        assertEquals(1, released("a.txt"))
        assertTrue(summaries.tryReceive().isFailure)
    }

    @Test fun retryFilesReleasedWhenNobodyCollectsSummaries() = sending(keepFailedForRetry = true, collectSummaries = false) {
        sender.enqueue(batchOf("a.txt", "b.txt"))
        start()
        complete("a.txt")
        complete("b.txt", ok = false, reason = "hash_mismatch")
        outcomes.receive()
        outcomes.receive()
        assertEquals(1, released("b.txt"))
    }

    @Test fun batchEndIsWrittenBeforeLastOutcomeIsObservable() = sending {
        sender.enqueue(batchOf("a.txt", "b.txt"))
        start()
        val id = complete("a.txt").batch!!.id
        complete("b.txt")
        outcomes.receive()
        outcomes.receive() // the last Sent
        assertEquals(Message.BatchEnd(id, 2, 2), link.poll())
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
