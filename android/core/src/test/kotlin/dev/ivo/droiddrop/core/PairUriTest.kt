package dev.ivo.droiddrop.core

import dev.ivo.droiddrop.core.pairing.PairUri
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

class PairUriTest {
    private val fixture = Fixtures.load("pair-uri")

    @Test
    fun parsesValidUris() {
        for (entry in fixture.list("valid")) {
            val expected = entry.getValue("expected").jsonObject
            val uri = PairUri.parse(entry.str("uri")) ?: fail("did not parse: ${entry.str("name")}")
            assertEquals(
                PairUri(
                    name = expected.str("name"),
                    hosts = expected.strings("hosts"),
                    port = expected.long("port").toInt(),
                    fingerprint = expected.str("fingerprint"),
                    secret = expected.str("secret"),
                ),
                uri,
                entry.str("name"),
            )
        }
    }

    @Test
    fun buildsUrisThatParseBack() {
        for (entry in fixture.list("valid")) {
            val uri = PairUri.parse(entry.str("uri"))!!
            assertEquals(uri, PairUri.parse(uri.toUriString()), entry.str("name"))
        }
    }

    @Test
    fun encodesReservedCharacters() {
        val fp = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE"
        val text = PairUri("A+B C", listOf("10.0.0.1", "10.0.0.2"), 47321, fp, fp).toUriString()
        assertTrue(text.contains("name=A%2BB%20C"), text)
        assertTrue(text.contains("hosts=10.0.0.1%2C10.0.0.2"), text)
    }

    @Test
    fun toStringRedactsSecret() {
        val uri = PairUri.parse(fixture.list("valid").first().str("uri"))!!
        val text = uri.toString()
        assertFalse(text.contains(uri.secret), text)
        assertTrue(text.contains(uri.fingerprint), text)
    }

    @Test
    fun rejectsInvalidUris() {
        for (entry in fixture.list("invalid")) {
            assertNull(PairUri.parse(entry.str("uri")), entry.str("name"))
        }
    }
}
