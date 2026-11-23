package dev.ivo.droiddrop.core

import dev.ivo.droiddrop.core.protocol.BatchInfo
import dev.ivo.droiddrop.core.protocol.Message
import dev.ivo.droiddrop.core.transfer.BatchSummary
import dev.ivo.droiddrop.core.transfer.Direction
import dev.ivo.droiddrop.core.transfer.IncomingBatches
import dev.ivo.droiddrop.core.transfer.TransferOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class IncomingBatchesTest {
    private val batches = IncomingBatches()

    private fun info(id: String, index: Long, count: Long = 3) = BatchInfo(id, index, count, 3_000, index * 1_000)

    private fun offer(batch: BatchInfo?, n: Int = 0) =
        Message.FileOffer("t$n", "f$n", 1_000, "00", "application/octet-stream", batch)

    private fun received(batch: BatchInfo) = batches.outcome(TransferOutcome.Received("f${batch.index}", "/x/${batch.index}", batch))

    @Test fun endSummarisesFromBatchEnd() {
        val a0 = info("A", 0)
        val a1 = info("A", 1)
        assertNull(batches.offer(offer(a0)))
        received(a0)
        assertNull(batches.offer(offer(a1)))
        received(a1)
        val summary = batches.end(Message.BatchEnd("A", 3, 2))
        assertEquals(BatchSummary(Direction.INCOMING, "A", 3, 2, locations = listOf("/x/0", "/x/1")), summary)
    }

    @Test fun cancelledEnd() {
        val a0 = info("A", 0)
        batches.offer(offer(a0))
        received(a0)
        val summary = batches.end(Message.BatchEnd("A", 3, 1, cancelled = true))!!
        assertEquals(true, summary.cancelled)
        assertEquals(1, summary.done)
    }

    @Test fun unknownEndIgnored() {
        assertNull(batches.end(Message.BatchEnd("nope", 3, 1)))
        batches.offer(offer(info("A", 0)))
        assertNull(batches.end(Message.BatchEnd("B", 3, 1)))
        assertEquals(3, batches.end(Message.BatchEnd("A", 3, 1))!!.count)
    }

    @Test fun duplicateIndexCountsOnce() {
        val a1 = info("A", 1)
        batches.offer(offer(a1))
        received(a1)
        batches.offer(offer(a1))
        received(a1)
        assertEquals(listOf("/x/1"), batches.end(Message.BatchEnd("A", 3, 1))!!.locations)
    }

    @Test fun otherBatchOfferEndsOpenBatch() {
        val a0 = info("A", 0, 4)
        batches.offer(offer(a0))
        received(a0)
        batches.outcome(TransferOutcome.Failed(Direction.INCOMING, "f1", "hash_mismatch", info("A", 1, 4)))
        val summary = batches.offer(offer(info("B", 0, 2)))
        assertEquals(BatchSummary(Direction.INCOMING, "A", 4, 1, silent = true, locations = listOf("/x/0")), summary)
        val b0 = info("B", 0, 2)
        received(b0)
        assertEquals(BatchSummary(Direction.INCOMING, "B", 2, 1, locations = listOf("/x/0")), batches.end(Message.BatchEnd("B", 2, 1)))
    }

    @Test fun unbatchedOfferEndsOpenBatch() {
        val a0 = info("A", 0)
        batches.offer(offer(a0))
        received(a0)
        val summary = batches.offer(offer(null, 1))
        assertEquals(BatchSummary(Direction.INCOMING, "A", 3, 1, silent = true, locations = listOf("/x/0")), summary)
        assertNull(batches.offer(offer(null, 2)))
    }

    @Test fun endAfterFallbackIgnored() {
        batches.offer(offer(info("A", 0)))
        batches.offer(offer(null, 1))
        assertNull(batches.end(Message.BatchEnd("A", 3, 0)))
    }

    @Test fun clearDropsOpenBatch() {
        val a0 = info("A", 0)
        batches.offer(offer(a0))
        received(a0)
        batches.clear()
        assertNull(batches.offer(offer(info("B", 0))))
        assertNull(batches.end(Message.BatchEnd("A", 3, 1)))
    }

    @Test fun clearWithNothingOpen() {
        batches.clear()
        assertNull(batches.offer(offer(null)))
    }

    @Test fun fallbackNeverReportsMoreThanCount() {
        for (i in 0L..3L) {
            val b = info("A", i, 5)
            batches.offer(offer(b))
            received(b)
        }
        batches.offer(offer(info("A", 2, 3)))
        val summary = batches.offer(offer(null, 9))!!
        assertEquals(3, summary.count)
        assertEquals(3, summary.done)
        assertEquals(listOf("/x/0", "/x/1", "/x/2"), summary.locations)
    }

    @Test fun duplicateIndexCountsOnceInFallback() {
        val a1 = info("A", 1)
        batches.offer(offer(a1))
        received(a1)
        batches.offer(offer(a1))
        received(a1)
        assertEquals(1, batches.offer(offer(null, 1))!!.done)
    }

    @Test fun latestCountWinsInFallback() {
        batches.offer(offer(info("A", 0, 3)))
        batches.offer(offer(info("A", 1, 7)))
        assertEquals(7, batches.offer(offer(null, 1))!!.count)
    }

    @Test fun foreignAndUnbatchedOutcomesIgnored() {
        val a0 = info("A", 0)
        batches.offer(offer(a0))
        batches.outcome(TransferOutcome.Received("z", "/z", info("Z", 1)))
        batches.outcome(TransferOutcome.Received("u", "/u"))
        batches.outcome(TransferOutcome.Sent("s", info("A", 2)))
        assertEquals(0, batches.end(Message.BatchEnd("A", 3, 0))!!.locations.size)
    }

    @Test fun backToBackBatchesReturnNull() {
        batches.offer(offer(info("A", 0, 1)))
        assertEquals("A", batches.end(Message.BatchEnd("A", 1, 0))!!.batch)
        assertNull(batches.offer(offer(info("B", 0, 1))))
    }
}
