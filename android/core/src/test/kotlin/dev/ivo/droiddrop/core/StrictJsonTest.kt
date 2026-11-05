package dev.ivo.droiddrop.core

import dev.ivo.droiddrop.core.protocol.ProtocolException
import dev.ivo.droiddrop.core.protocol.StrictJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class StrictJsonTest {
    @ParameterizedTest
    @ValueSource(
        strings = [
            "{}", "[]", "0", "-1.5e+3", "2E-2", "0.0", "-0.0", "-0e1", "\"\"", "null",
            """{"a":[],"b":{},"c":[true,false,null],"d":"\u00e9\/\b\f\n\r\t\"\\\u0000"}""",
            """{"a":1,"A":2}""", """["\uD83D\uDE00"]""",
        ],
    )
    fun accepts(json: String) {
        assertDoesNotThrow { StrictJson.validate(json.toByteArray()) }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", " ", "-0", "-", "01", "1.", ".5", "1e", "1e+", "+1", "tru", "nul", "True", "NaN", "Infinity",
            """"\x"""", """"\u12"""", """"\u12G4"""", "\"a\tb\"", "\"open", "[1 2]", "[,]", "[1,]", """{"a" 1}""", "{,}",
            """{"a":1,}""", "{a:1}", "{'a':1}", "[1]]", "[1]\u000C", "\uFEFF[]", """["\uDE00\uD800"]""", """["\uD800x"]""",
            """{"é":1,"\u00e9":2}""",
        ],
    )
    fun rejects(json: String) {
        assertThrows<ProtocolException> { StrictJson.validate(json.toByteArray()) }
    }

    @Test
    fun depthLimitIsExact() {
        assertDoesNotThrow { StrictJson.validate(nested(StrictJson.MAX_DEPTH)) }
        assertThrows<ProtocolException> { StrictJson.validate(nested(StrictJson.MAX_DEPTH + 1)) }
    }

    @Test
    fun deepNestingIsRejectedWithoutStackOverflow() {
        assertThrows<ProtocolException> { StrictJson.validate(nested(100_000)) }
    }

    private fun nested(depth: Int) = ("[".repeat(depth) + "]".repeat(depth)).toByteArray()
}
