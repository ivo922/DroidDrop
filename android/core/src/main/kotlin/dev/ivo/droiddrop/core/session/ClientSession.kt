package dev.ivo.droiddrop.core.session

import dev.ivo.droiddrop.core.pairing.Base64Url
import dev.ivo.droiddrop.core.pairing.Pairing
import dev.ivo.droiddrop.core.protocol.Message
import java.util.concurrent.atomic.AtomicLong

sealed interface ClientAction {
    data class Send(val message: Message) : ClientAction
    data class Paired(val serverName: String) : ClientAction
    data class Connected(val serverName: String) : ClientAction
    data class Deliver(val message: Message) : ClientAction
    data class Close(val reason: String) : ClientAction
}

/**
 * Protocol rules of one client-side session (SPEC §5, §6). No I/O.
 *
 * A paired client is established only when the server answers the ping sent right after its hello
 * (§5 rule 4); messages that arrive earlier are held and delivered after [ClientAction.Connected].
 * [receive], [start] and [endOfStream] must be called from one thread; [nextPing] may be called from any.
 */
class ClientSession(
    private val serverFingerprint: ByteArray,
    private val clientFingerprint: ByteArray,
    private val pairingSecret: ByteArray?,
) {
    private enum class Phase { NEW, AWAITING_HELLO, AWAITING_PAIR_RESULT, AWAITING_PONG, READY, CLOSED }

    private var phase = Phase.NEW
    private var serverName = ""
    private val pingIds = AtomicLong()
    private var confirmationPingId = -1L
    private val held = mutableListOf<ClientAction>()

    /** The opening messages: [hello], then the confirmation ping when already paired. */
    fun start(hello: Message.Hello): List<ClientAction> {
        check(phase == Phase.NEW) { "session already started" }
        phase = Phase.AWAITING_HELLO
        if (pairingSecret != null) return listOf(ClientAction.Send(hello))
        val ping = nextPing()
        confirmationPingId = ping.id
        return listOf(ClientAction.Send(hello), ClientAction.Send(ping))
    }

    /** A keepalive ping; ids continue the sequence started by the confirmation ping. */
    fun nextPing(): Message.Ping = Message.Ping(pingIds.getAndIncrement())

    fun receive(message: Message): List<ClientAction> {
        check(phase != Phase.NEW) { "session not started" }
        if (message is Message.Unknown) return emptyList()
        return when (phase) {
            Phase.NEW, Phase.CLOSED -> emptyList()
            Phase.AWAITING_HELLO -> onHello(message)
            Phase.AWAITING_PAIR_RESULT -> onPairResult(message)
            Phase.AWAITING_PONG -> onConfirmation(message)
            Phase.READY -> onReady(message)
        }
    }

    /** The server closed the stream; a session that was never established is a failure. */
    fun endOfStream(): List<ClientAction> = when (phase) {
        Phase.READY -> emptyList<ClientAction>().also { phase = Phase.CLOSED }
        Phase.CLOSED -> emptyList()
        else -> close("server closed before session was established")
    }

    private fun onHello(message: Message): List<ClientAction> {
        if (message !is Message.Hello) return close("expected hello")
        if (message.v != 1L) return close("unsupported protocol version ${message.v}")
        serverName = message.name
        val secret = pairingSecret
        if (secret == null) {
            phase = Phase.AWAITING_PONG
            return emptyList()
        }
        phase = Phase.AWAITING_PAIR_RESULT
        val proof = Pairing.clientProof(secret, serverFingerprint, clientFingerprint)
        return listOf(ClientAction.Send(Message.PairRequest(Base64Url.encode(proof))))
    }

    private fun onPairResult(message: Message): List<ClientAction> {
        if (message !is Message.PairResult) return close("expected pair_result")
        if (!message.ok) return close("pairing rejected: ${message.reason ?: "no reason given"}")
        val proof = message.proof?.let(Base64Url::decode32)
        if (proof == null || !Pairing.isValidServerProof(proof, pairingSecret!!, serverFingerprint, clientFingerprint)) {
            return close("server proof invalid")
        }
        phase = Phase.READY
        return listOf(ClientAction.Paired(serverName), ClientAction.Connected(serverName))
    }

    private fun onConfirmation(message: Message): List<ClientAction> {
        if (message is Message.Pong && message.id == confirmationPingId) {
            phase = Phase.READY
            val released = held.toList()
            held.clear()
            return listOf(ClientAction.Connected(serverName)) + released
        }
        val actions = onReady(message)
        if (phase == Phase.CLOSED) return actions
        held += actions.filterIsInstance<ClientAction.Deliver>()
        return actions.filterIsInstance<ClientAction.Send>()
    }

    private fun onReady(message: Message): List<ClientAction> = when (message) {
        is Message.Ping -> listOf(ClientAction.Send(Message.Pong(message.id)))
        is Message.Hello, is Message.PairRequest, is Message.PairResult -> close("unexpected ${message::class.simpleName}")
        else -> listOf(ClientAction.Deliver(message))
    }

    private fun close(reason: String): List<ClientAction> {
        phase = Phase.CLOSED
        held.clear()
        return listOf(ClientAction.Close(reason))
    }
}
