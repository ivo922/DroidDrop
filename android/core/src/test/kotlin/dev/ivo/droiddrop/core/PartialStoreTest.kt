package dev.ivo.droiddrop.core

import dev.ivo.droiddrop.core.transfer.PartialStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PartialStoreTest {
    @TempDir lateinit var tmp: File
    private val sha = "ab".repeat(32)

    private fun makeStore() = PartialStore(File(tmp, "partials")).also { it.directory.mkdirs() }

    @Test fun resumeOffsetIsPartialLength() {
        val store = PartialStore(File(tmp, "partials"))
        assertEquals(0L, store.resumeOffset(sha, 10))
        store.directory.mkdirs()
        store.file(sha, 10).writeBytes(ByteArray(4))
        assertEquals("$sha-10.part", store.file(sha, 10).name)
        assertEquals(4L, store.resumeOffset(sha, 10))
    }

    @Test fun partialLongerThanSizeIsDeleted() {
        val store = makeStore()
        store.file(sha, 10).writeBytes(ByteArray(12))
        assertEquals(0L, store.resumeOffset(sha, 10))
        assertFalse(store.file(sha, 10).exists())
    }

    @Test fun pruneDeletesOnlyOldPartials() {
        val store = makeStore()
        val now = System.currentTimeMillis()
        val old = store.file(sha, 1).apply { writeBytes(ByteArray(1)) }
        val recent = store.file(sha, 2).apply { writeBytes(ByteArray(2)) }
        assertTrue(old.setLastModified(now - 8 * 86_400_000L))
        assertTrue(recent.setLastModified(now - 86_400_000L))
        store.prune(now)
        assertFalse(old.exists())
        assertTrue(recent.exists())
    }
}
