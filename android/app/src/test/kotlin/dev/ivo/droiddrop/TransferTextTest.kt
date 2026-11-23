package dev.ivo.droiddrop

import dev.ivo.droiddrop.core.transfer.BatchFailure
import dev.ivo.droiddrop.core.transfer.BatchProgress
import dev.ivo.droiddrop.core.transfer.BatchSummary
import dev.ivo.droiddrop.core.transfer.Direction
import dev.ivo.droiddrop.core.transfer.TransferOutcome
import dev.ivo.droiddrop.core.transfer.TransferStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TransferTextTest {
    @Test
    fun progressLines() {
        assertEquals("Receiving photo.jpg — 42%", TransferText.progress(TransferStatus(Direction.INCOMING, "photo.jpg", 42, 100)))
        assertEquals("Sending a.pdf — 10% (+2 queued)", TransferText.progress(TransferStatus(Direction.OUTGOING, "a.pdf", 1, 10, queued = 2)))
        assertEquals("Receiving e — 0%", TransferText.progress(TransferStatus(Direction.INCOMING, "e", 0, 0)))
        assertEquals("1 file waiting for MacBook", TransferText.waiting(1, "MacBook"))
        assertEquals("3 files waiting for MacBook", TransferText.waiting(3, "MacBook"))
    }

    @Test
    fun outcomeText() {
        assertEquals("Received photo.jpg" to "Download › DroidDrop", TransferText.outcome(TransferOutcome.Received("photo.jpg", "content://x"), "MacBook"))
        assertEquals("Sent a.pdf" to "To MacBook", TransferText.outcome(TransferOutcome.Sent("a.pdf"), "MacBook"))
        assertEquals("Could not send c" to "cancelled", TransferText.outcome(TransferOutcome.Failed(Direction.OUTGOING, "c", "cancelled"), "MacBook"))
    }

    @Test
    fun batchProgress() {
        val batch = BatchProgress(index = 2, count = 20, bytesDone = 45, size = 100)
        assertEquals(
            "Sending 3 of 20 · IMG_2041.jpg — 45%",
            TransferText.progress(TransferStatus(Direction.OUTGOING, "IMG_2041.jpg", 5, 10, batch = batch)),
        )
        assertEquals(
            "Sending 3 of 20 · IMG_2041.jpg — 45% (+5 queued)",
            TransferText.progress(TransferStatus(Direction.OUTGOING, "IMG_2041.jpg", 5, 10, queued = 5, batch = batch)),
        )
        assertEquals(
            "Receiving 3 of 20 · IMG_2041.jpg — 45%",
            TransferText.progress(TransferStatus(Direction.INCOMING, "IMG_2041.jpg", 5, 10, batch = batch)),
        )
    }

    private fun out(done: Int, failures: List<BatchFailure> = emptyList(), cancelled: Boolean = false) =
        BatchSummary(Direction.OUTGOING, "b", 20, done, failures, cancelled)

    private fun inc(done: Int, cancelled: Boolean = false) =
        BatchSummary(Direction.INCOMING, "b", 20, done, cancelled = cancelled)

    @Test
    fun senderSummaries() {
        val mac = "MacBook Air"
        val two = listOf(BatchFailure("a.jpg", "disk full"), BatchFailure("b.jpg", "cancelled by receiver"))
        assertEquals("Sent 20 files" to "To MacBook Air", TransferText.summary(out(20), mac))
        assertEquals(
            "Sent 18 of 20 files" to "2 failed:\na.jpg (disk full)\nb.jpg (cancelled by receiver)",
            TransferText.summary(out(18, two), mac),
        )
        assertEquals(
            "Could not send 20 files" to "2 failed:\na.jpg (disk full)\nb.jpg (cancelled by receiver)",
            TransferText.summary(out(0, two), mac),
        )
        assertEquals("Sent 5 of 20 files" to "Cancelled", TransferText.summary(out(5, cancelled = true), mac))
        assertEquals("Could not send 20 files" to "Cancelled", TransferText.summary(out(0, cancelled = true), mac))
    }

    @Test
    fun receiverSummaries() {
        val mac = "MacBook Air"
        assertEquals("Received 20 files" to "Download › DroidDrop", TransferText.summary(inc(20), mac))
        assertEquals("Received 18 of 20 files" to "2 not received", TransferText.summary(inc(18), mac))
        assertEquals("Received 5 of 20 files" to "Cancelled on MacBook Air", TransferText.summary(inc(5, cancelled = true), mac))
        assertEquals("Could not receive 20 files" to "20 not received", TransferText.summary(inc(0), mac))
        assertEquals("Could not receive 20 files" to "Cancelled on MacBook Air", TransferText.summary(inc(0, cancelled = true), mac))
    }

    @Test
    fun exactlyFiveFailuresHaveNoMore() {
        val failures = (1..5).map { BatchFailure("f$it.jpg", "r$it") }
        assertEquals(
            "5 failed:\nf1.jpg (r1)\nf2.jpg (r2)\nf3.jpg (r3)\nf4.jpg (r4)\nf5.jpg (r5)",
            TransferText.summary(out(15, failures), "Mac").second,
        )
    }

    @Test
    fun batchProgressWithZeroSize() {
        val status = TransferStatus(Direction.OUTGOING, "e.bin", 0, 0, batch = BatchProgress(0, 2, 0, 0))
        assertEquals("Sending 1 of 2 · e.bin — 0%", TransferText.progress(status))
    }

    @Test
    fun senderSummaryListsAtMostFive() {
        val failures = (1..7).map { BatchFailure("f$it.jpg", "r$it") }
        val (title, body) = TransferText.summary(out(13, failures), "Mac")
        assertEquals("Sent 13 of 20 files", title)
        assertEquals("7 failed:\nf1.jpg (r1)\nf2.jpg (r2)\nf3.jpg (r3)\nf4.jpg (r4)\nf5.jpg (r5)\nand 2 more", body)
    }

    @Test
    fun progressPermille() {
        val batch = BatchProgress(index = 0, count = 3, bytesDone = 250, size = 1000)
        assertEquals(250, TransferText.progressPermille(TransferStatus(Direction.OUTGOING, "a", 9, 10, batch = batch)))
        assertEquals(900, TransferText.progressPermille(TransferStatus(Direction.OUTGOING, "a", 9, 10)))
        assertEquals(0, TransferText.progressPermille(TransferStatus(Direction.OUTGOING, "a", 0, 0)))
        assertEquals(0, TransferText.progressPermille(TransferStatus(Direction.OUTGOING, "a", 5, 10, batch = batch.copy(size = 0))))
    }
}
