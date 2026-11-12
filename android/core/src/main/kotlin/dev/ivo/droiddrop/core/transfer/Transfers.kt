package dev.ivo.droiddrop.core.transfer

import java.io.File
import java.nio.file.Files

enum class Direction { INCOMING, OUTGOING }

/** Progress of the transfer in one direction, for the notification. */
data class TransferStatus(
    val direction: Direction,
    val name: String,
    val bytesDone: Long,
    val size: Long,
    /** Files waiting behind this one (outgoing only). */
    val queued: Int = 0,
)

/** How a transfer ended, for completion notifications. */
sealed interface TransferOutcome {
    data class Received(val name: String, val location: String) : TransferOutcome
    data class Sent(val name: String) : TransferOutcome
    data class Failed(val direction: Direction, val name: String, val reason: String) : TransferOutcome
}

/** Where a verified inbound file ends up. */
fun interface Destination {
    /** Moves or copies a verified [file] into place as [name] (already sanitized). Returns where it went. May throw anything. */
    fun save(file: File, name: String): String
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
