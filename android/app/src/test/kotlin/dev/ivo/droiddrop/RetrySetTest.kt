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
        set.replace("A", listOf(file("a"), file("b")))
        assertEquals(0, releases.get())
        set.replace("B", listOf(file("c")))
        assertEquals(2, releases.get())
        assertEquals(listOf("c"), set.take("B").map { it.name })
    }

    @Test
    fun takeEmptiesAndHandsOver() {
        val set = RetrySet()
        set.replace("A", listOf(file("a"), file("b")))
        val taken = set.take("A")
        assertEquals(listOf("a", "b"), taken.map { it.name })
        assertEquals(0, releases.get())
        assertTrue(set.take("A").isEmpty())
        set.clear()
        assertEquals(0, releases.get())
    }

    @Test
    fun takeWhenEmpty() {
        assertTrue(RetrySet().take("A").isEmpty())
    }

    @Test
    fun isEmptyFollowsContents() {
        val set = RetrySet()
        assertTrue(set.isEmpty())
        set.replace("A", listOf(file("a")))
        assertFalse(set.isEmpty())
        set.take("A")
        assertTrue(set.isEmpty())
    }

    @Test
    fun clearReleases() {
        val set = RetrySet()
        set.replace("A", listOf(file("a"), file("b")))
        set.clear()
        assertEquals(2, releases.get())
        assertTrue(set.take("A").isEmpty())
    }

    @Test
    fun retryOfAnotherBatchTakesNothing() {
        val set = RetrySet()
        set.replace("B", listOf(file("b")))
        assertTrue(set.take("A").isEmpty()) // a late Retry from summary A must not resend B's files
        assertFalse(set.isEmpty())
        assertEquals(listOf("b"), set.take("B").map { it.name })
    }

    @Test
    fun dismissOfAnotherBatchKeepsFiles() {
        val set = RetrySet()
        set.replace("B", listOf(file("b")))
        set.clear("A") // a late swipe of summary A must not release B's files
        assertEquals(0, releases.get())
        set.clear("B")
        assertEquals(1, releases.get())
        assertTrue(set.isEmpty())
    }
}
