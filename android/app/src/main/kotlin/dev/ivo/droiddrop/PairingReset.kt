package dev.ivo.droiddrop

/** What a completed pairing must forget. Pure, so it can be unit tested. */
object PairingReset {
    enum class Reset { INCOMING_BATCHES, SEND_QUEUE, RETRY, SENT_SUMMARY }

    fun forNewPairing(previous: String?, next: String): Set<Reset> = 
        if (previous == next) setOf(Reset.INCOMING_BATCHES) else Reset.entries.toSet()
}
