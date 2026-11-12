package dev.ivo.droiddrop.core

import dev.ivo.droiddrop.core.protocol.Frame
import dev.ivo.droiddrop.core.protocol.Message
import dev.ivo.droiddrop.core.transfer.Direction
import dev.ivo.droiddrop.core.transfer.DirectoryDestination
import dev.ivo.droiddrop.core.transfer.FileReceiver
import dev.ivo.droiddrop.core.transfer.PartialStore
import dev.ivo.droiddrop.core.transfer.TransferOutcome
import dev.ivo.droiddrop.core.transfer.TransferStatus
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FileReceiverTest {
    @TempDir lateinit var tmp: File
    private val id1 = "0f8e1c52-7a0b-4a43-9d1e-111111111111"
    private val id2 = "0f8e1c52-7a0b-4a43-9d1e-222222222222"
    private val mebibyte = 1_048_576

    private val partials get() = PartialStore(File(tmp, "partials"))
    private val dest get() = File(tmp, "Download/DroidDrop")
    private val receiver by lazy { makeReceiver() }

    private fun makeReceiver() = FileReceiver(partials, DirectoryDestination(dest))

    private fun sample(count: Int = 3_000_000) = ByteArray(count) { (it % 251).toByte() }

    private fun partialCount() = File(tmp, "partials").list()?.size ?: 0
    private fun destinationCount() = dest.list()?.size ?: 0

    private fun offer(id: String, name: String, data: ByteArray, hash: String = sha(data), to: FileReceiver = receiver) =
        to.offer(Message.FileOffer(id, name, data.size.toLong(), hash, "application/octet-stream"))

    private fun chunk(id: String, offset: Int, data: ByteArray, to: FileReceiver = receiver) =
        to.chunk(Frame.Chunk(id, offset.toLong(), data))

    /** Offers, sends everything in one chunk and finishes. Returns the outcome. */
    private fun receive(name: String, data: ByteArray): TransferOutcome {
        offer(id1, name, data)
        assertNull(chunk(id1, 0, data))
        return receiver.done(Message.FileDone(id1))!!.second
    }

    @Test fun receivesFileIntoDestination() {
        val data = sample()
        assertEquals(Message.FileAccept(id1, 0), offer(id1, "photo.jpg", data, sha(data)))
        assertNull(chunk(id1, 0, data.copyOfRange(0, 1_048_576)))
        assertEquals(TransferStatus(Direction.INCOMING, "photo.jpg", 1_048_576, 3_000_000), receiver.status)
        assertNull(chunk(id1, 1_048_576, data.copyOfRange(1_048_576, data.size)))
        val (reply, outcome) = receiver.done(Message.FileDone(id1))!!
        val saved = File(tmp, "Download/DroidDrop/photo.jpg")
        assertEquals(Message.FileResult(id1, true), reply)
        assertEquals(TransferOutcome.Received("photo.jpg", saved.absolutePath), outcome)
        assertArrayEquals(data, saved.readBytes())
        assertNull(receiver.status)
        assertEquals(0, partialCount())
    }

    @Test fun emptyFile() {
        assertEquals(Message.FileAccept(id1, 0), offer(id1, "empty.txt", ByteArray(0)))
        val (reply, _) = receiver.done(Message.FileDone(id1))!!
        assertEquals(Message.FileResult(id1, true), reply)
        assertTrue(File(dest, "empty.txt").isFile)
        assertEquals(0L, File(dest, "empty.txt").length())
    }

    @Test fun resumesFromRetainedPartial() {
        val data = sample()
        offer(id1, "photo.jpg", data)
        assertNull(chunk(id1, 0, data.copyOfRange(0, mebibyte)))
        receiver.close()

        val second = makeReceiver()
        assertEquals(Message.FileAccept(id2, mebibyte.toLong()), offer(id2, "photo.jpg", data, to = second))
        assertEquals(mebibyte.toLong(), second.status?.bytesDone)
        assertNull(chunk(id2, mebibyte, data.copyOfRange(mebibyte, data.size), to = second))
        val (reply, outcome) = second.done(Message.FileDone(id2))!!
        assertEquals(Message.FileResult(id2, true), reply)
        val saved = File(dest, "photo.jpg")
        assertEquals(TransferOutcome.Received("photo.jpg", saved.absolutePath), outcome)
        assertArrayEquals(data, saved.readBytes())
    }

    @Test fun peerCancelKeepsPartial() {
        val data = sample()
        offer(id1, "photo.jpg", data)
        assertNull(chunk(id1, 0, data.copyOfRange(0, mebibyte)))
        receiver.cancel(id1)
        assertNull(receiver.status)
        assertNull(chunk(id1, mebibyte, data.copyOfRange(mebibyte, mebibyte + 10)))
        assertEquals(mebibyte.toLong(), partials.resumeOffset(sha(data), data.size.toLong()))
        assertEquals(Message.FileAccept(id2, mebibyte.toLong()), offer(id2, "photo.jpg", data))
    }

    @Test fun outOfOrderChunkCancelsAndDiscards() {
        val data = sample(100)
        offer(id1, "photo.jpg", data)
        val result = chunk(id1, 10, data.copyOfRange(10, 20))!!
        assertEquals(Message.FileCancel(id1), result.first)
        assertEquals(TransferOutcome.Failed(Direction.INCOMING, "photo.jpg", "out_of_order"), result.second)
        assertNull(receiver.status)
        assertEquals(0, partialCount())
        assertEquals(Message.FileAccept(id2, 0), offer(id2, "photo.jpg", data))
    }

    @Test fun chunkPastSizeCancels() {
        val data = sample(4)
        offer(id1, "a.bin", data)
        val result = chunk(id1, 0, ByteArray(5))!!
        assertEquals(Message.FileCancel(id1), result.first)
        assertEquals(TransferOutcome.Failed(Direction.INCOMING, "a.bin", "too_long"), result.second)
        assertEquals(0, partialCount())
    }

    @Test fun unknownTransferChunksAreIgnored() {
        assertNull(chunk(id2, 0, ByteArray(3)))
        val data = sample(10)
        offer(id1, "a.bin", data)
        assertNull(chunk(id2, 0, ByteArray(3)))
        assertEquals(0L, receiver.status?.bytesDone)
        assertNull(receiver.done(Message.FileDone(id2)))
    }

    @Test fun earlyDoneFailsWithSizeMismatch() {
        val data = sample(10)
        offer(id1, "a.bin", data)
        assertNull(chunk(id1, 0, data.copyOfRange(0, 5)))
        val (reply, outcome) = receiver.done(Message.FileDone(id1))!!
        assertEquals(Message.FileResult(id1, false, "size_mismatch"), reply)
        assertEquals(TransferOutcome.Failed(Direction.INCOMING, "a.bin", "size_mismatch"), outcome)
        assertEquals(0, partialCount())
        assertEquals(0, destinationCount())
        assertNull(receiver.status)
    }

    @Test fun hashMismatchDiscards() {
        val data = sample(1000)
        offer(id1, "a.bin", data, hash = sha("other"))
        assertNull(chunk(id1, 0, data))
        val (reply, outcome) = receiver.done(Message.FileDone(id1))!!
        assertEquals(Message.FileResult(id1, false, "hash_mismatch"), reply)
        assertEquals(TransferOutcome.Failed(Direction.INCOMING, "a.bin", "hash_mismatch"), outcome)
        assertEquals(0, partialCount())
        assertEquals(0, destinationCount())
    }

    @Test fun existingFileIsNotOverwritten() {
        dest.mkdirs()
        File(dest, "photo.jpg").writeText("old")
        val data = sample(100)
        val outcome = receive("photo.jpg", data)
        assertEquals(TransferOutcome.Received("photo.jpg", File(dest, "photo (1).jpg").absolutePath), outcome)
        assertEquals("old", File(dest, "photo.jpg").readText())
        assertArrayEquals(data, File(dest, "photo (1).jpg").readBytes())
    }

    @Test fun traversalNameStaysInDestination() {
        val outcome = receive("../../evil.sh", sample(10))
        assertEquals(TransferOutcome.Received("_.._evil.sh", File(dest, "_.._evil.sh").absolutePath), outcome)
    }

    @Test fun newOfferSupersedesActiveTransfer() {
        val a = sample(3_000_000)
        val b = sample(500)
        offer(id1, "a.bin", a)
        assertNull(chunk(id1, 0, a.copyOfRange(0, mebibyte)))
        assertEquals(Message.FileAccept(id2, 0), offer(id2, "b.bin", b))
        assertNull(chunk(id1, mebibyte, a.copyOfRange(mebibyte, mebibyte + 10)))
        assertEquals(TransferStatus(Direction.INCOMING, "b.bin", 0, b.size.toLong()), receiver.status)
        assertEquals(mebibyte.toLong(), partials.resumeOffset(sha(a), a.size.toLong()))
    }

    @Test fun unwritablePartialsDirectoryRejects() {
        File(tmp, "partials").writeBytes(ByteArray(0))
        val data = sample(10)
        assertEquals(Message.FileReject(id1, "cannot_write"), offer(id1, "a.bin", data))
        assertNull(receiver.status)
    }

    @Test fun saveFailureReportsSaveFailed() {
        val receiver = FileReceiver(partials) { _, _ -> throw IllegalStateException("MediaStore said no") }
        val data = "hello".toByteArray()
        offer(id1, "hello.txt", data, to = receiver)
        assertNull(chunk(id1, 0, data, to = receiver))
        val (reply, outcome) = receiver.done(Message.FileDone(id1))!!
        assertEquals(Message.FileResult(id1, false, "save_failed"), reply)
        assertEquals(TransferOutcome.Failed(Direction.INCOMING, "hello.txt", "save_failed"), outcome)
        assertEquals(0L, partials.resumeOffset(sha("hello"), 5))
    }
}
