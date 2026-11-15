package dev.ivo.droiddrop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReceivedFilesTest {
    @Test
    fun savesAndListsTheSameFolder() {
        // MediaStore stores RELATIVE_PATH with a trailing slash (seen on device: `Download/DroidDrop/`).
        assertEquals("Download/DroidDrop/", ReceivedFiles.RELATIVE_PATH)
    }

    @Test
    fun breaksSameSecondTiesByNewestRow() {
        // DATE_ADDED has one-second resolution: files of one multi-file drop must not swap places between refreshes.
        assertEquals("date_added DESC, _id DESC", ReceivedFiles.SORT_ORDER)
    }
}
