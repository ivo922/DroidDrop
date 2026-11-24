package dev.ivo.droiddrop

import dev.ivo.droiddrop.core.transfer.OutgoingFile

/**
 * The failed files of the last sender summary, kept open for its Retry action. Thread-safe.
 *
 * Retry and Dismiss name their batch: a late broadcast from an older summary must not take or release the files of
 * the summary that replaced it.
 */
class RetrySet {
    private var batch: String? = null
    private var files: List<OutgoingFile> = emptyList()

    /** Keeps [files] for [batch], releasing the previous set. */
    fun replace(batch: String, files: List<OutgoingFile>) {
        val previous = synchronized(this) {
            val old = this.files
            this.batch = batch
            this.files = files
            old
        }
        previous.forEach(OutgoingFile::release)
    }

    /** Hands over [batch]'s files (the caller owns them now), or nothing when another batch is held. */
    @Synchronized
    fun take(batch: String): List<OutgoingFile> {
        if (batch != this.batch) return emptyList()
        return files.also { files = emptyList(); this.batch = null }
    }

    @Synchronized
    fun isEmpty(): Boolean = files.isEmpty()

    /** Releases [batch]'s files; another batch's are kept. */
    fun clear(batch: String) = take(batch).forEach(OutgoingFile::release)

    /** Releases whatever is held (unpair, a new pairing, notifications off). */
    fun clear() {
        val held = synchronized(this) { files.also { files = emptyList(); batch = null } }
        held.forEach(OutgoingFile::release)
    }
}
