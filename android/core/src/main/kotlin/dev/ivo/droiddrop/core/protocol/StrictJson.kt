package dev.ivo.droiddrop.core.protocol

import java.io.ByteArrayOutputStream

/**
 * Strict RFC 8259 grammar check (SPEC §4): no BOM, no duplicate member names in any object
 * (compared after unescaping), no unpaired surrogate escapes, no `-0`. Input must already be valid UTF-8.
 */
internal object StrictJson {
    const val MAX_DEPTH = 64

    fun validate(bytes: ByteArray) {
        val parser = Parser(bytes)
        parser.skipWhitespace()
        parser.value(depth = 0)
        parser.skipWhitespace()
        if (parser.peek() != END) throw parser.error("trailing data")
    }

    private const val END = -1

    private class Parser(private val bytes: ByteArray) {
        private var index = 0

        fun peek(): Int = if (index < bytes.size) bytes[index].toInt() and 0xFF else END

        fun error(reason: String) = ProtocolException("invalid_message", "invalid JSON at byte $index: $reason")

        fun skipWhitespace() {
            while (peek().let { it == 0x20 || it == 0x09 || it == 0x0A || it == 0x0D }) index++
        }

        private fun expect(char: Char) {
            if (peek() != char.code) throw error("expected '$char'")
            index++
        }

        fun value(depth: Int) {
            when (peek()) {
                '{'.code -> obj(depth + 1)
                '['.code -> array(depth + 1)
                '"'.code -> string(null)
                't'.code -> literal("true")
                'f'.code -> literal("false")
                'n'.code -> literal("null")
                else -> number()
            }
        }

        private fun obj(depth: Int) {
            if (depth > MAX_DEPTH) throw error("nesting deeper than $MAX_DEPTH")
            index++
            skipWhitespace()
            if (peek() == '}'.code) { index++; return }
            val names = HashSet<String>()
            while (true) {
                skipWhitespace()
                val name = ByteArrayOutputStream()
                string(name)
                if (!names.add(name.toString(Charsets.UTF_8))) throw error("duplicate member name")
                skipWhitespace()
                expect(':')
                skipWhitespace()
                value(depth)
                skipWhitespace()
                if (peek() == ','.code) { index++; continue }
                expect('}')
                return
            }
        }

        private fun array(depth: Int) {
            if (depth > MAX_DEPTH) throw error("nesting deeper than $MAX_DEPTH")
            index++
            skipWhitespace()
            if (peek() == ']'.code) { index++; return }
            while (true) {
                skipWhitespace()
                value(depth)
                skipWhitespace()
                if (peek() == ','.code) { index++; continue }
                expect(']')
                return
            }
        }

        /** Writes the unescaped UTF-8 bytes to [out] (if given), so escaped and literal spellings of a name compare equal. */
        private fun string(out: ByteArrayOutputStream?) {
            expect('"')
            while (true) {
                val byte = peek()
                if (byte == END) throw error("unterminated string")
                index++
                when {
                    byte == '"'.code -> return
                    byte == '\\'.code -> escape(out)
                    byte < 0x20 -> throw error("unescaped control character")
                    else -> out?.write(byte)
                }
            }
        }

        private fun escape(out: ByteArrayOutputStream?) {
            val byte = peek()
            if (byte == END) throw error("unterminated escape")
            index++
            when (byte.toChar()) {
                '"', '\\', '/' -> out?.write(byte)
                'b' -> out?.write(0x08)
                'f' -> out?.write(0x0C)
                'n' -> out?.write(0x0A)
                'r' -> out?.write(0x0D)
                't' -> out?.write(0x09)
                'u' -> {
                    var scalar = hex4()
                    if (scalar in 0xD800..0xDBFF) {
                        expect('\\')
                        expect('u')
                        val low = hex4()
                        if (low !in 0xDC00..0xDFFF) throw error("unpaired surrogate escape")
                        scalar = 0x10000 + ((scalar - 0xD800) shl 10) + (low - 0xDC00)
                    } else if (scalar in 0xDC00..0xDFFF) {
                        throw error("unpaired surrogate escape")
                    }
                    out?.write(String(Character.toChars(scalar)).toByteArray(Charsets.UTF_8))
                }
                else -> throw error("invalid escape")
            }
        }

        private fun hex4(): Int {
            var value = 0
            repeat(4) {
                val digit = Character.digit(peek(), 16)
                if (digit < 0) throw error("invalid \\u escape")
                value = value shl 4 or digit
                index++
            }
            return value
        }

        private fun literal(word: String) {
            for (char in word) {
                if (peek() != char.code) throw error("invalid literal")
                index++
            }
        }

        private fun number() {
            val start = index
            if (peek() == '-'.code) index++
            val first = peek()
            if (!isDigit(first)) throw error("unexpected character")
            index++
            if (first != '0'.code) skipDigits()
            var isInteger = true
            if (peek() == '.'.code) {
                index++
                requireDigits()
                isInteger = false
            }
            if (peek() == 'e'.code || peek() == 'E'.code) {
                index++
                if (peek() == '+'.code || peek() == '-'.code) index++
                requireDigits()
                isInteger = false
            }
            if (isInteger && first == '0'.code && bytes[start] == '-'.code.toByte()) throw error("-0 is not an integer")
        }

        private fun requireDigits() {
            if (!isDigit(peek())) throw error("expected digit")
            skipDigits()
        }

        private fun skipDigits() {
            while (isDigit(peek())) index++
        }

        private fun isDigit(byte: Int) = byte in '0'.code..'9'.code
    }
}
