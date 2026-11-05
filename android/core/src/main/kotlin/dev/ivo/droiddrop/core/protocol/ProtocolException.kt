package dev.ivo.droiddrop.core.protocol

import java.io.IOException

/** Fatal protocol violation; the connection must be closed. [code] is a spec error code or "invalid_message". */
class ProtocolException(val code: String, message: String = code) : IOException(message)
