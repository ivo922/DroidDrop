package dev.ivo.droiddrop

/**
 * Least-recently-used cache bounded by total size, so a row scrolled back into view shows its thumbnail at once
 * instead of reloading it. A value larger than the whole budget is not kept. Thread-safe.
 */
class ThumbnailCache<K : Any, V : Any>(private val maxBytes: Long, private val sizeOf: (V) -> Long) {
    private val entries = LinkedHashMap<K, V>(16, 0.75f, true) // access order: first entry is the least recent
    private var bytes = 0L

    @Synchronized
    fun get(key: K): V? = entries[key]

    @Synchronized
    fun put(key: K, value: V) {
        entries.remove(key)?.let { bytes -= sizeOf(it) }
        val size = sizeOf(value)
        if (size > maxBytes) return
        entries[key] = value
        bytes += size
        val oldest = entries.entries.iterator()
        while (bytes > maxBytes && oldest.hasNext()) {
            bytes -= sizeOf(oldest.next().value)
            oldest.remove()
        }
    }
}
