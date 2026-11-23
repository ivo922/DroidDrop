package dev.ivo.droiddrop.core.transfer

import dev.ivo.droiddrop.core.protocol.Message
import java.util.TreeMap

/**
 * The one open incoming batch of a peer, kept above the per-session [FileReceiver] so it survives reconnects.
 * Thread-safe (R12): session threads change across reconnects.
 */
class IncomingBatches {
    private class Open(val id: String, var count: Int) {
        /** Received file index to where it was saved; a repeated index (a re-offer) counts once. */
        val received = TreeMap<Long, String>()
    }

    private var open: Open? = null

    /** Call for every offer. Returns the fallback summary (silent) of a different open batch that this offer ends, else null. */
    @Synchronized
    fun offer(offer: Message.FileOffer): BatchSummary? {
        val batch = offer.batch
        val current = open
        if (current != null && current.id == batch?.id) {
            current.count = batch.count.toIntClamped()
            current.received.tailMap(batch.count, true).clear()
            return null
        }
        open = batch?.let { Open(it.id, it.count.toIntClamped()) }
        return current?.let { summary(it, it.count, minOf(it.received.size, it.count), cancelled = false, silent = true) }
    }

    /** Records a `Received` outcome tagged with the open batch (index + location). Anything else is ignored. */
    @Synchronized
    fun outcome(outcome: TransferOutcome) {
        val batch = outcome.batch ?: return
        val current = open?.takeIf { it.id == batch.id } ?: return
        if (outcome is TransferOutcome.Received) current.received[batch.index] = outcome.location
    }

    /** Summary from batch_end's count/sent/cancelled plus the locations seen, or null for an unknown batch. */
    @Synchronized
    fun end(end: Message.BatchEnd): BatchSummary? {
        val current = open?.takeIf { it.id == end.batch } ?: return null
        open = null
        return summary(current, end.count.toIntClamped(), end.sent.toIntClamped(), end.cancelled, silent = false)
    }

    /** Drops the open batch without a summary (unpair). */
    @Synchronized
    fun clear() {
        open = null
    }

    private fun summary(current: Open, count: Int, done: Int, cancelled: Boolean, silent: Boolean) = BatchSummary(
        Direction.INCOMING, current.id, count, done, cancelled = cancelled, silent = silent,
        locations = current.received.values.toList(),
    )
}

internal fun Long.toIntClamped(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
