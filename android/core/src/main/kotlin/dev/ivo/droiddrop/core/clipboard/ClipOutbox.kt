package dev.ivo.droiddrop.core.clipboard

import dev.ivo.droiddrop.core.protocol.FrameLimits
import dev.ivo.droiddrop.core.protocol.Message
import dev.ivo.droiddrop.core.protocol.MessageCodec
import java.security.MessageDigest
import java.util.UUID

/**
 * Which local clipboard text becomes a clip (SPEC §4.2, §8). Thread-safe, no I/O.
 *
 * Echo and sensitivity checks apply to automatic sends only: an explicit tap always sends.
 */
class ClipOutbox(private val makeId: () -> String = { UUID.randomUUID().toString() }) {
    sealed interface Decision {
        data class Send(val clip: Message.Clip) : Decision
        data object Empty : Decision
        data object TooLarge : Decision
        /** The text of the last clip applied from the Mac (automatic sends only). */
        data object FromMac : Decision
        /** Marked sensitive by a password manager (automatic sends only). */
        data object Sensitive : Decision
    }

    private var lastRemoteHash: ByteArray? = null

    /** Records the text of a clip applied from the Mac. */
    @Synchronized
    fun noteRemote(text: String) {
        lastRemoteHash = hash(text)
    }

    @Synchronized
    fun decide(text: String, automatic: Boolean, sensitive: Boolean = false): Decision {
        if (text.isEmpty()) return Decision.Empty
        if (text.encodeToByteArray().size > ClipRules.MAX_TEXT_BYTES) return Decision.TooLarge
        val clip = Message.Clip(makeId(), ClipRules.MIME_TEXT, text)
        // JSON escaping can more than double the text (U+0001 becomes six bytes), so check the frame too.
        if (MessageCodec.encode(clip).size > FrameLimits.MAX_CONTROL_PAYLOAD) return Decision.TooLarge
        if (automatic && sensitive) return Decision.Sensitive
        if (automatic && hash(text).contentEquals(lastRemoteHash)) return Decision.FromMac
        return Decision.Send(clip)
    }

    private fun hash(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray())
}
