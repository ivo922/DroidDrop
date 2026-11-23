package dev.ivo.droiddrop

import dev.ivo.droiddrop.core.transfer.BatchSummary
import dev.ivo.droiddrop.core.transfer.Direction
import dev.ivo.droiddrop.core.transfer.TransferOutcome
import dev.ivo.droiddrop.core.transfer.TransferStatus

/** Notification wording for transfers. Pure, so it can be unit tested. */
object TransferText {
    const val PROGRESS_MAX = 1000

    /** Progress in thousandths of the batch when the file belongs to one, else of the file. 0 when the size is 0. */
    fun progressPermille(status: TransferStatus): Int {
        val done = status.batch?.bytesDone ?: status.bytesDone
        val size = status.batch?.size ?: status.size
        return if (size > 0) (done * PROGRESS_MAX / size).toInt() else 0
    }

    fun progress(status: TransferStatus): String {
        val verb = if (status.direction == Direction.INCOMING) "Receiving" else "Sending"
        val percent = if (status.size > 0) status.bytesDone * 100 / status.size else 0
        val queued = if (status.queued > 0) " (+${status.queued} queued)" else ""
        val batch = status.batch
        if (batch != null) {
            val batchPercent = if (batch.size > 0) batch.bytesDone * 100 / batch.size else 0
            return "$verb ${batch.index + 1} of ${batch.count} · ${status.name} — $batchPercent%$queued"
        }
        return "$verb ${status.name} — $percent%$queued"
    }

    /** Title and body of the one notification that ends a batch. [peerName] is the Mac the files went to or came from. */
    fun summary(summary: BatchSummary, peerName: String): Pair<String, String> {
        val count = summary.count
        val done = summary.done
        val files = if (count == 1) "file" else "files"
        return if (summary.direction == Direction.OUTGOING) {
            val title = when (done) {
                count -> "Sent $count $files"
                0 -> "Could not send $count $files"
                else -> "Sent $done of $count $files"
            }
            val body = when {
                summary.cancelled -> "Cancelled"
                summary.failures.isEmpty() && done >= count -> "To $peerName"
                else -> failureList(summary)
            }
            title to body
        } else {
            val title = when (done) {
                count -> "Received $count $files"
                0 -> "Could not receive $count $files"
                else -> "Received $done of $count $files"
            }
            val body = when {
                summary.cancelled && done < count -> "Cancelled on $peerName"
                done >= count -> "Download › DroidDrop"
                else -> "${count - done} not received"
            }
            title to body
        }
    }

    /** At most [MAX_LISTED] failures, one `<name> (<reason>)` per line, then `and N more` (R7). */
    private fun failureList(summary: BatchSummary): String {
        val failures = summary.failures
        val total = if (failures.isEmpty()) summary.count - summary.done else failures.size
        val lines = failures.take(MAX_LISTED).map { "${it.name} (${it.reason})" }
        val more = if (failures.size > MAX_LISTED) listOf("and ${failures.size - MAX_LISTED} more") else emptyList()
        return (listOf("$total failed:") + lines + more).joinToString("\n")
    }

    private const val MAX_LISTED = 5

    fun waiting(count: Int, macName: String): String =
        "$count ${if (count == 1) "file" else "files"} waiting for $macName"

    /** Title and body. The name is the offered (sanitized) one, MediaStore may have renamed the saved file. */
    fun outcome(outcome: TransferOutcome, macName: String): Pair<String, String> = when (outcome) {
        is TransferOutcome.Received -> "Received ${outcome.name}" to "Download › DroidDrop"
        is TransferOutcome.Sent -> "Sent ${outcome.name}" to "To $macName"
        is TransferOutcome.Failed -> {
            val verb = if (outcome.direction == Direction.INCOMING) "receive" else "send"
            "Could not $verb ${outcome.name}" to outcome.reason
        }
    }
}
