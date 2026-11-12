package dev.ivo.droiddrop.core.transfer

import java.io.File

/** Where inbound transfers keep their unfinished bytes, so a dropped session can resume (SPEC §9, R1). */
class PartialStore(val directory: File) {
    /** `<directory>/<sha256>-<size>.part` (R1) */
    fun file(sha256: String, size: Long): File = File(directory, "$sha256-$size.$EXTENSION")

    /**
     * `<directory>/<sha256>-<size>.done`: the UTF-8 location a finished transfer was saved to, so a `file_result` lost
     * with the session does not make the sender's re-offer save the file twice.
     */
    fun completionMarker(sha256: String, size: Long): File = File(directory, "$sha256-$size.$COMPLETED_EXTENSION")

    /** Length of the retained partial, else 0. Deletes a partial longer than [size] first. */
    fun resumeOffset(sha256: String, size: Long): Long {
        val partial = file(sha256, size)
        if (!partial.isFile) return 0
        val length = partial.length()
        if (length > size) {
            partial.delete()
            return 0
        }
        return length
    }

    /** Records where a verified file was saved. Best effort: without a marker a re-offer just saves it again. */
    fun markCompleted(sha256: String, size: Long, location: String) {
        runCatching { completionMarker(sha256, size).writeText(location, Charsets.UTF_8) }
    }

    /**
     * The location of a transfer completed less than [COMPLETION_MAX_AGE_MILLIS] before [nowMillis] whose saved file
     * still [exists], else null. A marker that is expired, unreadable or points at a missing file is deleted.
     */
    fun completedLocation(
        sha256: String,
        size: Long,
        exists: (String) -> Boolean,
        nowMillis: Long = System.currentTimeMillis(),
    ): String? {
        val marker = completionMarker(sha256, size)
        if (!marker.isFile) return null
        val location = runCatching { marker.readText(Charsets.UTF_8) }.getOrNull()
        val fresh = nowMillis - marker.lastModified() < COMPLETION_MAX_AGE_MILLIS
        if (location != null && fresh && runCatching { exists(location) }.getOrDefault(false)) return location
        marker.delete()
        return null
    }

    /** Deletes `.part` files older than [MAX_AGE_MILLIS] and `.done` markers older than [COMPLETION_MAX_AGE_MILLIS]. */
    fun prune(nowMillis: Long = System.currentTimeMillis()) {
        for (file in directory.listFiles().orEmpty()) {
            if (!file.isFile) continue
            val maxAge = when (file.extension) {
                EXTENSION -> MAX_AGE_MILLIS
                COMPLETED_EXTENSION -> COMPLETION_MAX_AGE_MILLIS
                else -> continue
            }
            if (nowMillis - file.lastModified() > maxAge) file.delete()
        }
    }

    companion object {
        const val MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000
        const val COMPLETION_MAX_AGE_MILLIS = 10L * 60 * 1000
        private const val EXTENSION = "part"
        private const val COMPLETED_EXTENSION = "done"
    }
}
