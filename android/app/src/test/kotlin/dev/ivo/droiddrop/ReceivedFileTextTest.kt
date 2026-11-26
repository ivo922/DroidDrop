package dev.ivo.droiddrop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ReceivedFileTextTest {
    private val sofia = ZoneId.of("Europe/Sofia")
    private fun at(text: String) = LocalDateTime.parse(text).atZone(sofia).toInstant().toEpochMilli()
    private val now = at("2026-11-13T15:00")

    @Test
    fun extensionLabels() {
        assertEquals("JPG", ReceivedFileText.extension("IMG_2041.JPG"))
        assertEquals("GZ", ReceivedFileText.extension("archive.tar.gz"))
        assertEquals("", ReceivedFileText.extension("README"))
        assertEquals("", ReceivedFileText.extension(".profile"))
        assertEquals("", ReceivedFileText.extension("draft."))
    }

    @Test
    fun dateLabels() {
        assertEquals("Today 14:02", ReceivedFileText.date(at("2026-11-13T14:02"), now, sofia))
        assertEquals("Today 00:00", ReceivedFileText.date(at("2026-11-13T00:00"), now, sofia))
        assertEquals("Yesterday 23:59", ReceivedFileText.date(at("2026-11-12T23:59"), now, sofia))
        assertEquals("Yesterday 09:15", ReceivedFileText.date(at("2026-11-12T09:15"), now, sofia))
        assertEquals("3 Nov", ReceivedFileText.date(at("2026-11-03T10:00"), now, sofia))
        assertEquals("30 Dec 2025", ReceivedFileText.date(at("2025-12-30T10:00"), now, sofia))
        assertEquals("14 Nov", ReceivedFileText.date(at("2026-11-14T08:00"), now, sofia))
    }

    @Test
    fun detailLine() {
        assertEquals("JPG · Today 14:02", ReceivedFileText.detail("IMG_2041.jpg", at("2026-11-13T14:02"), now, sofia))
        assertEquals("Yesterday 09:15", ReceivedFileText.detail("README", at("2026-11-12T09:15"), now, sofia))
    }

    @Test
    fun openFailureMessages() {
        assertEquals("No app can open README.", ReceivedFileText.openFailure("README", fileGone = false))
        assertEquals("report.pdf is no longer available.", ReceivedFileText.openFailure("report.pdf", fileGone = true))
    }

    @Test
    fun placeholderOnlyOnceLoaded() {
        // Before the first query returns there is no answer yet, so no "nothing here" text may flash.
        assertEquals(null, ReceivedFileText.placeholder(loaded = false, empty = true))
        assertEquals("Files you receive from your Mac appear here.", ReceivedFileText.placeholder(loaded = true, empty = true))
        assertEquals(null, ReceivedFileText.placeholder(loaded = true, empty = false))
    }

    @Test
    fun fileKinds() {
        assertEquals(FileKind.IMAGE, FileKind.of("image/jpeg", "a.jpg"))
        assertEquals(FileKind.VIDEO, FileKind.of("video/mp4", "a.mp4"))
        assertEquals(FileKind.AUDIO, FileKind.of("audio/mpeg", "a.mp3"))
        assertEquals(FileKind.DOCUMENT, FileKind.of("application/pdf", "report.pdf"))
        assertEquals(FileKind.DOCUMENT, FileKind.of("text/markdown", "notes.md"))
        assertEquals(FileKind.ARCHIVE, FileKind.of("application/octet-stream", "archive.tar.gz"))
        assertEquals(FileKind.OTHER, FileKind.of(null, "README"))
        assertTrue(FileKind.IMAGE.hasThumbnail && FileKind.VIDEO.hasThumbnail)
        assertFalse(FileKind.DOCUMENT.hasThumbnail)
    }

    @Test
    fun countLabels() {
        assertEquals("1 file", ReceivedFileText.count(1))
        assertEquals("7 files", ReceivedFileText.count(7))
    }

    @Test
    fun timeDetailLabels() {
        assertEquals("JPG · 14:02", ReceivedFileText.timeDetail("IMG_2041.jpg", at("2026-11-13T14:02"), sofia))
        assertEquals("09:15", ReceivedFileText.timeDetail("README", at("2026-11-12T09:15"), sofia))
    }

    @Test
    fun sheetDetailIncludesSize() {
        assertEquals(
            "JPG · Today 14:02 · 2.4 MB",
            ReceivedFileText.sheetDetail("IMG_2041.jpg", at("2026-11-13T14:02"), now, sofia, "2.4 MB"),
        )
        assertEquals(
            "Yesterday 09:15 · 12 KB",
            ReceivedFileText.sheetDetail("README", at("2026-11-12T09:15"), now, sofia, "12 KB"),
        )
    }

    @Test
    fun actionMessages() {
        assertEquals(
            "\"report.pdf\" will be permanently deleted from Download/DroidDrop.",
            ReceivedFileText.deleteConfirmation("report.pdf"),
        )
        assertEquals(null, ReceivedFileText.deleteMessage("report.pdf", ReceivedFiles.DeleteResult.DELETED))
        assertEquals(
            "report.pdf is no longer available.",
            ReceivedFileText.deleteMessage("report.pdf", ReceivedFiles.DeleteResult.ALREADY_GONE),
        )
        assertEquals(
            "Could not delete report.pdf.",
            ReceivedFileText.deleteMessage("report.pdf", ReceivedFiles.DeleteResult.FAILED),
        )
        assertEquals("No file manager app found.", ReceivedFileText.folderFailure())
    }

    @Test
    fun actionLabels() {
        assertEquals("File actions", ReceivedFileText.ACTIONS_LABEL)
        assertEquals("Open with", ReceivedFileText.OPEN_WITH_TITLE)
        assertEquals("Share", ReceivedFileText.ACTION_SHARE)
        assertEquals("Open with…", ReceivedFileText.ACTION_OPEN_WITH)
        assertEquals("Open in folder", ReceivedFileText.ACTION_OPEN_FOLDER)
        assertEquals("Delete", ReceivedFileText.ACTION_DELETE)
        assertEquals("Delete file?", ReceivedFileText.DELETE_DIALOG_TITLE)
        assertEquals("Cancel", ReceivedFileText.ACTION_CANCEL)
    }
}
