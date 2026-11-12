package dev.ivo.droiddrop.core

import dev.ivo.droiddrop.core.transfer.FileName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FileNameTest {
    @Test
    fun matchesSanitizeFixtures() {
        for (entry in Fixtures.load("sanitize").list("cases")) {
            val input = entry.str("input")
            assertEquals(entry.str("output"), FileName.sanitize(input), input.take(40))
        }
    }

    @Test
    fun uniqueAddsSmallestFreeSuffix() {
        val taken = setOf("a.txt", "a (1).txt", "a (3).txt", "README", "archive.tar.gz")
        assertEquals("b.txt", FileName.unique("b.txt") { it in taken })
        assertEquals("a (2).txt", FileName.unique("a.txt") { it in taken })
        assertEquals("README (1)", FileName.unique("README") { it in taken })
        assertEquals("archive.tar (1).gz", FileName.unique("archive.tar.gz") { it in taken })
    }
}
