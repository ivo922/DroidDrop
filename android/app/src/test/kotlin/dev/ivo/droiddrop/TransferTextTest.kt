package dev.ivo.droiddrop

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
}
