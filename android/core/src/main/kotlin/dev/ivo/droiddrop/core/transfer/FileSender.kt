package dev.ivo.droiddrop.core.transfer

import dev.ivo.droiddrop.core.protocol.BatchInfo
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * Outbound queue (SPEC §9). [enqueue] and [cancelAll] may be called from any thread. [run] drives one session at a
 * time, and the sender may outlive many sessions. Chunks are written with blocking sends on [Dispatchers.IO], so the
 * socket is the flow control (no window).
 */
class FileSender(
    private val newId: () -> String = { UUID.randomUUID().toString() },
    /** Keep failed (not cancelled) batch files open and hand them over in [BatchSummary.retry] (R5). */
    private val keepFailedForRetry: Boolean = false,
) {
    private class Item(
        val file: OutgoingFile,
        /** Set for files enqueued together with others; kept across re-offers. */
        val batch: BatchInfo? = null,
        val tracker: Tracker? = null,
        /** The file's size at enqueue time: its share of the batch progress is clamped to it. */
        val enqueuedSize: Long = 0,
    ) {
        /** Computed once, kept across re-offers (plan 04 R4). Only [run] touches it. */
        @Volatile var digest: Digest? = null

        /** Set by [cancelAll] while [run] owns this item; [run] then sends `file_cancel` and reports it. */
        @Volatile var cancelled = false

        /** Bytes last reported for this file. A re-offer shows them until `file_accept` gives the real resume point. */
        @Volatile var progress = 0L
    }

    private class Digest(val size: Long, val sha256: String)

    /** Per-batch tally. Every field is guarded by [lock]. */
    private class Tracker(val id: String, val count: Int) {
        var offered = false // a file_offer of the batch was written
        var resolved = 0 // files that reached an outcome or were dropped
        var reported = 0 // files that reached an outcome
        var sent = 0
        var cancelled = false
        var silent = false // dropped by an unpair: no summary, no batch_end
        val failures = ArrayList<BatchFailure>()
        val kept = ArrayList<OutgoingFile>() // failed files held for Retry
    }

    /** A finished batch: its summary (if any) and files to release, to be handled outside [lock]. */
    private class Completion(val summary: BatchSummary?, val release: List<OutgoingFile>)

    /** The session's link failed or its replies channel closed: the session is over. */
    private class LinkLost(cause: Throwable? = null) : Exception(cause)

    private val lock = Any()
    private val items = ArrayDeque<Item>() // guarded by lock; the head is the active file
    private var active: Item? = null // guarded by lock; the head while run is working on it
    private val pendingEnds = ArrayDeque<Message.BatchEnd>() // guarded by lock; batch_end waiting for a session

    /**
     * Held by [run] for its whole life. A cancelled run can keep going for a while (blocked writing a chunk or
     * hashing), so the next session's run waits here until the old one has put the head back.
     */
    private val running = Mutex()

    /** Wakes [run] after [enqueue] or [cancelAll]. A stale signal only costs one extra check. */
    private val signal = Channel<Unit>(Channel.CONFLATED)

    private val mutableStatus = MutableStateFlow<TransferStatus?>(null)
    private val mutablePending = MutableStateFlow(0)
    private val mutableOutcomes = MutableSharedFlow<TransferOutcome>(extraBufferCapacity = 64)
    private val mutableSummaries = MutableSharedFlow<BatchSummary>(extraBufferCapacity = 64)

    /** Active file, or null. */
    val status: StateFlow<TransferStatus?> = mutableStatus.asStateFlow()

    /** Files queued, including the active one. */
    val pending: StateFlow<Int> = mutablePending.asStateFlow()

    /** extraBufferCapacity = 64; emitting never suspends. */
    val outcomes: SharedFlow<TransferOutcome> = mutableOutcomes.asSharedFlow()

    /** One summary per batch that had an outcome (R4). extraBufferCapacity = 64; emitting never suspends. */
    val summaries: SharedFlow<BatchSummary> = mutableSummaries.asSharedFlow()

    /** Two or more files form a batch (SPEC 9.2); one file is sent unbatched. */
    fun enqueue(files: List<OutgoingFile>) {
        if (files.isEmpty()) return
        val batched = if (files.size >= 2) {
            // Smallest first, so a screenshot never waits behind a video of the same send; ties keep the picked order.
            val sized = files.map { it to (readOrNull { it.channel.size() } ?: 0L) }.sortedBy { it.second }
            val id = newId()
            val total = sized.sumOf { it.second }
            val tracker = Tracker(id, files.size)
            var before = 0L
            sized.mapIndexed { index, (file, size) ->
                val info = BatchInfo(id, index.toLong(), files.size.toLong(), total, before)
                before += size
                Item(file, info, tracker, size)
            }
        } else {
            files.map { Item(it) }
        }
        synchronized(lock) {
            items.addAll(batched)
            mutablePending.value = items.size
        }
        signal.trySend(Unit)
    }

    /**
     * file_cancel for the active file (Failed "cancelled"), then releases and drops the rest (plan 04 R10). Any thread.
     * A batch that had a file offered ends with a cancelled summary and `batch_end`. With [silent] (unpair) nothing
     * is reported and pending `batch_end`s are dropped.
     */
    fun cancelAll(silent: Boolean = false) {
        val completions = ArrayList<Completion>()
        val dropped = synchronized(lock) {
            val current = active
            current?.cancelled = true
            val rest = items.filter { it !== current }
            items.clear()
            mutablePending.value = 0
            if (silent) pendingEnds.clear()
            (rest + listOfNotNull(current)).forEach { item ->
                item.tracker?.let {
                    it.cancelled = true
                    if (silent) it.silent = true
                }
            }
            rest.forEach { resolveLocked(it, null)?.let(completions::add) }
            rest
        }
        signal.trySend(Unit)
        dropped.forEach { it.file.release() }
        completions.forEach(::publishCompletion)
    }

    /**
     * Sends queued files over one established session until cancelled; waits for more when the queue is empty.
     * [replies] carries this session's file_accept / file_reject / file_result / file_cancel.
     * When cancelled (session ended), the unfinished head stays queued and unreleased (plan 04 R4).
     *
     * One run at a time: a run started while an earlier one is still winding down after its cancellation (for
     * example, blocked in a socket write or hashing) suspends, cancellably, until that one has finished.
     */
    suspend fun run(link: MessageSender, replies: ReceiveChannel<Message>) {
        running.withLock { runSession(link, replies) }
    }

    private suspend fun runSession(link: MessageSender, replies: ReceiveChannel<Message>) {
        withContext(Dispatchers.IO) {
            while (true) {
                try {
                    flushBatchEnds(link)
                } catch (_: LinkLost) {
                    awaitCancellation() // the session is over; the entry stays queued for the next one
                }
                val claim = claimHead()
                if (claim.flushFirst) continue // a batch_end was queued meanwhile: it goes out before any offer
                val item = claim.item
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
                if (finish(item, outcome, link)) awaitCancellation() // the link died writing a batch_end
            }
        }
    }

    /** Writes the `batch_end`s waiting for a session, in order, before anything else. A failed write keeps its entry. */
    private fun flushBatchEnds(link: MessageSender) {
        while (true) {
            val end = synchronized(lock) { pendingEnds.firstOrNull() } ?: return
            link.write(end)
            synchronized(lock) { if (pendingEnds.firstOrNull() === end) pendingEnds.removeFirst() }
        }
    }

    private class Claim(val item: Item?, val flushFirst: Boolean)

    /**
     * The head to work on. Checked under [lock] together with [pendingEnds]: if a `batch_end` is waiting (a batch
     * completed, or [cancelAll] queued one, since the last flush), nothing is claimed and the caller flushes first, so
     * the next batch's offer can never overtake it.
     */
    private fun claimHead(): Claim = synchronized(lock) {
        if (pendingEnds.isNotEmpty()) Claim(null, true) else Claim(items.firstOrNull()?.also { active = it }, false)
    }

    /** Offers [item], streams it and waits for the verdict. Throws [LinkLost] or CancellationException when the session ends. */
    private suspend fun sendHead(item: Item, link: MessageSender, replies: ReceiveChannel<Message>): TransferOutcome {
        val file = item.file
        fun failed(reason: String) = TransferOutcome.Failed(Direction.OUTGOING, file.name, reason, item.batch)

        val digest = item.digest ?: run {
            val size = readOrNull { file.channel.size() } ?: return failed(UNREADABLE)
            publish(item, 0, size)
            val sha256 = readOrNull { FileDigest.sha256(file.channel) } ?: return failed(UNREADABLE)
            Digest(size, sha256).also { item.digest = it }
        }
        val size = digest.size
        publish(item, item.progress, size)
        currentCoroutineContext().ensureActive()
        if (item.cancelled) return failed(CANCELLED)

        val id = newId()
        // Before the write: a partly written offer may still have arrived, and an unknown batch_end is harmless.
        item.tracker?.let { tracker -> synchronized(lock) { tracker.offered = true } }
        link.write(Message.FileOffer(id, file.name, size, digest.sha256, file.mime, item.batch))
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
        publish(item, offset, size)
        while (offset < size) {
            currentCoroutineContext().ensureActive()
            if (item.cancelled) return cancel(link, id, failed(CANCELLED))
            peerStop(id, replies)?.let { return failed(it) }
            val count = minOf(FrameLimits.MAX_CHUNK_DATA.toLong(), size - offset).toInt()
            val data = readOrNull { readFully(file.channel, offset, count) } ?: return cancel(link, id, failed(UNREADABLE))
            link.write(Frame.Chunk(id, offset, data))
            offset += count
            publish(item, offset, size)
        }
        link.write(Message.FileDone(id))

        while (true) {
            when (val reply = nextReply(item, id, replies)) {
                null -> return cancel(link, id, failed(CANCELLED))
                is Message.FileResult ->
                    return if (reply.ok) TransferOutcome.Sent(file.name, item.batch) else failed(reply.reason ?: "failed")
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

    /**
     * The head finished with [outcome]: drop it from the queue (unless [cancelAll] already did), release it, report it.
     * If that completed its batch, the `batch_end` is written first, so a caller that stops after the last outcome
     * (devcli) has already sent it. Returns true if that write failed (the entry stays queued; the session is over).
     */
    private fun finish(item: Item, outcome: TransferOutcome, link: MessageSender): Boolean {
        var keep = false
        val completion = synchronized(lock) {
            keep = keepFailedForRetry && item.tracker != null && outcome is TransferOutcome.Failed && !item.cancelled
            active = null
            if (items.firstOrNull() === item) items.removeFirst()
            mutablePending.value = items.size
            if (keep) item.tracker!!.kept.add(item.file)
            resolveLocked(item, outcome)
        }
        if (!keep) item.file.release()
        mutableStatus.value = null
        val linkLost = try {
            flushBatchEnds(link)
            false
        } catch (_: LinkLost) {
            true
        }
        mutableOutcomes.tryEmit(outcome)
        completion?.let(::publishCompletion)
        return linkLost
    }

    /** The session ended mid-file: the head stays queued for the next session, unless [cancelAll] dropped it meanwhile. */
    private fun endSession(item: Item) {
        val outcome = TransferOutcome.Failed(Direction.OUTGOING, item.file.name, CANCELLED, item.batch)
        var completion: Completion? = null
        val dropped = synchronized(lock) {
            active = null
            item.cancelled.also { if (it) completion = resolveLocked(item, outcome) }
        }
        mutableStatus.value = null
        if (dropped) {
            item.file.release()
            mutableOutcomes.tryEmit(outcome)
            completion?.let(::publishCompletion)
        }
    }

    /**
     * Counts [item] as done in its batch ([outcome] null: dropped without one). The batch's last file completes it:
     * queues the `batch_end` and builds the summary. Call under [lock].
     */
    private fun resolveLocked(item: Item, outcome: TransferOutcome?): Completion? {
        val tracker = item.tracker ?: return null
        tracker.resolved++
        if (outcome != null) tracker.reported++
        when {
            outcome is TransferOutcome.Sent -> tracker.sent++
            outcome is TransferOutcome.Failed && !item.cancelled -> tracker.failures.add(BatchFailure(outcome.name, outcome.reason))
        }
        if (tracker.resolved < tracker.count) return null
        val keepForRetry = !tracker.cancelled && !tracker.silent
        val kept = tracker.kept.toList()
        if (tracker.silent) return Completion(null, kept)
        if (tracker.offered) {
            pendingEnds.addLast(Message.BatchEnd(tracker.id, tracker.count.toLong(), tracker.sent.toLong(), tracker.cancelled))
        }
        val summary = if (if (tracker.cancelled) tracker.offered else tracker.reported > 0) {
            BatchSummary(
                Direction.OUTGOING, tracker.id, tracker.count, tracker.sent, tracker.failures.toList(),
                cancelled = tracker.cancelled, retry = if (keepForRetry) kept else emptyList(),
            )
        } else {
            null
        }
        return Completion(summary, if (keepForRetry && summary != null) emptyList() else kept)
    }

    private fun publishCompletion(completion: Completion) {
        completion.release.forEach { it.release() }
        completion.summary?.let { summary ->
            // Nobody listening would leak the files kept for Retry: release them here.
            val delivered = mutableSummaries.subscriptionCount.value > 0 && mutableSummaries.tryEmit(summary)
            if (!delivered) summary.retry.forEach { it.release() }
        }
    }

    private fun publish(item: Item, bytesDone: Long, size: Long) {
        item.progress = bytesDone
        // Files queued after the active file's batch (after the file itself when unbatched).
        val queued = synchronized(lock) {
            val at = items.indexOf(item)
            if (at < 0) 0 else items.drop(at + 1).count { item.tracker == null || it.tracker !== item.tracker }
        }
        val batch = item.batch?.let {
            val own = bytesDone.coerceIn(0, item.enqueuedSize)
            BatchProgress(it.index.toInt(), it.count.toInt(), (it.before + own).coerceAtMost(it.size), it.size)
        }
        mutableStatus.value = TransferStatus(Direction.OUTGOING, item.file.name, bytesDone, size, queued, batch)
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
