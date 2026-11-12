package dev.ivo.droiddrop.core.transfer

/** Receiver-side file name handling (SPEC §9.1). */
object FileName {
    const val MAX_BYTES = 200
    const val MAX_EXTENSION_BYTES = 16

    /** SPEC §9.1 rules 1–4. */
    fun sanitize(name: String): String {
        val replaced = buildString {
            name.codePoints().forEach { cp ->
                val forbidden = cp <= 0x1F || cp == 0x7F || cp == '/'.code || cp == '\\'.code || cp == ':'.code
                appendCodePoint(if (forbidden) '_'.code else cp)
            }
        }
        // NEL (U+0085) is not isWhitespace() on the JVM, but Swift trims it; trim it here too so both sides agree.
        val stripped = replaced.trim { it.isWhitespace() || it == '\u0085' }.trimStart('.')
        if (stripped.isEmpty()) return "file"
        if (utf8Length(stripped) <= MAX_BYTES) return stripped

        val (stem, ext) = split(stripped)
        if (ext.isNotEmpty() && utf8Length(ext) <= MAX_EXTENSION_BYTES) {
            return cut(stem, MAX_BYTES - utf8Length(ext)) + ext
        }
        return cut(stripped, MAX_BYTES)
    }

    /** SPEC §9.1 rule 5: [name] if free, else `stem (n)ext` with the smallest free n ≥ 1. */
    fun unique(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        val (stem, ext) = split(name)
        var n = 1
        while (exists("$stem ($n)$ext")) n++
        return "$stem ($n)$ext"
    }

    /** Splits at the last `.` (kept with the extension) unless that `.` is the first character. */
    private fun split(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) name to "" else name.substring(0, dot) to name.substring(dot)
    }

    private fun utf8Length(s: String): Int = s.codePoints().map(::utf8Width).sum()

    private fun utf8Width(cp: Int): Int = when {
        cp < 0x80 -> 1
        cp < 0x800 -> 2
        cp < 0x10000 -> 3
        else -> 4
    }

    /** Longest prefix of whole code points that fits in [limit] UTF-8 bytes. */
    private fun cut(s: String, limit: Int): String {
        var bytes = 0
        val result = StringBuilder()
        for (cp in s.codePoints().toArray()) {
            bytes += utf8Width(cp)
            if (bytes > limit) break
            result.appendCodePoint(cp)
        }
        return result.toString()
    }
}
