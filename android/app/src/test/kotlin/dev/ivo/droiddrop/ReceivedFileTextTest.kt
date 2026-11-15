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
}
