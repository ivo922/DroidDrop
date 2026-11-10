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
}
