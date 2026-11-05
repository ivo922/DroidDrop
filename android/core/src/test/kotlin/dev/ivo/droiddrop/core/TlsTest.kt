package dev.ivo.droiddrop.core

import dev.ivo.droiddrop.core.testing.SelfSigned
import dev.ivo.droiddrop.core.tls.PinMismatchException
import dev.ivo.droiddrop.core.tls.PinnedTrustManager
import dev.ivo.droiddrop.core.tls.Tls
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.security.cert.CertificateException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

class TlsTest {
    private val server = SelfSigned.generate("Server")
    private val client = SelfSigned.generate("Client")

    private fun listen(acceptClient: (ByteArray) -> Boolean): SSLServerSocket =
        (Tls.context(server, acceptClient).serverSocketFactory
            .createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket).apply {
            enabledProtocols = arrayOf("TLSv1.3")
            needClientAuth = true
        }

    private fun echoOnce(listener: SSLServerSocket, clientFingerprint: CompletableFuture<ByteArray>) = thread {
        runCatching {
            (listener.accept() as SSLSocket).use { socket ->
                socket.startHandshake()
                clientFingerprint.complete(Tls.peerFingerprint(socket))
                socket.outputStream.write(socket.inputStream.read())
                socket.outputStream.flush()
            }
        }.onFailure { clientFingerprint.completeExceptionally(it) }
    }

    @Test
    fun mutualPinnedHandshake() {
        listen { it.contentEquals(client.fingerprint) }.use { listener ->
            val seen = CompletableFuture<ByteArray>()
            echoOnce(listener, seen)
            Tls.connect(Tls.context(client) { it.contentEquals(server.fingerprint) }, "127.0.0.1", listener.localPort).use { socket ->
                assertArrayEquals(server.fingerprint, Tls.peerFingerprint(socket))
                assertEquals("TLSv1.3", socket.session.protocol)
                socket.outputStream.write(42)
                socket.outputStream.flush()
                assertEquals(42, socket.inputStream.read())
            }
            assertArrayEquals(client.fingerprint, seen.get(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun clientRejectsUnpinnedServer() {
        listen { true }.use { listener ->
            echoOnce(listener, CompletableFuture())
            val e = assertThrows<SSLHandshakeException> {
                Tls.connect(Tls.context(client) { false }, "127.0.0.1", listener.localPort)
            }
            assertTrue(generateSequence<Throwable>(e) { it.cause }.any { it is PinMismatchException })
        }
    }

    @Test
    fun serverRejectsUnknownClient() {
        listen { false }.use { listener ->
            echoOnce(listener, CompletableFuture())
            assertThrows<IOException> {
                Tls.connect(Tls.context(client) { it.contentEquals(server.fingerprint) }, "127.0.0.1", listener.localPort).use {
                    it.outputStream.write(1)
                    it.outputStream.flush()
                    if (it.inputStream.read() < 0) throw EOFException("closed by server")
                }
            }
        }
    }

    @Test
    fun trustManagerThrowsPinMismatchAndAcceptsPinned() {
        val chain = arrayOf(server.certificate)
        PinnedTrustManager(PinnedTrustManager.pinnedTo(server.fingerprint)).checkServerTrusted(chain, "EC")
        val e = assertThrows<CertificateException> {
            PinnedTrustManager(PinnedTrustManager.pinnedTo(client.fingerprint)).checkServerTrusted(chain, "EC")
        }
        assertTrue(e is PinMismatchException)
        val empty = assertThrows<CertificateException> { PinnedTrustManager { true }.checkClientTrusted(emptyArray(), "EC") }
        assertFalse(empty is PinMismatchException)
    }
}
