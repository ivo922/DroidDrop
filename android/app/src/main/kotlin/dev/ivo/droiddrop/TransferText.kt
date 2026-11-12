package dev.ivo.droiddrop

import dev.ivo.droiddrop.core.transfer.Direction
import dev.ivo.droiddrop.core.transfer.TransferOutcome
import dev.ivo.droiddrop.core.transfer.TransferStatus

/** Notification wording for transfers. Pure, so it can be unit tested. */
object TransferText {
    fun progress(status: TransferStatus): String {
        val verb = if (status.direction == Direction.INCOMING) "Receiving" else "Sending"
        val percent = if (status.size > 0) status.bytesDone * 100 / status.size else 0
        val queued = if (status.queued > 0) " (+${status.queued} queued)" else ""
        return "$verb ${status.name} — $percent%$queued"
    }

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
