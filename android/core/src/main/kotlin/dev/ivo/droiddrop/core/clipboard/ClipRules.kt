package dev.ivo.droiddrop.core.clipboard

import dev.ivo.droiddrop.core.protocol.Message

/** Which received clips to apply (SPEC §4.2). */
object ClipRules {
    const val MIME_TEXT = "text/plain"
    const val MAX_TEXT_BYTES = 262_144

    /** The text to apply, or null when the clip must be ignored: another mime, or more than [MAX_TEXT_BYTES] UTF-8 bytes. */
    fun acceptIncoming(clip: Message.Clip): String? {
        if (clip.mime != MIME_TEXT) return null
        if (clip.text.encodeToByteArray().size > MAX_TEXT_BYTES) return null
        return clip.text
    }
}
