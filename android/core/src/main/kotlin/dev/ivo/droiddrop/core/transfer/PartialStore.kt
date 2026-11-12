package dev.ivo.droiddrop.core.transfer

import java.io.File

/** Where inbound transfers keep their unfinished bytes, so a dropped session can resume (SPEC §9, R1). */
class PartialStore(val directory: File) {
    /** `<directory>/<sha256>-<size>.part` (R1) */
    fun file(sha256: String, size: Long): File = File(directory, "$sha256-$size.$EXTENSION")

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

    /** Deletes `.part` files modified more than [MAX_AGE_MILLIS] before [nowMillis]. */
    fun prune(nowMillis: Long = System.currentTimeMillis()) {
        for (file in directory.listFiles().orEmpty()) {
            if (file.isFile && file.extension == EXTENSION && nowMillis - file.lastModified() > MAX_AGE_MILLIS) {
                file.delete()
            }
        }
    }

    companion object {
        const val MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000
        private const val EXTENSION = "part"
    }
}
