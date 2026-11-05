package dev.ivo.droiddrop

import dev.ivo.droiddrop.core.protocol.ProtocolException
import dev.ivo.droiddrop.core.tls.PinMismatchException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * What a failed connect means for the reconnect loop.
 *
 * A failure before TCP is up (refused, connect timeout, unreachable) backs off and does not count.
 * Once TCP has connected, every failure before [dev.ivo.droiddrop.core.session.SessionListener.onConnected]
 * counts toward [MAX_REJECTIONS], including [ProtocolException] `session_closed`. A [PinMismatchException]
 * anywhere in the cause chain does not count: the pinned Mac certificate changed, so the loop unpairs immediately.
 */
internal object ReconnectPolicy {
    const val MAX_REJECTIONS = 3

    enum class FailureKind { Retry, Rejection, PinMismatch }

    fun classify(error: Throwable): FailureKind {
        val chain = generateSequence(error) { it.cause }.toList()
        if (chain.any { it is PinMismatchException }) return FailureKind.PinMismatch
        if (chain.any { it is SSLException || it is ProtocolException }) return FailureKind.Rejection
        if (chain.any(::isBeforeTcp)) return FailureKind.Retry
        return FailureKind.Rejection
    }

    /** SPEC §4.1 hello name is non-empty. Global device name, then model, then "Android". */
    fun deviceName(global: String?, model: String?): String =
        listOf(global, model).firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) } ?: "Android"

    private fun isBeforeTcp(error: Throwable): Boolean = when (error) {
        is UnknownHostException, is ConnectException, is NoRouteToHostException, is PortUnreachableException -> true
        is SocketTimeoutException -> isConnectTimeout(error.message)
        is SocketException -> isPreConnectSocket(error.message)
        else -> false
    }

    /** Connect timeouts say "connect". A read timeout ("Read timed out") is after TCP and must count. */
    private fun isConnectTimeout(message: String?): Boolean {
        val text = message?.lowercase() ?: return true
        return text.contains("connect")
    }

    private fun isPreConnectSocket(message: String?): Boolean {
        val text = message?.lowercase() ?: return false
        return PRE_CONNECT_MARKERS.any { text.contains(it) }
    }

    private val PRE_CONNECT_MARKERS = listOf(
        "connection refused",
        "econnrefused",
        "network is unreachable",
        "enetunreach",
        "no route to host",
        "host is unreachable",
        "ehostunreach",
        "connection timed out",
        "etimedout",
        "permission denied",
        "operation not permitted",
        "eperm",
        "eacces",
    )
}

/** Consecutive post-TCP rejections. Pre-TCP retries neither increment nor reset the streak. */
internal class RejectionCounter(private val max: Int = ReconnectPolicy.MAX_REJECTIONS) {
    var consecutive: Int = 0
        private set

    fun onConnected() {
        consecutive = 0
    }

    fun onFailure(kind: ReconnectPolicy.FailureKind): Outcome = when (kind) {
        ReconnectPolicy.FailureKind.Retry -> Outcome.Backoff
        ReconnectPolicy.FailureKind.PinMismatch -> Outcome.UnpairPinMismatch
        ReconnectPolicy.FailureKind.Rejection -> {
            consecutive++
            if (consecutive >= max) Outcome.UnpairRejections else Outcome.Backoff
        }
    }

    enum class Outcome { Backoff, UnpairRejections, UnpairPinMismatch }
}
