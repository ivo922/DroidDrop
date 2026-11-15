package dev.ivo.droiddrop

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Labels for a row of the received files list. Pure, so it can be unit tested. */
object ReceivedFileText {
    // English and 24-hour whatever the device locale: the app's text is English-only (R1).
    private val time = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val dayMonthYear = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    /** Upper-case extension after the last non-leading `.`, as in FileName; "" when there is none. */
    fun extension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) "" else name.substring(dot + 1).uppercase(Locale.ROOT)
    }

    /** "Today 14:02", "Yesterday 09:15", "3 Nov", or "30 Dec 2025" for an earlier year (R2). */
    fun date(addedMillis: Long, nowMillis: Long, zone: ZoneId): String {
        val added = Instant.ofEpochMilli(addedMillis).atZone(zone)
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        return when {
            added.toLocalDate() == today -> "Today ${time.format(added)}"
            added.toLocalDate() == today.minusDays(1) -> "Yesterday ${time.format(added)}"
            added.year == today.year -> dayMonth.format(added)
            else -> dayMonthYear.format(added)
        }
    }

    /** The "nothing here" text: only once the list has loaded and is empty, so it never flashes before the first query. */
    fun placeholder(loaded: Boolean, empty: Boolean): String? =
        if (loaded && empty) "Files you receive from your Mac appear here." else null

    /** The count beside the Received heading: "1 file", "7 files". */
    fun count(files: Int): String = if (files == 1) "1 file" else "$files files"

    /** Toast when a row cannot be opened: no viewer app (R7), or the file was deleted since the list was loaded. */
    fun openFailure(name: String, fileGone: Boolean): String =
        if (fileGone) "$name is no longer available." else "No app can open $name."

    /** "JPG · 14:02" under a day heading on the View all screen, or just the time when the name has no extension. */
    fun timeDetail(name: String, addedMillis: Long, zone: ZoneId): String {
        val clock = time.format(Instant.ofEpochMilli(addedMillis).atZone(zone))
        val extension = extension(name)
        return if (extension.isEmpty()) clock else "$extension · $clock"
    }

    /** "JPG · Today 14:02", or just the date when the name has no extension. */
    fun detail(name: String, addedMillis: Long, nowMillis: Long, zone: ZoneId): String {
        val date = date(addedMillis, nowMillis, zone)
        val extension = extension(name)
        return if (extension.isEmpty()) date else "$extension · $date"
    }
}

/** What a row shows in place of a thumbnail (R3), and whether to try loading one. */
enum class FileKind(val symbol: String) {
    IMAGE("🖼"), VIDEO("🎬"), AUDIO("🎵"), DOCUMENT("📄"), ARCHIVE("🗜"), OTHER("📎");

    val hasThumbnail: Boolean get() = this == IMAGE || this == VIDEO

    companion object {
        private val documents = setOf("pdf", "doc", "docx", "txt", "md", "rtf", "odt", "xls", "xlsx", "csv", "ppt", "pptx", "pages", "numbers", "key")
        private val archives = setOf("zip", "gz", "tgz", "tar", "7z", "rar", "bz2", "xz", "dmg")

        /** By mime prefix first (`text/` counts as a document), else by the lower-cased extension. */
        fun of(mime: String?, name: String): FileKind {
            when {
                mime == null -> Unit
                mime.startsWith("image/") -> return IMAGE
                mime.startsWith("video/") -> return VIDEO
                mime.startsWith("audio/") -> return AUDIO
                mime.startsWith("text/") -> return DOCUMENT
            }
            val extension = ReceivedFileText.extension(name).lowercase(Locale.ROOT)
            return when (extension) {
                in documents -> DOCUMENT
                in archives -> ARCHIVE
                else -> OTHER
            }
        }
    }
}
