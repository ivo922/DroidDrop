package dev.ivo.droiddrop.core.tls

import dev.ivo.droiddrop.core.pairing.Pairing
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

object Tls {
    private const val PROTOCOL = "TLSv1.3"
    private const val HANDSHAKE_TIMEOUT_MILLIS = 10_000

    fun context(identity: DeviceIdentity, acceptPeer: (ByteArray) -> Boolean): SSLContext =
        SSLContext.getInstance(PROTOCOL).apply {
            init(arrayOf(SingleKeyManager(identity)), arrayOf(PinnedTrustManager(acceptPeer)), SecureRandom())
        }

    /** Connects and completes the TLS handshake. Reads time out after [readTimeoutMillis] of silence (SPEC §5 keepalive). */
    fun connect(
        context: SSLContext,
        host: String,
        port: Int,
        connectTimeoutMillis: Int = 5_000,
        readTimeoutMillis: Int = 90_000,
    ): SSLSocket {
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(host, port), connectTimeoutMillis)
            raw.tcpNoDelay = true
            raw.keepAlive = true
            val socket = context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
            socket.enabledProtocols = arrayOf(PROTOCOL)
            socket.soTimeout = HANDSHAKE_TIMEOUT_MILLIS
            socket.startHandshake()
            socket.soTimeout = readTimeoutMillis
            return socket
        } catch (e: Exception) {
            raw.close()
            throw e
        }
    }

    fun peerFingerprint(socket: SSLSocket): ByteArray = Pairing.fingerprint(socket.session.peerCertificates[0].encoded)
}
