package dev.ivo.droiddrop.core.protocol

import java.io.IOException

/** Fatal protocol violation; the connection must be closed. [code] is a spec error code or "invalid_message". */
class ProtocolException(val code: String, message: String = code) : IOException(message) {
    companion object {
        /** The session was rejected, broke the protocol, or ended before it was established. */
        const val SESSION_CLOSED = "session_closed"

        /** The server closed on our pair_request without a pair_result: it already trusts this identity. */
        const val ALREADY_PAIRED = "already_paired"
    }
}
