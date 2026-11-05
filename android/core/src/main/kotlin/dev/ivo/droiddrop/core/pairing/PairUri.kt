package dev.ivo.droiddrop.core.pairing

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets.UTF_8

data class PairUri(
    val name: String,
    val hosts: List<String>,
    val port: Int,
    /** Server fingerprint, b64url. */
    val fingerprint: String,
    /** One-time pairing secret, b64url. */
    val secret: String,
) {
    fun toUriString(): String = listOf(
        "v" to "1",
        "name" to name,
        "hosts" to hosts.joinToString(","),
        "port" to port.toString(),
        "fp" to fingerprint,
        "secret" to secret,
    ).joinToString("&", prefix = "droiddrop://pair?") { (key, value) -> "$key=${percentEncode(value)}" }

    companion object {
        private val unreserved = (('A'..'Z') + ('a'..'z') + ('0'..'9') + "-._~".toList()).toSet()
        private const val HEX = "0123456789ABCDEF"

        fun parse(text: String): PairUri? {
            val uri = try { URI(text) } catch (e: URISyntaxException) { return null }
            if (uri.scheme != "droiddrop" || uri.host != "pair") return null
            val params = LinkedHashMap<String, String>()
            for (part in (uri.rawQuery ?: return null).split('&')) {
                val eq = part.indexOf('=')
                if (eq < 0) continue
                val key = percentDecode(part.substring(0, eq)) ?: return null
                val value = percentDecode(part.substring(eq + 1)) ?: return null
                params.putIfAbsent(key, value)
            }
            if (params["v"] != "1") return null
            val name = params["name"]?.takeIf { it.isNotEmpty() } ?: return null
            val hosts = params["hosts"]?.split(',') ?: return null
            if (!hosts.all(::isIpv4)) return null
            val port = params["port"]?.toIntOrNull()?.takeIf { it in 1..65_535 } ?: return null
            val fingerprint = params["fp"]?.takeIf { Base64Url.decode32(it) != null } ?: return null
            val secret = params["secret"]?.takeIf { Base64Url.decode32(it) != null } ?: return null
            return PairUri(name, hosts, port, fingerprint, secret)
        }

        internal fun isIpv4(text: String): Boolean {
            val parts = text.split('.')
            return parts.size == 4 && parts.all { part ->
                part.length in 1..3 &&
                    part.all { it in '0'..'9' } &&
                    !(part.length > 1 && part[0] == '0') &&
                    part.toInt() <= 255
            }
        }

        private fun percentEncode(value: String): String = buildString {
            for (byte in value.toByteArray(UTF_8)) {
                val b = byte.toInt() and 0xff
                if (b < 0x80 && b.toChar() in unreserved) append(b.toChar())
                else append('%').append(HEX[b shr 4]).append(HEX[b and 0x0f])
            }
        }

        /** Percent-decoding where '+' stays a literal plus. */
        private fun percentDecode(value: String): String? {
            val out = ByteArrayOutputStream()
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c == '%') {
                    if (i + 2 >= value.length) return null
                    val hi = Character.digit(value[i + 1], 16)
                    val lo = Character.digit(value[i + 2], 16)
                    if (hi < 0 || lo < 0) return null
                    out.write(hi * 16 + lo)
                    i += 3
                } else {
                    out.write(c.toString().toByteArray(UTF_8))
                    i++
                }
            }
            return try {
                UTF_8.newDecoder().decode(ByteBuffer.wrap(out.toByteArray())).toString()
            } catch (e: CharacterCodingException) {
                null
            }
        }
    }
}
