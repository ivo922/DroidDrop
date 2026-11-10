package dev.ivo.droiddrop.core

import dev.ivo.droiddrop.core.clipboard.ClipRules
import dev.ivo.droiddrop.core.protocol.Message
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ClipRulesTest {
    private fun clip(mime: String, text: String) = Message.Clip("6f1c0d2e-8a4b-4c3d-9e5f-0a1b2c3d4e5f", mime, text)

    @Test
    fun acceptsPlainText() {
        assertEquals("hi", ClipRules.acceptIncoming(clip("text/plain", "hi")))
    }

    @Test
    fun ignoresOtherMime() {
        assertNull(ClipRules.acceptIncoming(clip("text/html", "<b>hi</b>")))
        assertNull(ClipRules.acceptIncoming(clip("text/plain; charset=utf-8", "hi")))
    }

    @Test
    fun limitCountsUtf8Bytes() {
        assertNotNull(ClipRules.acceptIncoming(clip("text/plain", "a".repeat(262_144))))
        assertNull(ClipRules.acceptIncoming(clip("text/plain", "a".repeat(262_145))))
        assertNull(ClipRules.acceptIncoming(clip("text/plain", "€".repeat(87_382)))) // 262 146 bytes
    }
}
