package dev.ivo.droiddrop

import dev.ivo.droiddrop.core.transfer.OutgoingFile

/** The failed files of the last sender summary, kept open for its Retry action. Thread-safe. */
class RetrySet {
    private var files: List<OutgoingFile> = emptyList()

    /** Keeps [files], releasing the previous set. */
    fun replace(files: List<OutgoingFile>) {
        val previous = synchronized(this) {
            val old = this.files
            this.files = files
            old
        }
        previous.forEach(OutgoingFile::release)
    }

    /** Empties the set and hands it over: the caller owns the files now. */
    @Synchronized
    fun take(): List<OutgoingFile> {
        val taken = files
        files = emptyList()
        return taken
    }

    @Synchronized
    fun isEmpty(): Boolean = files.isEmpty()

    /** Releases the set. */
    fun clear() = take().forEach(OutgoingFile::release)
}
