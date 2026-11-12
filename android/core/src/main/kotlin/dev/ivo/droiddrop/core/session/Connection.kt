package dev.ivo.droiddrop.core.session

import dev.ivo.droiddrop.core.protocol.Frame
import dev.ivo.droiddrop.core.protocol.FrameCodec
import dev.ivo.droiddrop.core.protocol.FrameReader
import dev.ivo.droiddrop.core.protocol.Message
import dev.ivo.droiddrop.core.protocol.MessageCodec
import dev.ivo.droiddrop.core.tls.Tls
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import javax.net.ssl.SSLSocket

/** Writes to one session. Thread-safe; [send] blocks until the frame is written to the socket. */
interface MessageSender {
    fun send(message: Message)
    fun send(frame: Frame)
}

/** Framed view of one TLS socket. [send] is thread-safe; [read] must be called from one reader thread. */
class Connection(private val socket: SSLSocket) : MessageSender, Closeable {
    private val reader = FrameReader(BufferedInputStream(socket.inputStream))
    private val output = BufferedOutputStream(socket.outputStream)

    val peerFingerprint: ByteArray = Tls.peerFingerprint(socket)

    @Volatile
    var lastSentAtNanos: Long = System.nanoTime()
        private set

    /** When [read] last returned a frame (initially, when the connection was created). */
    @Volatile
    var lastReceivedAtNanos: Long = System.nanoTime()
        private set

    /** Blocking; null on clean end-of-stream. Throws SocketTimeoutException after the socket's read timeout. */
    fun read(): Frame? = reader.read()?.also { lastReceivedAtNanos = System.nanoTime() }

    override fun send(message: Message) = send(Frame.Control(MessageCodec.encode(message)))

    @Synchronized
    override fun send(frame: Frame) {
        output.write(FrameCodec.encode(frame))
        output.flush()
        lastSentAtNanos = System.nanoTime()
    }

    override fun close() = socket.close()
}
