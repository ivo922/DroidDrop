package dev.ivo.droiddrop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SendGateTest {
    @Test
    fun refusesWhenUnpairedNoLanOrEmpty() {
        assertEquals("Pair DroidDrop with your Mac first.", SendGate.refusal(paired = false, localNetworkAllowed = true, fileCount = 1))
        assertEquals("Open DroidDrop to allow local network access.", SendGate.refusal(paired = true, localNetworkAllowed = false, fileCount = 1))
        assertEquals("Nothing to send.", SendGate.refusal(paired = true, localNetworkAllowed = true, fileCount = 0))
        assertNull(SendGate.refusal(paired = true, localNetworkAllowed = true, fileCount = 2))
    }

    @Test
    fun textShareWithPreviewThumbnailSendsText() {
        // Chrome puts a thumbnail URI in ClipData next to EXTRA_TEXT; only EXTRA_STREAM makes a file share.
        assertTrue(SendGate.sharesText(singleSend = true, hasStream = false, text = "https://example.com"))
    }

    @Test
    fun streamOrNoTextIsAFileShare() {
        assertFalse(SendGate.sharesText(singleSend = true, hasStream = true, text = "caption"))
        assertFalse(SendGate.sharesText(singleSend = true, hasStream = false, text = "  "))
        assertFalse(SendGate.sharesText(singleSend = true, hasStream = false, text = null))
        assertFalse(SendGate.sharesText(singleSend = false, hasStream = false, text = "x"))
    }
}
