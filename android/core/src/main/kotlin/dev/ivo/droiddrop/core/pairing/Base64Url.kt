package dev.ivo.droiddrop.core.pairing

import java.util.Base64

object Base64Url {
    private val alphabet = Regex("^[A-Za-z0-9_-]*$")

    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** Strict: unpadded base64url alphabet only, canonical (unused trailing bits zero). */
    fun decode(text: String): ByteArray? {
        if (!alphabet.matches(text) || text.length % 4 == 1) return null
        val bytes = runCatching { Base64.getUrlDecoder().decode(text) }.getOrNull() ?: return null
        return bytes.takeIf { encode(it) == text }
    }

    /** Exactly 32 bytes (43 characters): fingerprints, secrets, proofs. */
    fun decode32(text: String): ByteArray? = if (text.length == 43) decode(text)?.takeIf { it.size == 32 } else null
}
