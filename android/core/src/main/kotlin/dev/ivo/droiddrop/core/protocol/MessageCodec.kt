package dev.ivo.droiddrop.core.protocol

import dev.ivo.droiddrop.core.pairing.Base64Url
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets

object MessageCodec {
    private const val MAX_INTEGER = 9_007_199_254_740_991L
    private val uuidPattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val sha256Pattern = Regex("^[0-9a-f]{64}$")

    private val integerPattern = Regex("^(0|[1-9][0-9]*)$")

    fun decode(bytes: ByteArray): Message {
        val text = try {
            StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            throw invalid("payload is not UTF-8")
        }
        StrictJson.validate(bytes)
        val element = try {
            Json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw invalid("payload is not JSON")
        }
        val o = element as? JsonObject ?: throw invalid("payload is not a JSON object")
        return when (val type = o.string("type")) {
            "hello" -> Message.Hello(
                v = o.integer("v").also { if (it < 1) throw invalid("v must be >= 1") },
                name = o.nonEmpty("name"),
                platform = Platform.fromWire(o.string("platform")) ?: throw invalid("unknown platform"),
            )
            "pair_request" -> Message.PairRequest(o.b64url32("proof"))
            "pair_result" -> {
                val ok = o.boolean("ok")
                val proof = o.optional("proof")?.let { o.b64url32("proof") }
                if (ok && proof == null) throw invalid("proof required when ok")
                Message.PairResult(ok, proof, o.optional("reason")?.let { o.string("reason") })
            }
            "ping" -> Message.Ping(o.integer("id"))
            "pong" -> Message.Pong(o.integer("id"))
            "clip" -> Message.Clip(o.uuid("id"), o.nonEmpty("mime"), o.string("text"))
            "file_offer" -> Message.FileOffer(
                transferId = o.uuid("transferId"),
                name = o.nonEmpty("name"),
                size = o.integer("size"),
                sha256 = o.string("sha256").also { if (!sha256Pattern.matches(it)) throw invalid("bad sha256") },
                mime = o.nonEmpty("mime"),
            )
            "file_accept" -> Message.FileAccept(o.uuid("transferId"), o.integer("offset"))
            "file_reject" -> Message.FileReject(o.uuid("transferId"), o.string("reason"))
            "file_done" -> Message.FileDone(o.uuid("transferId"))
            "file_result" -> Message.FileResult(o.uuid("transferId"), o.boolean("ok"), o.optional("reason")?.let { o.string("reason") })
            "file_cancel" -> Message.FileCancel(o.uuid("transferId"))
            else -> Message.Unknown(type)
        }
    }

    fun encode(message: Message): ByteArray = buildJsonObject {
        when (message) {
            is Message.Hello -> {
                put("type", "hello"); put("v", message.v); put("name", message.name); put("platform", message.platform.wire)
            }
            is Message.PairRequest -> {
                put("type", "pair_request"); put("proof", message.proof)
            }
            is Message.PairResult -> {
                put("type", "pair_result"); put("ok", message.ok)
                message.proof?.let { put("proof", it) }
                message.reason?.let { put("reason", it) }
            }
            is Message.Ping -> {
                put("type", "ping"); put("id", message.id)
            }
            is Message.Pong -> {
                put("type", "pong"); put("id", message.id)
            }
            is Message.Clip -> {
                put("type", "clip"); put("id", message.id); put("mime", message.mime); put("text", message.text)
            }
            is Message.FileOffer -> {
                put("type", "file_offer"); put("transferId", message.transferId); put("name", message.name)
                put("size", message.size); put("sha256", message.sha256); put("mime", message.mime)
            }
            is Message.FileAccept -> {
                put("type", "file_accept"); put("transferId", message.transferId); put("offset", message.offset)
            }
            is Message.FileReject -> {
                put("type", "file_reject"); put("transferId", message.transferId); put("reason", message.reason)
            }
            is Message.FileDone -> {
                put("type", "file_done"); put("transferId", message.transferId)
            }
            is Message.FileResult -> {
                put("type", "file_result"); put("transferId", message.transferId); put("ok", message.ok)
                message.reason?.let { put("reason", it) }
            }
            is Message.FileCancel -> {
                put("type", "file_cancel"); put("transferId", message.transferId)
            }
            is Message.Unknown -> throw IllegalArgumentException("unknown messages cannot be encoded: ${message.type}")
        }
    }.toString().toByteArray(StandardCharsets.UTF_8)

    private fun invalid(reason: String) = ProtocolException("invalid_message", reason)

    private fun JsonObject.primitive(key: String): JsonPrimitive =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull } ?: throw invalid("missing or non-scalar '$key'")

    private fun JsonObject.optional(key: String): JsonElement? = this[key]?.takeIf { it !is JsonNull }

    private fun JsonObject.string(key: String): String =
        primitive(key).takeIf { it.isString }?.content ?: throw invalid("'$key' must be a string")

    private fun JsonObject.nonEmpty(key: String): String =
        string(key).ifEmpty { throw invalid("'$key' must not be empty") }

    private fun JsonObject.integer(key: String): Long {
        val p = primitive(key)
        val value = if (p.isString || !integerPattern.matches(p.content)) null else p.content.toLongOrNull()
        if (value == null || value !in 0..MAX_INTEGER) throw invalid("'$key' must be an integer in 0..2^53-1")
        return value
    }

    private fun JsonObject.boolean(key: String): Boolean {
        val p = primitive(key)
        if (p.isString) throw invalid("'$key' must be a boolean")
        return when (p.content) {
            "true" -> true
            "false" -> false
            else -> throw invalid("'$key' must be a boolean")
        }
    }

    private fun JsonObject.uuid(key: String): String =
        string(key).also { if (!uuidPattern.matches(it)) throw invalid("'$key' must be a lowercase uuid") }

    private fun JsonObject.b64url32(key: String): String =
        string(key).also { if (Base64Url.decode32(it) == null) throw invalid("'$key' must be b64url32") }
}
