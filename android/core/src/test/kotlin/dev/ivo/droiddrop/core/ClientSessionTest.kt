package dev.ivo.droiddrop.core

import dev.ivo.droiddrop.core.pairing.Base64Url
import dev.ivo.droiddrop.core.pairing.Pairing
import dev.ivo.droiddrop.core.protocol.Message
import dev.ivo.droiddrop.core.protocol.ProtocolException
import dev.ivo.droiddrop.core.protocol.Platform
import dev.ivo.droiddrop.core.session.ClientAction
import dev.ivo.droiddrop.core.session.ClientSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ClientSessionTest {
    private val serverFp = ByteArray(32) { 1 }
    private val clientFp = ByteArray(32) { 2 }
    private val secret = ByteArray(32) { 3 }
    private val hello = Message.Hello(1, "MacBook", Platform.MACOS)
    private val ownHello = Message.Hello(1, "Pixel", Platform.ANDROID)
    private val clip = Message.Clip("5b2c8f9e-1d3a-4e7b-9c6d-0a1b2c3d4e5f", "text/plain", "hi")

    private fun started(pairingSecret: ByteArray?) = ClientSession(serverFp, clientFp, pairingSecret).also { it.start(ownHello) }

    @Test
    fun pairedClientSendsHelloThenPing() {
        val session = ClientSession(serverFp, clientFp, pairingSecret = null)
        assertEquals(
            listOf(ClientAction.Send(ownHello), ClientAction.Send(Message.Ping(0))),
            session.start(ownHello),
        )
        assertEquals(Message.Ping(1), session.nextPing())
        assertEquals(Message.Ping(2), session.nextPing())
    }

    @Test
    fun pairedClientConnectsOnlyOnConfirmationPong() {
        val session = started(pairingSecret = null)
        assertEquals(emptyList<ClientAction>(), session.receive(hello))
        assertEquals(listOf(ClientAction.Connected("MacBook")), session.receive(Message.Pong(0)))
        assertEquals(listOf(ClientAction.Send(Message.Pong(3))), session.receive(Message.Ping(3)))
        assertEquals(listOf(ClientAction.Deliver(clip)), session.receive(clip))
        assertEquals(emptyList<ClientAction>(), session.receive(Message.Unknown("future")))
        assertEquals(emptyList<ClientAction>(), session.endOfStream())
    }

    @Test
    fun keepalivePongsAreDelivered() {
        val session = started(pairingSecret = null)
        session.receive(hello)
        session.receive(Message.Pong(0))
        val ping = session.nextPing()
        assertEquals(listOf(ClientAction.Deliver(Message.Pong(ping.id))), session.receive(Message.Pong(ping.id)))
    }

    @Test
    fun messagesBeforeConfirmationAreHeldUntilConnected() {
        val session = started(pairingSecret = null)
        session.receive(hello)
        assertEquals(listOf(ClientAction.Send(Message.Pong(7))), session.receive(Message.Ping(7)))
        assertEquals(emptyList<ClientAction>(), session.receive(clip))
        assertEquals(emptyList<ClientAction>(), session.receive(Message.Unknown("future")))
        assertEquals(
            listOf(ClientAction.Connected("MacBook"), ClientAction.Deliver(clip)),
            session.receive(Message.Pong(0)),
        )
    }

    @Test
    fun serverCloseBeforePongNeverConnects() {
        val session = started(pairingSecret = null)
        session.receive(hello)
        assertEquals(emptyList<ClientAction>(), session.receive(clip))
        assertEquals(listOf(ClientAction.Close("server closed before session was established")), session.endOfStream())
        assertEquals(emptyList<ClientAction>(), session.receive(Message.Pong(0)))
    }

    @Test
    fun pongBeforeServerHelloCloses() {
        val session = started(pairingSecret = null)
        assertEquals(listOf(ClientAction.Close("expected hello")), session.receive(Message.Pong(0)))
    }

    @Test
    fun pairingSendsProofAndVerifiesServerProof() {
        val session = ClientSession(serverFp, clientFp, secret)
        assertEquals(listOf(ClientAction.Send(ownHello)), session.start(ownHello))
        val proof = Base64Url.encode(Pairing.clientProof(secret, serverFp, clientFp))
        assertEquals(listOf(ClientAction.Send(Message.PairRequest(proof))), session.receive(hello))

        val serverProof = Base64Url.encode(Pairing.serverProof(secret, serverFp, clientFp))
        assertEquals(
            listOf(ClientAction.Paired("MacBook"), ClientAction.Connected("MacBook")),
            session.receive(Message.PairResult(true, serverProof)),
        )
        assertEquals(listOf(ClientAction.Deliver(clip)), session.receive(clip))
    }

    @Test
    fun unknownTypeDuringPairingIsIgnored() {
        val session = started(secret)
        session.receive(hello)
        assertEquals(emptyList<ClientAction>(), session.receive(Message.Unknown("future")))
        val serverProof = Base64Url.encode(Pairing.serverProof(secret, serverFp, clientFp))
        assertEquals(
            listOf(ClientAction.Paired("MacBook"), ClientAction.Connected("MacBook")),
            session.receive(Message.PairResult(true, serverProof)),
        )
    }

    @Test
    fun serverCloseAfterPairRequestMeansAlreadyPaired() {
        // A server that already trusts this identity closes on pair_request without a pair_result (SPEC §5 rule 4).
        val session = started(secret)
        session.receive(hello)
        assertEquals(
            listOf(ClientAction.Close("server closed after pair_request: it already trusts this phone", ProtocolException.ALREADY_PAIRED)),
            session.endOfStream(),
        )
    }

    @Test
    fun otherClosesKeepTheSessionClosedCode() {
        assertEquals(ProtocolException.SESSION_CLOSED, ClientAction.Close("x").code)
    }

    @Test
    fun invalidServerProofCloses() {
        val session = started(secret)
        session.receive(hello)
        val forged = Base64Url.encode(ByteArray(32))
        assertEquals(listOf(ClientAction.Close("server proof invalid")), session.receive(Message.PairResult(true, forged)))
        assertEquals(emptyList<ClientAction>(), session.receive(Message.Ping(1)))
        assertEquals(emptyList<ClientAction>(), session.endOfStream())
    }

    @Test
    fun missingOrMalformedServerProofCloses() {
        for (proof in listOf(null, "not-base64", Base64Url.encode(ByteArray(16)))) {
            val session = started(secret)
            session.receive(hello)
            assertEquals(listOf(ClientAction.Close("server proof invalid")), session.receive(Message.PairResult(true, proof)))
        }
    }

    @Test
    fun rejectedPairingCloses() {
        val session = started(secret)
        session.receive(hello)
        assertEquals(
            listOf(ClientAction.Close("pairing rejected: bad_proof")),
            session.receive(Message.PairResult(false, reason = "bad_proof")),
        )
    }

    @Test
    fun sessionMessageDuringPairingMeansAlreadyPaired() {
        // SPEC §5 rule 4: a server sends session messages only to a client it already trusts (it registered us on
        // hello and may push a queued file_offer or a clip before it reads our pair_request).
        val session = started(secret)
        session.receive(hello)
        val actions = session.receive(clip)
        assertEquals(1, actions.size)
        assertEquals(ProtocolException.ALREADY_PAIRED, (actions.single() as ClientAction.Close).code)
    }

    @Test
    fun helloAgainDuringPairingIsAProtocolError() {
        val session = started(secret)
        session.receive(hello)
        assertEquals(listOf(ClientAction.Close("expected pair_result")), session.receive(hello))
    }

    @Test
    fun pingDuringPairingMeansAlreadyPaired() {
        val session = started(secret)
        session.receive(hello)
        assertEquals(ProtocolException.ALREADY_PAIRED, (session.receive(Message.Ping(1)).single() as ClientAction.Close).code)
    }

    @Test
    fun otherPongBeforeConfirmationIsHeldThenDelivered() {
        val session = started(pairingSecret = null)
        session.receive(hello)
        assertEquals(emptyList<ClientAction>(), session.receive(Message.Pong(9)))
        assertEquals(
            listOf(ClientAction.Connected("MacBook"), ClientAction.Deliver(Message.Pong(9))),
            session.receive(Message.Pong(0)),
        )
    }

    @Test
    fun serverCloseBeforeHelloCloses() {
        assertEquals(listOf(ClientAction.Close("server closed before session was established")), started(null).endOfStream())
    }

    @Test
    fun requiresHelloFirstWithVersionOne() {
        assertEquals(listOf(ClientAction.Close("expected hello")), started(null).receive(Message.Ping(1)))
        assertEquals(listOf(ClientAction.Close("expected hello")), started(null).receive(Message.Unknown("future")))
        assertEquals(listOf(ClientAction.Close("expected hello")), started(secret).receive(Message.Unknown("future")))
        assertEquals(
            listOf(ClientAction.Close("unsupported protocol version 2")),
            started(null).receive(Message.Hello(2, "MacBook", Platform.MACOS)),
        )
    }

    @Test
    fun repeatedHelloOrPairingMessagesClose() {
        for (message in listOf(hello, Message.PairRequest("x"), Message.PairResult(true))) {
            val session = started(null)
            session.receive(hello)
            session.receive(Message.Pong(0))
            assertEquals(listOf(ClientAction.Close("unexpected ${message::class.simpleName}")), session.receive(message))
        }
        val awaiting = started(null)
        awaiting.receive(hello)
        assertEquals(listOf(ClientAction.Close("unexpected Hello")), awaiting.receive(hello))
    }

    @Test
    fun mustStartExactlyOnceBeforeReceiving() {
        assertThrows<IllegalStateException> { ClientSession(serverFp, clientFp, null).receive(hello) }
        val session = started(null)
        assertThrows<IllegalStateException> { session.start(ownHello) }
    }
}
