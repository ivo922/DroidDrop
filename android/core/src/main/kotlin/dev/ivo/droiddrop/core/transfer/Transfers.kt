package dev.ivo.droiddrop.core.transfer

import dev.ivo.droiddrop.core.protocol.BatchInfo
import java.io.File
import java.nio.file.Files

enum class Direction { INCOMING, OUTGOING }

/** Where the active file sits in its batch. [index] is 0-based; [bytesDone] is clamped to [size]. */
data class BatchProgress(val index: Int, val count: Int, val bytesDone: Long, val size: Long)

/** Progress of the transfer in one direction, for the notification. */
data class TransferStatus(
    val direction: Direction,
    val name: String,
    val bytesDone: Long,
    val size: Long,
    /** Files waiting behind this one (outgoing only). */
    val queued: Int = 0,
    /** Set while the file belongs to a batch. */
    val batch: BatchProgress? = null,
)

/** How a transfer ended, for completion notifications. */
sealed interface TransferOutcome {
    /** The batch the file belonged to, or null. */
    val batch: BatchInfo?

    data class Received(val name: String, val location: String, override val batch: BatchInfo? = null) : TransferOutcome
    data class Sent(val name: String, override val batch: BatchInfo? = null) : TransferOutcome
    data class Failed(
        val direction: Direction,
        val name: String,
        val reason: String,
        override val batch: BatchInfo? = null,
    ) : TransferOutcome
}

data class BatchFailure(val name: String, val reason: String)

/** How a batch ended, for its one summary notification. */
data class BatchSummary(
    val direction: Direction,
    val batch: String,
    val count: Int,
    val done: Int,
    val failures: List<BatchFailure> = emptyList(),
    val cancelled: Boolean = false,
    val silent: Boolean = false,
    /** Receiver: where the received files were saved. */
    val locations: List<String> = emptyList(),
    /** Sender: failed files kept open for Retry. The collector owns them and must release them. */
    val retry: List<OutgoingFile> = emptyList(),
)

/** Where a verified inbound file ends up. */
fun interface Destination {
    /** Moves or copies a verified [file] into place as [name] (already sanitized). Returns where it went. May throw anything. */
    fun save(file: File, name: String): String

    /** Whether a location that [save] returned still exists. The default reads it as a file path. May throw. */
    fun exists(location: String): Boolean = File(location).isFile
}

/** Saves into a plain directory with [FileName.unique]; returns the absolute path. For devcli and tests. */
class DirectoryDestination(private val directory: File) : Destination {
    override fun save(file: File, name: String): String {
        Files.createDirectories(directory.toPath())
        val target = File(directory, FileName.unique(name) { File(directory, it).exists() })
        Files.move(file.toPath(), target.toPath())
        return target.absolutePath
    }
}
