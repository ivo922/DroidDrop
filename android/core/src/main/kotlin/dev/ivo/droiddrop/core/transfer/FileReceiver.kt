package dev.ivo.droiddrop.core.transfer

import dev.ivo.droiddrop.core.protocol.BatchInfo
import dev.ivo.droiddrop.core.protocol.Frame
import dev.ivo.droiddrop.core.protocol.Message
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/** Inbound side of one session (SPEC §9). Call from one thread. Blocking file I/O. */
class FileReceiver(private val partials: PartialStore, private val destination: Destination) {
    private class Active(
        val transferId: String,
        val name: String,
        val size: Long,
        val sha256: String,
        val partial: File,
        val channel: FileChannel,
        var received: Long,
        val batch: BatchInfo?,
    )

    /** A re-offer of a file already saved, whose `file_result` was lost: accepted at its end, answered from the marker. */
    private class Completed(val transferId: String, val name: String, val location: String, val batch: BatchInfo?)

    private var active: Active? = null
    private var completed: Completed? = null

    /** The transfer in progress (sanitized name), or null. */
    @Volatile
    var status: TransferStatus? = null
        private set

    /**
     * `file_accept` at the resume offset, or `file_reject{reason: "cannot_write"}`. Supersedes an active transfer (R2).
     * A file saved less than [PartialStore.COMPLETION_MAX_AGE_MILLIS] ago that still exists is accepted at its size.
     */
    fun offer(offer: Message.FileOffer): Message {
        close()
        partials.completedLocation(offer.sha256, offer.size, destination::exists)?.let { location ->
            completed = Completed(offer.transferId, FileName.sanitize(offer.name), location, offer.batch)
            return Message.FileAccept(offer.transferId, offer.size)
        }
        val partial = partials.file(offer.sha256, offer.size)
        return try {
            Files.createDirectories(partials.directory.toPath())
            partials.resumeOffset(offer.sha256, offer.size) // drops an oversize partial before it is reopened
            val channel = FileChannel.open(
                partial.toPath(), StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.APPEND,
            )
            val current = Active(offer.transferId, FileName.sanitize(offer.name), offer.size, offer.sha256, partial, channel, channel.size(), offer.batch)
            active = current
            publishStatus(current)
            Message.FileAccept(offer.transferId, current.received)
        } catch (e: Exception) {
            Message.FileReject(offer.transferId, "cannot_write")
        }
    }

    /** Null when written or ignored; else `file_cancel` plus the failure, with the partial discarded (R3). */
    fun chunk(chunk: Frame.Chunk): Pair<Message, TransferOutcome>? {
        val current = active?.takeIf { it.transferId == chunk.transferId } ?: return null
        if (chunk.offset != current.received) return abort(current, "out_of_order")
        if (chunk.data.size > current.size - current.received) return abort(current, "too_long")
        try {
            val buffer = ByteBuffer.wrap(chunk.data)
            while (buffer.hasRemaining()) current.channel.write(buffer)
        } catch (e: Exception) {
            return abort(current, "write_failed")
        }
        current.received += chunk.data.size
        publishStatus(current)
        return null
    }

    /** `file_result` plus the outcome; null when the transfer is not active. */
    fun done(done: Message.FileDone): Pair<Message, TransferOutcome>? {
        completed?.takeIf { it.transferId == done.transferId }?.let {
            completed = null
            return Message.FileResult(it.transferId, true) to TransferOutcome.Received(it.name, it.location, it.batch)
        }
        val current = active?.takeIf { it.transferId == done.transferId } ?: return null
        active = null
        status = null
        runCatching { current.channel.close() }

        fun failure(reason: String): Pair<Message, TransferOutcome> {
            current.partial.delete()
            return Message.FileResult(current.transferId, false, reason) to
                TransferOutcome.Failed(Direction.INCOMING, current.name, reason, current.batch)
        }

        if (current.received != current.size) return failure("size_mismatch")
        val digest = try {
            if (current.partial.length() != current.size) return failure("size_mismatch")
            FileDigest.sha256(current.partial)
        } catch (e: Exception) {
            return failure("save_failed")
        }
        if (digest != current.sha256) return failure("hash_mismatch")

        val location = try {
            destination.save(current.partial, current.name)
        } catch (e: Exception) {
            return failure("save_failed")
        }
        partials.markCompleted(current.sha256, current.size, location)
        current.partial.delete() // a copying destination leaves it behind
        return Message.FileResult(current.transferId, true) to TransferOutcome.Received(current.name, location, current.batch)
    }

    /** The peer's `file_cancel`: stops the transfer, keeps the partial. */
    fun cancel(transferId: String) {
        if (active?.transferId == transferId || completed?.transferId == transferId) close()
    }

    /** Session ended: closes the partial file and keeps it (R4). */
    fun close() {
        active?.let { runCatching { it.channel.close() } }
        active = null
        completed = null
        status = null
    }

    /** Ends the transfer with `file_cancel`, discarding the partial (R3). */
    private fun abort(current: Active, reason: String): Pair<Message, TransferOutcome> {
        close()
        current.partial.delete()
        return Message.FileCancel(current.transferId) to TransferOutcome.Failed(Direction.INCOMING, current.name, reason, current.batch)
    }

    private fun publishStatus(current: Active) {
        val batch = current.batch?.let {
            BatchProgress(it.index.toIntClamped(), it.count.toIntClamped(), (it.before + current.received).coerceIn(0, it.size), it.size)
        }
        status = TransferStatus(Direction.INCOMING, current.name, current.received, current.size, batch = batch)
    }
}
