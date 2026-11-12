package dev.ivo.droiddrop.core.session

import dev.ivo.droiddrop.core.protocol.Frame
import dev.ivo.droiddrop.core.protocol.Message
import dev.ivo.droiddrop.core.protocol.MessageCodec
import dev.ivo.droiddrop.core.protocol.Platform
import dev.ivo.droiddrop.core.protocol.ProtocolException
import dev.ivo.droiddrop.core.tls.DeviceIdentity
import dev.ivo.droiddrop.core.tls.PinnedTrustManager
import dev.ivo.droiddrop.core.tls.Tls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.IOException

/** Session callbacks, invoked on the session's IO thread. An exception thrown from a callback ends the session and propagates out of [DroidDropClient.run]. */
interface SessionListener {
    fun onPaired(serverName: String) {}

    /** [sender] stays usable from any thread until [DroidDropClient.run] returns. */
    fun onConnected(serverName: String, sender: MessageSender) {}

    /** Every message after [onConnected], including pongs answering keepalive pings. */
    fun onMessage(message: Message) {}
    fun onChunk(chunk: Frame.Chunk) {}
}

class DroidDropClient(
    private val identity: DeviceIdentity,
    private val deviceName: String,
    private val pingIdleMillis: Long = 30_000,
    private val receiveTimeoutMillis: Int = 90_000,
) {
    /**
     * Runs one session to completion. Pass [pairingSecret] to pair during this session.
     *
     * Returns normally only when the server ends the stream cleanly after [SessionListener.onConnected].
     * Throws [ProtocolException] with code `session_closed` when the session is rejected, violates the protocol,
     * or ends before it was established; [IOException] (including SSLException and SocketTimeoutException) on
     * network, TLS or receive-timeout failure.
     */
    suspend fun run(host: String, port: Int, serverFingerprint: ByteArray, pairingSecret: ByteArray?, listener: SessionListener) {
        withContext(Dispatchers.IO) {
            val context = Tls.context(identity, PinnedTrustManager.pinnedTo(serverFingerprint))
            runInterruptible { Tls.connect(context, host, port, readTimeoutMillis = receiveTimeoutMillis) }.use { socket ->
                runSession(Connection(socket), ClientSession(serverFingerprint, identity.fingerprint, pairingSecret), listener)
            }
        }
    }

    private suspend fun runSession(connection: Connection, session: ClientSession, listener: SessionListener) = coroutineScope {
        // Blocking socket I/O ignores coroutine cancellation; closing the socket unblocks it.
        val closer = launch {
            try {
                awaitCancellation()
            } finally {
                connection.close()
            }
        }
        var pinger: Job? = null

        fun perform(actions: List<ClientAction>) {
            for (action in actions) {
                when (action) {
                    is ClientAction.Send -> connection.send(action.message)
                    is ClientAction.Paired -> listener.onPaired(action.serverName)
                    is ClientAction.Connected -> {
                        listener.onConnected(action.serverName, connection)
                        pinger = launch { keepalive(connection, session) }
                    }
                    is ClientAction.Deliver -> listener.onMessage(action.message)
                    is ClientAction.Close -> throw ProtocolException("session_closed", action.reason)
                }
            }
        }

        try {
            perform(session.start(Message.Hello(1, deviceName, Platform.ANDROID)))
            while (true) {
                when (val frame = connection.read()) {
                    null -> return@coroutineScope perform(session.endOfStream())
                    is Frame.Control -> perform(session.receive(MessageCodec.decode(frame.json)))
                    is Frame.Chunk -> {
                        if (pinger == null) throw ProtocolException("session_closed", "chunk before session was established")
                        listener.onChunk(frame)
                    }
                }
            }
        } catch (e: IOException) {
            ensureActive()
            throw e
        } finally {
            pinger?.cancel()
            closer.cancel()
        }
    }

    /**
     * SPEC §5 rule 5: ping after [pingIdleMillis] without sending anything. R14: also ping after [pingIdleMillis]
     * without receiving anything when no ping went out in that time, so a long upload (which sends constantly and
     * gets nothing back until `file_result`) still draws a pong before the receive timeout.
     */
    private suspend fun CoroutineScope.keepalive(connection: Connection, session: ClientSession) {
        val idleNanos = pingIdleMillis * 1_000_000
        // The confirmation ping went out just before the session was established.
        var lastPingAtNanos = System.nanoTime()
        while (true) {
            val now = System.nanoTime()
            val untilSendIdle = idleNanos - (now - connection.lastSentAtNanos)
            val untilReceiveIdle = maxOf(idleNanos - (now - connection.lastReceivedAtNanos), idleNanos - (now - lastPingAtNanos))
            if (untilSendIdle > 0 && untilReceiveIdle > 0) {
                delay(minOf(untilSendIdle, untilReceiveIdle) / 1_000_000 + 1)
                continue
            }
            try {
                connection.send(session.nextPing())
            } catch (e: IOException) {
                ensureActive()
                throw e
            }
            lastPingAtNanos = System.nanoTime()
        }
    }
}
