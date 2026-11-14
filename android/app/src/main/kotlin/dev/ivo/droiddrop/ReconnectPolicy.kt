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
 * cost the pairing. A [PinMismatchException] anywhere in the cause chain means some other certificate answered.
 *
 * None of these unpair: alerts sent before the server certificate are unauthenticated, and a mismatch only proves
 * that another device answered. They change what the phone shows ([ReconnectRound]); the pairing and the
 * identity are only ever removed by the user.
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

/** Consecutive rejections. Retries and pin mismatches neither increment nor reset the streak. */
internal class RejectionCounter(private val max: Int = ReconnectPolicy.MAX_REJECTIONS) {
    var consecutive: Int = 0
        private set

    /** The Mac has said no [max] times in a row since the last connection. */
    val rejected: Boolean get() = consecutive >= max

    fun onConnected() {
        consecutive = 0
    }

    fun record(kind: ReconnectPolicy.FailureKind) {
        if (kind == ReconnectPolicy.FailureKind.Rejection) consecutive++
    }
}

/** The failures of one pass over the candidate endpoints, and what to show and forget after it. Never unpairs. */
internal class ReconnectRound(private val rejections: RejectionCounter) {
    enum class Problem {
        Rejected,
        CertificateChanged;

        fun message(macName: String): String = when (this) {
            Rejected -> "$macName no longer accepts this phone. Pair again."
            CertificateChanged -> "$macName answered with a different certificate. If DroidDrop was reset on the Mac, pair again."
        }
    }

    /** [problem] is null while the phone is simply still looking. [staleHosts] answered with another certificate. */
    data class Verdict(val problem: Problem?, val staleHosts: Set<String>)

    private val mismatched = linkedSetOf<String>()
    private var otherFailures = 0

    fun failed(endpoint: Endpoint, kind: ReconnectPolicy.FailureKind) {
        if (kind == ReconnectPolicy.FailureKind.PinMismatch) {
            mismatched += endpoint.host
        } else {
            otherFailures++
            rejections.record(kind)
        }
    }

    fun verdict(): Verdict {
        val problem = when {
            rejections.rejected -> Problem.Rejected
            mismatched.isNotEmpty() && otherFailures == 0 -> Problem.CertificateChanged
            else -> null
        }
        return Verdict(problem, mismatched.toSet())
    }
}
