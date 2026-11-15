package dev.ivo.droiddrop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ThumbnailCacheTest {
    private fun cache(maxBytes: Long) = ThumbnailCache<String, String>(maxBytes) { it.length.toLong() }

    @Test
    fun returnsWhatWasPut() {
        val cache = cache(100)
        cache.put("a", "aaaa")
        assertEquals("aaaa", cache.get("a"))
        assertNull(cache.get("b"))
    }

    @Test
    fun evictsLeastRecentlyUsedOverBudget() {
        val cache = cache(10)
        cache.put("a", "aaaa") // 4
        cache.put("b", "bbbb") // 8
        cache.get("a") // a is now the most recent
        cache.put("c", "cccc") // 12 > 10: drop b, the least recently used
        assertEquals("aaaa", cache.get("a"))
        assertNull(cache.get("b"))
        assertEquals("cccc", cache.get("c"))
    }

    @Test
    fun replacingAKeyReleasesItsOldSize() {
        val cache = cache(10)
        cache.put("a", "aaaaaaaa") // 8
        cache.put("a", "aa") // 2, not 10
        cache.put("b", "bbbbbbbb") // 10: fits, nothing evicted
        assertEquals("aa", cache.get("a"))
        assertEquals("bbbbbbbb", cache.get("b"))
    }

    @Test
    fun skipsAValueLargerThanTheWholeBudget() {
        val cache = cache(4)
        cache.put("a", "aa")
        cache.put("big", "bbbbbbbbbb")
        assertNull(cache.get("big"))
        assertEquals("aa", cache.get("a"))
    }
}
