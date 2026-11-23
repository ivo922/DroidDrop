package dev.ivo.droiddrop

import dev.ivo.droiddrop.core.transfer.OutgoingFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicInteger

class RetrySetTest {
    @TempDir
    lateinit var dir: File

    private val releases = AtomicInteger()

    private fun file(name: String): OutgoingFile {
        val path = File(dir, name).apply { writeText(name) }
        return OutgoingFile(name, "text/plain", FileChannel.open(path.toPath())) { releases.incrementAndGet() }
    }

    @Test
    fun replaceReleasesPrevious() {
        val set = RetrySet()
        set.replace(listOf(file("a"), file("b")))
        assertEquals(0, releases.get())
        set.replace(listOf(file("c")))
        assertEquals(2, releases.get())
        assertEquals(listOf("c"), set.take().map { it.name })
    }

    @Test
    fun takeEmptiesAndHandsOver() {
        val set = RetrySet()
        set.replace(listOf(file("a"), file("b")))
        val taken = set.take()
        assertEquals(listOf("a", "b"), taken.map { it.name })
        assertEquals(0, releases.get())
        assertTrue(set.take().isEmpty())
        set.clear()
        assertEquals(0, releases.get())
    }

    @Test
    fun takeWhenEmpty() {
        assertTrue(RetrySet().take().isEmpty())
    }

    @Test
    fun isEmptyFollowsContents() {
        val set = RetrySet()
        assertTrue(set.isEmpty())
        set.replace(listOf(file("a")))
        assertFalse(set.isEmpty())
        set.take()
        assertTrue(set.isEmpty())
    }

    @Test
    fun clearReleases() {
        val set = RetrySet()
        set.replace(listOf(file("a"), file("b")))
        set.clear()
        assertEquals(2, releases.get())
        assertTrue(set.take().isEmpty())
    }
}
