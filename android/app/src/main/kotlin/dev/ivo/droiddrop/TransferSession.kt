package dev.ivo.droiddrop

import android.util.Log
import dev.ivo.droiddrop.core.protocol.Frame
import dev.ivo.droiddrop.core.protocol.Message
import dev.ivo.droiddrop.core.session.MessageSender
import dev.ivo.droiddrop.core.transfer.BatchSummary
import dev.ivo.droiddrop.core.transfer.FileReceiver
import dev.ivo.droiddrop.core.transfer.FileSender
import dev.ivo.droiddrop.core.transfer.IncomingBatches
import dev.ivo.droiddrop.core.transfer.TransferOutcome
import dev.ivo.droiddrop.core.transfer.TransferStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * File routing for one client.run. [handle] and [chunk] run on the session's IO thread and never throw.
 * Call [close] when the session ends, however it ends: the sender's run holds a lock for as long as it lives.
 */
class TransferSession(
    private val scope: CoroutineScope,
    private val receiver: FileReceiver,
    private val sender: FileSender,
    private val onIncoming: (TransferStatus?) -> Unit,
    private val onOutcome: (TransferOutcome) -> Unit,
    private val incomingBatches: IncomingBatches,
    /** A received batch ended (or a fallback ended it). Runs on the session thread. */
    private val onBatchSummary: (BatchSummary) -> Unit,
    /** A send job that died of anything but cancellation. Logged rather than crashing the process. */
    private val onSendFailure: (Throwable) -> Unit = { Log.e(TAG, "file sender stopped", it) },
) {
    @Volatile private var link: MessageSender? = null
    private val replies = Channel<Message>(Channel.UNLIMITED)
    private var sendJob: Job? = null

    /** Starts sender.run(link, replies) on Dispatchers.IO in [scope]. */
    fun connected(link: MessageSender) {
        this.link = link
        sendJob = scope.launch(Dispatchers.IO) {
            try {
                sender.run(link, replies)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                onSendFailure(e) // the scope has no handler: an escaping throwable would crash the process
            }
        }
    }

    /** True when [message] is a file message (handled here). */
    fun handle(message: Message): Boolean {
        when (message) {
            is Message.FileOffer -> {
                var ended: BatchSummary? = null
                guarded {
                    ended = incomingBatches.offer(message)
                    reply(receiver.offer(message))
                    reportIncoming()
                }
                // Apart from the reply: posting a summary must never keep the Mac waiting for its accept.
                ended?.let { guarded { onBatchSummary(it) } }
            }
            is Message.BatchEnd -> guarded { incomingBatches.end(message)?.let(onBatchSummary) }
            is Message.FileDone -> guarded {
                receiver.done(message)?.let { (result, outcome) ->
                    reply(result)
                    record(outcome)
                }
                reportIncoming()
            }
            is Message.FileCancel -> guarded {
                receiver.cancel(message.transferId)
                replies.trySend(message)
                reportIncoming()
            }
            is Message.FileAccept, is Message.FileReject, is Message.FileResult -> guarded { replies.trySend(message) }
            else -> return false
        }
        return true
    }

    fun chunk(chunk: Frame.Chunk) = guarded {
        receiver.chunk(chunk)?.let { (cancel, outcome) ->
            reply(cancel)
            record(outcome)
        }
        reportIncoming()
    }

    /** Cancels the send job, closes the receiver (partial kept), reports incoming null. */
    fun close() {
        sendJob?.cancel()
        replies.close()
        receiver.close()
        guarded { onIncoming(null) }
    }

    /** Feeds the batch tally first, so the list refresh that [onOutcome] triggers sees it. */
    private fun record(outcome: TransferOutcome) {
        incomingBatches.outcome(outcome)
        onOutcome(outcome)
    }

    private fun reportIncoming() = onIncoming(receiver.status)

    /** A failed send means the session is over. The reader finds out by itself, so it is not an error here. */
    private fun reply(message: Message) {
        try {
            link?.send(message)
        } catch (_: IOException) {
            // The session ends on its own.
        }
    }

    /** Callbacks must not throw: an exception here would end the session. */
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            // Dropped on purpose; see above.
        }
    }

    private companion object {
        const val TAG = "TransferSession"
    }
}
