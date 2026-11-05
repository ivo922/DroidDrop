package dev.ivo.droiddrop.core.protocol

enum class Platform(val wire: String) {
    ANDROID("android"),
    MACOS("macos");

    companion object {
        fun fromWire(value: String): Platform? = entries.firstOrNull { it.wire == value }
    }
}

sealed interface Message {
    data class Hello(val v: Long, val name: String, val platform: Platform) : Message
    data class PairRequest(val proof: String) : Message
    data class PairResult(val ok: Boolean, val proof: String? = null, val reason: String? = null) : Message
    data class Ping(val id: Long) : Message
    data class Pong(val id: Long) : Message
    data class Clip(val id: String, val mime: String, val text: String) : Message
    data class FileOffer(val transferId: String, val name: String, val size: Long, val sha256: String, val mime: String) : Message
    data class FileAccept(val transferId: String, val offset: Long) : Message
    data class FileReject(val transferId: String, val reason: String) : Message
    data class FileDone(val transferId: String) : Message
    data class FileResult(val transferId: String, val ok: Boolean, val reason: String? = null) : Message
    data class FileCancel(val transferId: String) : Message
    data class Unknown(val type: String) : Message
}
