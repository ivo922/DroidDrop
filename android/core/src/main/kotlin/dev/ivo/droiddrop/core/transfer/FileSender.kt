package dev.ivo.droiddrop.core.transfer

import dev.ivo.droiddrop.core.protocol.Frame
import dev.ivo.droiddrop.core.protocol.FrameLimits
import dev.ivo.droiddrop.core.protocol.Message
import dev.ivo.droiddrop.core.session.MessageSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.NonReadableChannelException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** A file to send. Owns [channel] until [release] (idempotent: closes the channel, then runs onRelease). */
class OutgoingFile(val name: String, val mime: String, val channel: FileChannel, private val onRelease: () -> Unit = {}) {
    private val released = AtomicBoolean(false)

    fun release() {
        if (!released.compareAndSet(false, true)) return
        try {
            channel.close()
        } catch (_: IOException) {
            // Nothing left to do with it.
        } finally {
            onRelease()
        }
    }
}

/**
 * Outbound queue (SPEC §9). [enqueue] and [cancelAll] may be called from any thread; [run] drives one session at a
 * time. Chunks are written with blocking sends on [Dispatchers.IO], so the socket is the flow control (no window).
 */
class FileSender(private val newId: () -> String = { UUID.randomUUID().toString() }) {
    private class Item(val file: OutgoingFile) {
        /** Computed once, kept across re-offers (R4). Only [run] touches it. */
        @Volatile var digest: Digest? = null

        /** Set by [cancelAll] while [run] owns this item; [run] then sends `file_cancel` and reports it. */
        @Volatile var cancelled = false
    }

    private class Digest(val size: Long, val sha256: String)

    /** The session's link failed or its replies channel closed: the session is over. */
    private class LinkLost(cause: Throwable? = null) : Exception(cause)

    private val lock = Any()
    private val items = ArrayDeque<Item>() // guarded by lock; the head is the active file
    private var active: Item? = null // guarded by lock; the head while run is working on it

    /** Wakes [run] after [enqueue] or [cancelAll]. A stale signal only costs one extra check. */
    private val signal = Channel<Unit>(Channel.CONFLATED)

    private val mutableStatus = MutableStateFlow<TransferStatus?>(null)
    private val mutablePending = MutableStateFlow(0)
    private val mutableOutcomes = MutableSharedFlow<TransferOutcome>(extraBufferCapacity = 64)

    /** Active file, or null. */
    val status: StateFlow<TransferStatus?> = mutableStatus.asStateFlow()

    /** Files queued, including the active one. */
    val pending: StateFlow<Int> = mutablePending.asStateFlow()

    /** extraBufferCapacity = 64; emitting never suspends. */
    val outcomes: SharedFlow<TransferOutcome> = mutableOutcomes.asSharedFlow()

    fun enqueue(files: List<OutgoingFile>) {
        if (files.isEmpty()) return
        synchronized(lock) {
            files.mapTo(items, ::Item)
            mutablePending.value = items.size
        }
        signal.trySend(Unit)
    }

    /** file_cancel for the active file (Failed "cancelled"), then releases and drops the rest (R10). Any thread. */
    fun cancelAll() {
        val dropped = synchronized(lock) {
            val current = active
            current?.cancelled = true
            val rest = items.filter { it !== current }
            items.clear()
            mutablePending.value = 0
            rest
        }
        signal.trySend(Unit)
        dropped.forEach { it.file.release() }
    }

    /**
     * Sends queued files over one established session until cancelled; waits for more when the queue is empty.
     * [replies] carries this session's file_accept / file_reject / file_result / file_cancel.
     * When cancelled (session ended), the unfinished head stays queued and unreleased (R4).
     */
    suspend fun run(link: MessageSender, replies: ReceiveChannel<Message>) {
        withContext(Dispatchers.IO) {
            while (true) {
                val item = claimHead()
                if (item == null) {
                    while (replies.tryReceive().isSuccess) continue // replies for nothing active
                    signal.receive()
                    continue
                }
                val outcome = try {
                    sendHead(item, link, replies)
                } catch (e: Throwable) {
                    endSession(item)
                    if (e is LinkLost) awaitCancellation()
                    throw e
                }
                finish(item, outcome)
            }
        }
    }

    private fun claimHead(): Item? = synchronized(lock) { items.firstOrNull()?.also { active = it } }

    /** Offers [item], streams it and waits for the verdict. Throws [LinkLost] or CancellationException when the session ends. */
    private suspend fun sendHead(item: Item, link: MessageSender, replies: ReceiveChannel<Message>): TransferOutcome {
        val file = item.file
        fun failed(reason: String) = TransferOutcome.Failed(Direction.OUTGOING, file.name, reason)

        val digest = item.digest ?: run {
            val size = readOrNull { file.channel.size() } ?: return failed(UNREADABLE)
            publish(file, 0, size)
            val sha256 = readOrNull { FileDigest.sha256(file.channel) } ?: return failed(UNREADABLE)
            Digest(size, sha256).also { item.digest = it }
        }
        val size = digest.size
        publish(file, 0, size)
        currentCoroutineContext().ensureActive()
        if (item.cancelled) return failed(CANCELLED)

        val id = newId()
        link.write(Message.FileOffer(id, file.name, size, digest.sha256, file.mime))
        var offset: Long
        while (true) {
            when (val reply = nextReply(item, id, replies)) {
                null -> return cancel(link, id, failed(CANCELLED))
                is Message.FileAccept -> {
                    offset = reply.offset
                    break
                }
                is Message.FileReject -> return failed(reply.reason)
                is Message.FileCancel -> return failed(CANCELLED_BY_RECEIVER)
                else -> continue
            }
        }

        if (offset !in 0..size) return cancel(link, id, failed(UNREADABLE))
        publish(file, offset, size)
        while (offset < size) {
            currentCoroutineContext().ensureActive()
            if (item.cancelled) return cancel(link, id, failed(CANCELLED))
            peerStop(id, replies)?.let { return failed(it) }
            val count = minOf(FrameLimits.MAX_CHUNK_DATA.toLong(), size - offset).toInt()
            val data = readOrNull { readFully(file.channel, offset, count) } ?: return cancel(link, id, failed(UNREADABLE))
            link.write(Frame.Chunk(id, offset, data))
            offset += count
            publish(file, offset, size)
        }
        link.write(Message.FileDone(id))

        while (true) {
            when (val reply = nextReply(item, id, replies)) {
                null -> return cancel(link, id, failed(CANCELLED))
                is Message.FileResult ->
                    return if (reply.ok) TransferOutcome.Sent(file.name) else failed(reply.reason ?: "failed")
                is Message.FileCancel -> return failed(CANCELLED_BY_RECEIVER)
                else -> continue
            }
        }
    }

    /** The next reply about [id], or null once [item] is cancelled. Replies about other ids are dropped. */
    private suspend fun nextReply(item: Item, id: String, replies: ReceiveChannel<Message>): Message? {
        while (true) {
            if (item.cancelled) return null
            val reply = select<Message?> {
                signal.onReceive { null }
                replies.onReceiveCatching { it.getOrNull() ?: throw LinkLost(it.exceptionOrNull()) }
            }
            if (reply != null && transferIdOf(reply) == id && !item.cancelled) return reply
        }
    }

    /** Between chunks: the reason to stop if the receiver cancelled or already failed [id], else null. */
    private fun peerStop(id: String, replies: ReceiveChannel<Message>): String? {
        while (true) {
            val reply = replies.tryReceive().getOrNull() ?: return null
            when {
                reply is Message.FileCancel && reply.transferId == id -> return CANCELLED_BY_RECEIVER
                reply is Message.FileResult && reply.transferId == id && !reply.ok -> return reply.reason ?: "failed"
            }
        }
    }

    private fun cancel(link: MessageSender, id: String, outcome: TransferOutcome): TransferOutcome {
        link.write(Message.FileCancel(id))
        return outcome
    }

    /** The head finished with [outcome]: drop it from the queue (unless [cancelAll] already did), release it, report it. */
    private fun finish(item: Item, outcome: TransferOutcome) {
        synchronized(lock) {
            active = null
            if (items.firstOrNull() === item) items.removeFirst()
            mutablePending.value = items.size
        }
        item.file.release()
        mutableStatus.value = null
        mutableOutcomes.tryEmit(outcome)
    }

    /** The session ended mid-file: the head stays queued for the next session, unless [cancelAll] dropped it meanwhile. */
    private fun endSession(item: Item) {
        val dropped = synchronized(lock) {
            active = null
            item.cancelled
        }
        mutableStatus.value = null
        if (dropped) {
            item.file.release()
            mutableOutcomes.tryEmit(TransferOutcome.Failed(Direction.OUTGOING, item.file.name, CANCELLED))
        }
    }

    private fun publish(file: OutgoingFile, bytesDone: Long, size: Long) {
        val queued = (mutablePending.value - 1).coerceAtLeast(0)
        mutableStatus.value = TransferStatus(Direction.OUTGOING, file.name, bytesDone, size, queued)
    }

    private fun MessageSender.write(message: Message) = try {
        send(message)
    } catch (e: IOException) {
        throw LinkLost(e)
    }

    private fun MessageSender.write(frame: Frame) = try {
        send(frame)
    } catch (e: IOException) {
        throw LinkLost(e)
    }

    private companion object {
        const val CANCELLED = "cancelled"
        const val CANCELLED_BY_RECEIVER = "cancelled by receiver"
        const val UNREADABLE = "file changed or unreadable"

        fun transferIdOf(message: Message): String? = when (message) {
            is Message.FileAccept -> message.transferId
            is Message.FileReject -> message.transferId
            is Message.FileResult -> message.transferId
            is Message.FileCancel -> message.transferId
            else -> null
        }

        /** Exactly [count] bytes from [position], or null if the file ends first. Leaves the channel's position alone. */
        fun readFully(channel: FileChannel, position: Long, count: Int): ByteArray? {
            val buffer = ByteBuffer.allocate(count)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer, position + buffer.position()) < 0) return null
            }
            return buffer.array()
        }

        /** [block]'s result, or null if the file cannot be read. */
        inline fun <T> readOrNull(block: () -> T?): T? = try {
            block()
        } catch (_: IOException) {
            null
        } catch (_: NonReadableChannelException) {
            null
        }
    }
}
