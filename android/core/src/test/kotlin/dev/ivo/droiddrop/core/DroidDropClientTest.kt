package dev.ivo.droiddrop.core

import dev.ivo.droiddrop.core.pairing.Base64Url
import dev.ivo.droiddrop.core.pairing.Pairing
import dev.ivo.droiddrop.core.protocol.Frame
import dev.ivo.droiddrop.core.protocol.FrameCodec
import dev.ivo.droiddrop.core.protocol.FrameReader
import dev.ivo.droiddrop.core.protocol.Message
import dev.ivo.droiddrop.core.protocol.MessageCodec
import dev.ivo.droiddrop.core.protocol.Platform
import dev.ivo.droiddrop.core.protocol.ProtocolException
import dev.ivo.droiddrop.core.session.Connection
import dev.ivo.droiddrop.core.session.DroidDropClient
import dev.ivo.droiddrop.core.session.MessageSender
import dev.ivo.droiddrop.core.session.SessionListener
import dev.ivo.droiddrop.core.testing.SelfSigned
import dev.ivo.droiddrop.core.tls.DeviceIdentity
import dev.ivo.droiddrop.core.tls.Tls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

class DroidDropClientTest {
    private val mac = SelfSigned.generate("Mac")
    private val phone = SelfSigned.generate("Phone")
    private val secret = Pairing.newSecret()
    private val clip = Message.Clip("5b2c8f9e-1d3a-4e7b-9c6d-0a1b2c3d4e5f", "text/plain", "hi")
    private val chunk = Frame.Chunk("5b2c8f9e-1d3a-4e7b-9c6d-0a1b2c3d4e5f", 0, byteArrayOf(1, 2, 3))
    private val phoneHello = Message.Hello(1, "Test Phone", Platform.ANDROID)

    class FakePeer(val socket: SSLSocket) {
        private val reader = FrameReader(socket.inputStream)
        val fingerprint: ByteArray get() = Tls.peerFingerprint(socket)
        fun send(message: Message) = send(Frame.Control(MessageCodec.encode(message)))
        fun send(frame: Frame) {
            socket.outputStream.write(FrameCodec.encode(frame))
            socket.outputStream.flush()
        }
        fun receive(): Message = MessageCodec.decode((reader.read() as Frame.Control).json)
        fun read(): Frame? = reader.read()

        /** Server hello, then answers the paired client's confirmation ping. */
        fun acceptPaired() {
            send(Message.Hello(1, "Fake Mac", Platform.MACOS))
            assertEquals(Message.Hello(1, "Test Phone", Platform.ANDROID), receive())
            assertEquals(Message.Ping(0), receive())
            send(Message.Pong(0))
        }

        /** Half-closes (clean EOF for the client), then drains. */
        fun finish() {
            socket.shutdownOutput()
            drain()
        }

        /** Discards whatever the client sends until it closes. */
        fun drain() {
            while (reader.read() != null) {
                continue
            }
        }
    }

    /** One-connection TLS server running [script] on its own thread. */
    class FakeMac(identity: DeviceIdentity, script: (FakePeer) -> Unit) : Closeable {
        private val listener = (Tls.context(identity) { true }.serverSocketFactory
            .createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket).apply {
            enabledProtocols = arrayOf("TLSv1.3")
            needClientAuth = true
        }
        private val failure = AtomicReference<Throwable?>()
        private val worker = thread {
            try {
                (listener.accept() as SSLSocket).use { script(FakePeer(it.apply { startHandshake() })) }
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        val port: Int get() = listener.localPort
        fun join() {
            worker.join(5_000)
            assertFalse(worker.isAlive, "fake mac script did not finish")
            failure.get()?.let { throw it }
        }
        override fun close() = listener.close()
    }

    open class Recorder : SessionListener {
        val events: MutableList<String> = CopyOnWriteArrayList()
        override fun onPaired(serverName: String) { events += "paired:$serverName" }
        override fun onConnected(serverName: String, sender: MessageSender) { events += "connected:$serverName" }
        override fun onMessage(message: Message) { events += "message:$message" }
        override fun onChunk(chunk: Frame.Chunk) { events += "chunk:${chunk.offset}:${chunk.data.toList()}" }
    }

    private fun client(pingIdleMillis: Long = 30_000, receiveTimeoutMillis: Int = 90_000) =
        DroidDropClient(phone, "Test Phone", pingIdleMillis, receiveTimeoutMillis)

    @Test
    fun pairsThenReceivesMessages() {
        FakeMac(mac) { peer ->
            peer.send(Message.Hello(1, "Fake Mac", Platform.MACOS))
            assertEquals(phoneHello, peer.receive())
            val request = peer.receive() as Message.PairRequest
            assertTrue(Pairing.isValidClientProof(Base64Url.decode32(request.proof)!!, secret, mac.fingerprint, peer.fingerprint))
            peer.send(Message.PairResult(true, Base64Url.encode(Pairing.serverProof(secret, mac.fingerprint, peer.fingerprint))))
            peer.send(clip)
            peer.finish()
        }.use { fake ->
            val recorder = Recorder()
            runBlocking { client().run("127.0.0.1", fake.port, mac.fingerprint, secret, recorder) }
            fake.join()
            assertEquals(listOf("paired:Fake Mac", "connected:Fake Mac", "message:$clip"), recorder.events)
        }
    }

    @Test
    fun pairedClientConnectsWithoutPairRequest() {
        FakeMac(mac) { peer ->
            peer.acceptPaired()
            peer.send(Message.Ping(5))
            assertEquals(Message.Pong(5), peer.receive())
            peer.send(chunk)
            peer.finish()
        }.use { fake ->
            val recorder = Recorder()
            runBlocking { client().run("127.0.0.1", fake.port, mac.fingerprint, null, recorder) }
            fake.join()
            assertEquals(listOf("connected:Fake Mac", "chunk:0:[1, 2, 3]"), recorder.events)
        }
    }

    @Test
    fun messagesBeforeConfirmationPongAreDeliveredAfterConnected() {
        FakeMac(mac) { peer ->
            peer.send(Message.Hello(1, "Fake Mac", Platform.MACOS))
            peer.send(clip)
            assertEquals(phoneHello, peer.receive())
            assertEquals(Message.Ping(0), peer.receive())
            peer.send(Message.Pong(0))
            peer.finish()
        }.use { fake ->
            val recorder = Recorder()
            runBlocking { client().run("127.0.0.1", fake.port, mac.fingerprint, null, recorder) }
            fake.join()
            assertEquals(listOf("connected:Fake Mac", "message:$clip"), recorder.events)
        }
    }

    @Test
    fun serverClosingBeforePongThrowsAndNeverConnects() {
        FakeMac(mac) { peer ->
            peer.send(Message.Hello(1, "Fake Mac", Platform.MACOS))
            assertEquals(phoneHello, peer.receive())
            assertEquals(Message.Ping(0), peer.receive())
        }.use { fake ->
            val recorder = Recorder()
            val error = assertThrows<ProtocolException> {
                runBlocking { client().run("127.0.0.1", fake.port, mac.fingerprint, null, recorder) }
            }
            assertEquals("session_closed", error.code)
            assertEquals("server closed before session was established", error.message)
            assertEquals(emptyList<String>(), recorder.events)
            fake.join()
        }
    }

    @Test
    fun rejectedPairingThrows() {
        FakeMac(mac) { peer ->
            peer.send(Message.Hello(1, "Fake Mac", Platform.MACOS))
            peer.receive()
            peer.receive()
            peer.send(Message.PairResult(false, reason = "bad_proof"))
        }.use { fake ->
            val error = assertThrows<ProtocolException> {
                runBlocking { client().run("127.0.0.1", fake.port, mac.fingerprint, secret, Recorder()) }
            }
            assertEquals("session_closed", error.code)
            assertEquals("pairing rejected: bad_proof", error.message)
            fake.join()
        }
    }

    @Test
    fun forgedServerProofThrows() {
        FakeMac(mac) { peer ->
            peer.send(Message.Hello(1, "Fake Mac", Platform.MACOS))
            peer.receive()
            peer.receive()
            peer.send(Message.PairResult(true, Base64Url.encode(ByteArray(32))))
        }.use { fake ->
            val recorder = Recorder()
            val error = assertThrows<ProtocolException> {
                runBlocking { client().run("127.0.0.1", fake.port, mac.fingerprint, secret, recorder) }
            }
            assertEquals("server proof invalid", error.message)
            assertEquals(emptyList<String>(), recorder.events)
            fake.join()
        }
    }

    @Test
    fun wrongServerFingerprintFailsHandshake() {
        val impostor = SelfSigned.generate("Impostor")
        FakeMac(impostor) { }.use { fake ->
            val recorder = Recorder()
            assertThrows<SSLHandshakeException> {
                runBlocking { client().run("127.0.0.1", fake.port, mac.fingerprint, null, recorder) }
            }
            assertEquals(emptyList<String>(), recorder.events)
        }
    }

    @Test
    fun keepalivePingsContinueIdSequenceAndPongsAreDelivered() {
        FakeMac(mac) { peer ->
            peer.acceptPaired()
            assertEquals(Message.Ping(1), peer.receive())
            peer.send(Message.Pong(1))
            assertEquals(Message.Ping(2), peer.receive())
            peer.finish()
        }.use { fake ->
            val recorder = Recorder()
            runBlocking { client(pingIdleMillis = 50).run("127.0.0.1", fake.port, mac.fingerprint, null, recorder) }
            fake.join()
            assertEquals(listOf("connected:Fake Mac", "message:${Message.Pong(1)}"), recorder.events)
        }
    }

    @Test
    fun pingsWhileOnlySending() {
        val controls = CopyOnWriteArrayList<Message>()
        val sessionOver = AtomicBoolean(false)
        val sendFailure = AtomicReference<Throwable?>()
        val chunker = AtomicReference<Thread?>()
        FakeMac(mac) { peer ->
            peer.acceptPaired()
            val deadline = System.nanoTime() + 1_500_000_000L
            while (System.nanoTime() < deadline) {
                val frame = peer.read() ?: break
                if (frame !is Frame.Control) continue
                val message = MessageCodec.decode(frame.json)
                controls += message
                if (message is Message.Ping) {
                    peer.send(Message.Pong(message.id))
                    break
                }
            }
            peer.finish()
        }.use { fake ->
            val listener = object : SessionListener {
                override fun onConnected(serverName: String, sender: MessageSender) {
                    chunker.set(thread {
                        try {
                            while (!sessionOver.get()) {
                                sender.send(chunk)
                                Thread.sleep(50)
                            }
                        } catch (_: IOException) {
                            // The session ended under us.
                        } catch (t: Throwable) {
                            sendFailure.set(t)
                        }
                    })
                }
            }
            try {
                runBlocking { client(pingIdleMillis = 300).run("127.0.0.1", fake.port, mac.fingerprint, null, listener) }
            } finally {
                sessionOver.set(true)
                chunker.get()?.join(5_000)
            }
            fake.join()
            assertFalse(chunker.get()?.isAlive ?: false, "chunk sender thread did not stop")
            assertEquals(null, sendFailure.get())
            assertTrue(controls.any { it is Message.Ping && it.id >= 1 }, "no keepalive ping while only sending: $controls")
        }
    }

    @Test
    fun pairingClientDoesNotPingBeforePairingCompletes() {
        FakeMac(mac) { peer ->
            peer.send(Message.Hello(1, "Fake Mac", Platform.MACOS))
            assertEquals(phoneHello, peer.receive())
            assertTrue(peer.receive() is Message.PairRequest)
            peer.socket.soTimeout = 500
            assertThrows<SocketTimeoutException> { peer.receive() }
            peer.socket.soTimeout = 0
            peer.send(Message.PairResult(true, Base64Url.encode(Pairing.serverProof(secret, mac.fingerprint, peer.fingerprint))))
            assertEquals(Message.Ping(0), peer.receive())
            peer.finish()
        }.use { fake ->
            val recorder = Recorder()
            runBlocking { client(pingIdleMillis = 20).run("127.0.0.1", fake.port, mac.fingerprint, secret, recorder) }
            fake.join()
            assertEquals(listOf("paired:Fake Mac", "connected:Fake Mac"), recorder.events)
        }
    }

    @Test
    fun listenerSendsAreWritten() {
        val received = AtomicReference<Message>()
        FakeMac(mac) { peer ->
            peer.acceptPaired()
            received.set(peer.receive())
            peer.finish()
        }.use { fake ->
            val recorder = object : Recorder() {
                override fun onConnected(serverName: String, sender: MessageSender) {
                    super.onConnected(serverName, sender)
                    thread { sender.send(clip) }.join()
                }
            }
            runBlocking { client().run("127.0.0.1", fake.port, mac.fingerprint, null, recorder) }
            fake.join()
            assertEquals(clip, received.get())
        }
    }

    @Test
    fun silentServerTimesOut() {
        FakeMac(mac) { peer ->
            peer.acceptPaired()
            peer.drain()
        }.use { fake ->
            val recorder = Recorder()
            assertThrows<SocketTimeoutException> {
                runBlocking { client(receiveTimeoutMillis = 300).run("127.0.0.1", fake.port, mac.fingerprint, null, recorder) }
            }
            assertEquals(listOf("connected:Fake Mac"), recorder.events)
            fake.join()
        }
    }

    @Test
    fun chunkBeforeConnectedThrows() {
        FakeMac(mac) { peer ->
            peer.send(Message.Hello(1, "Fake Mac", Platform.MACOS))
            peer.send(chunk)
            peer.receive()
            peer.receive()
        }.use { fake ->
            val recorder = Recorder()
            val error = assertThrows<ProtocolException> {
                runBlocking { client().run("127.0.0.1", fake.port, mac.fingerprint, null, recorder) }
            }
            assertEquals("session_closed", error.code)
            assertEquals(emptyList<String>(), recorder.events)
            fake.join()
        }
    }

    @Test
    fun listenerExceptionEndsSession() {
        FakeMac(mac) { peer ->
            peer.acceptPaired()
            peer.send(clip)
            peer.finish()
        }.use { fake ->
            val listener = object : SessionListener {
                override fun onMessage(message: Message) = throw IllegalStateException("boom")
            }
            val error = assertThrows<IllegalStateException> {
                runBlocking { client().run("127.0.0.1", fake.port, mac.fingerprint, null, listener) }
            }
            assertEquals("boom", error.message)
            fake.join()
        }
    }

    @Test
    fun cancellationClosesTheConnection() {
        val connected = CountDownLatch(1)
        FakeMac(mac) { peer ->
            peer.acceptPaired()
            peer.drain()
        }.use { fake ->
            val listener = object : SessionListener {
                override fun onConnected(serverName: String, sender: MessageSender) = connected.countDown()
            }
            runBlocking {
                withTimeout(5_000) {
                    val job = launch(Dispatchers.IO) { client().run("127.0.0.1", fake.port, mac.fingerprint, null, listener) }
                    assertTrue(connected.await(5, TimeUnit.SECONDS))
                    job.cancelAndJoin()
                }
            }
            fake.join()
        }
    }

    @Test
    fun connectionExposesPeerFingerprint() {
        val seen = AtomicReference<ByteArray>()
        FakeMac(mac) { peer ->
            peer.acceptPaired()
            peer.finish()
        }.use { fake ->
            val listener = object : SessionListener {
                override fun onConnected(serverName: String, sender: MessageSender) {
                    seen.set((sender as Connection).peerFingerprint)
                }
            }
            runBlocking { client().run("127.0.0.1", fake.port, mac.fingerprint, null, listener) }
            fake.join()
            assertArrayEquals(mac.fingerprint, seen.get())
        }
    }
}
