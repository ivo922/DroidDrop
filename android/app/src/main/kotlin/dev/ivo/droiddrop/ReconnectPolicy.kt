package dev.ivo.droiddrop

import dev.ivo.droiddrop.core.protocol.ProtocolException
import dev.ivo.droiddrop.core.tls.PinMismatchException
import javax.net.ssl.SSLException

/**
 * What a failed connect means for the reconnect loop.
 *
 * Only the Mac saying no counts toward [MAX_REJECTIONS]: a TLS certificate alert (SPEC §5 rule 1: the Mac fails
 * the handshake for an unpaired client) or a [ProtocolException], such as `session_closed` before the session was
 * established. Resets, timeouts and handshakes cut off by the network only back off: a Wi-Fi drop must never
 * cost the pairing. A [PinMismatchException] anywhere in the cause chain means the Mac's certificate changed,
 * so the loop unpairs immediately.
 */
internal object ReconnectPolicy {
    const val MAX_REJECTIONS = 3

    enum class FailureKind { Retry, Rejection, PinMismatch }

    fun classify(error: Throwable): FailureKind {
        val chain = generateSequence(error) { it.cause }.toList()
        if (chain.any { it is PinMismatchException }) return FailureKind.PinMismatch
        if (chain.any { it is ProtocolException || it is SSLException && isCertificateAlert(it.message) }) {
            return FailureKind.Rejection
        }
        return FailureKind.Retry
    }

    /** SPEC §4.1 hello name is non-empty. Global device name, then model, then "Android". */
    fun deviceName(global: String?, model: String?): String =
        listOf(global, model).firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) } ?: "Android"

    /** The Mac's verify step rejects with certificate_unknown; JSSE and Conscrypt both name the alert in the message. */
    private fun isCertificateAlert(message: String?): Boolean {
        val text = message?.lowercase() ?: return false
        return CERTIFICATE_ALERTS.any { text.contains(it) }
    }

    private val CERTIFICATE_ALERTS = listOf("certificate_unknown", "bad_certificate", "certificate_required")
}

/** Consecutive rejections. Retries neither increment nor reset the streak. */
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
