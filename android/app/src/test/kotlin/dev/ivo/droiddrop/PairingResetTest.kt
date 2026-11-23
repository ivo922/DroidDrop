package dev.ivo.droiddrop

import dev.ivo.droiddrop.PairingReset.Reset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PairingResetTest {
    @Test
    fun firstPairingClearsEverything() {
        assertEquals(Reset.entries.toSet(), PairingReset.forNewPairing(null, "AAA"))
    }

    @Test
    fun differentMacClearsEverything() {
        assertEquals(Reset.entries.toSet(), PairingReset.forNewPairing("AAA", "BBB"))
    }

    @Test
    fun sameMacClearsOnlyIncomingBatches() {
        assertEquals(setOf(Reset.INCOMING_BATCHES), PairingReset.forNewPairing("AAA", "AAA"))
    }
}
