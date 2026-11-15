package dev.ivo.droiddrop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConnectionStatusTest {
    @Test
    fun serviceStartShowsSearchingForSavedMac() {
        assertEquals(ConnectionState.Searching("Mac"), ConnectionState.Unpaired.onServiceStart(savedMacName = "Mac"))
    }

    @Test
    fun serviceStartKeepsOtherStates() {
        assertEquals(ConnectionState.Unpaired, ConnectionState.Unpaired.onServiceStart(savedMacName = null))
        val failed = ConnectionState.Failed("Pairing failed")
        assertEquals(failed, failed.onServiceStart(savedMacName = "Mac"))
        assertEquals(ConnectionState.Connected("Mac"), ConnectionState.Connected("Mac").onServiceStart(savedMacName = "Mac"))
    }

    @Test
    fun hintsExplainTheState() {
        assertEquals("Files and clipboard sync over Wi-Fi", ConnectionState.Connected("Mac").hint())
        assertEquals("Keep DroidDrop running on your Mac, on the same network", ConnectionState.Searching("Mac").hint())
        assertEquals("On your Mac, choose Pair New Phone… and scan its code", ConnectionState.Unpaired.hint())
    }

    @Test
    fun noHintWhereTheTitleSaysItAll() {
        assertEquals(null, ConnectionState.Pairing("Mac").hint())
        assertEquals(null, ConnectionState.Failed("Pairing failed").hint())
    }
}
