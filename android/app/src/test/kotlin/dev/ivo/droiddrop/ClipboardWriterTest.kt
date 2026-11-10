package dev.ivo.droiddrop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClipboardWriterTest {
    @Test
    fun writesThroughToTheClipboard() {
        val written = mutableListOf<String>()
        assertTrue(ClipboardWriter { written += it }.write("from mac"))
        assertEquals(listOf("from mac"), written)
    }

    @Test
    fun reportsFailureInsteadOfThrowing() {
        assertFalse(ClipboardWriter { throw IllegalStateException("binder transaction too large") }.write("x"))
    }
}
