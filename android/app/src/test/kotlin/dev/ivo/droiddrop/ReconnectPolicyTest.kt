package dev.ivo.droiddrop

import dev.ivo.droiddrop.core.protocol.ProtocolException
import dev.ivo.droiddrop.core.tls.PinMismatchException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

class ReconnectPolicyTest {
    @Test
    fun sessionClosedAfterTcpCountsAsRejection() {
        val error = ProtocolException("session_closed", "server closed before session was established")
        assertEquals(ReconnectPolicy.FailureKind.Rejection, ReconnectPolicy.classify(error))
    }

    @Test
    fun pinMismatchUnpairsImmediatelyEvenWhenNested() {
        val pin = PinMismatchException("peer certificate fingerprint is not pinned")
        val ssl = SSLHandshakeException("handshake")
        ssl.initCause(pin)
        val wrapped = IOException("session failed")
        wrapped.initCause(ssl)

        assertEquals(ReconnectPolicy.FailureKind.PinMismatch, ReconnectPolicy.classify(pin))
        assertEquals(ReconnectPolicy.FailureKind.PinMismatch, ReconnectPolicy.classify(ssl))
        assertEquals(ReconnectPolicy.FailureKind.PinMismatch, ReconnectPolicy.classify(wrapped))

        val counter = RejectionCounter()
        assertEquals(RejectionCounter.Outcome.UnpairPinMismatch, counter.onFailure(ReconnectPolicy.classify(wrapped)))
        assertEquals(0, counter.consecutive)
    }

    @Test
    fun certificateAlertFromTheMacCounts() {
        val samples = listOf(
            SSLHandshakeException("Received fatal alert: certificate_unknown"),
            SSLHandshakeException(
                "Read error: ssl=0x7b: Failure in SSL library, usually a protocol error\n" +
                    "error:10000416:SSL routines:OPENSSL_internal:SSLV3_ALERT_CERTIFICATE_UNKNOWN",
            ),
        )
        for (error in samples) {
            assertEquals(ReconnectPolicy.FailureKind.Rejection, ReconnectPolicy.classify(error), error.toString())
        }
    }

    @Test
    fun networkFailuresAfterTcpDoNotCount() {
        val noCert = SSLHandshakeException("no cert")
        noCert.initCause(CertificateException("peer sent no certificate"))
        val samples = listOf(
            SocketException("Connection reset"),
            SSLException("Read error: ssl=0x7b: I/O error during system call, Connection reset by peer"),
            SSLHandshakeException("connection closed during handshake"),
            SocketTimeoutException("Read timed out"),
            noCert,
        )
        val counter = RejectionCounter()
        for (error in samples) {
            assertEquals(ReconnectPolicy.FailureKind.Retry, ReconnectPolicy.classify(error), error.toString())
            assertEquals(RejectionCounter.Outcome.Backoff, counter.onFailure(ReconnectPolicy.classify(error)))
        }
        assertEquals(0, counter.consecutive)
    }

    @Test
    fun refusedTimeoutAndUnreachableDoNotCount() {
        val samples = listOf(
            ConnectException("Connection refused"),
            ConnectException("failed to connect to /10.0.0.5 (port 47321): connect failed: ECONNREFUSED (Connection refused)"),
            SocketTimeoutException("Connect timed out"),
            SocketTimeoutException("failed to connect to /10.0.0.2 (port 47321) after 5000ms"),
            SocketTimeoutException(),
            NoRouteToHostException("No route to host"),
            UnknownHostException("mac.local"),
            SocketException("Network is unreachable"),
            SocketException("connect failed: ETIMEDOUT (Connection timed out)"),
            SocketException("connect failed: EPERM (Operation not permitted)"),
        )
        val counter = RejectionCounter()
        for (error in samples) {
            assertEquals(ReconnectPolicy.FailureKind.Retry, ReconnectPolicy.classify(error), error.toString())
            assertEquals(RejectionCounter.Outcome.Backoff, counter.onFailure(ReconnectPolicy.classify(error)))
        }
        assertEquals(0, counter.consecutive)
    }

    @Test
    fun threeConsecutiveRejectionsUnpairAndRetriesDoNotResetTheStreak() {
        val counter = RejectionCounter()
        assertEquals(RejectionCounter.Outcome.Backoff, counter.onFailure(ReconnectPolicy.FailureKind.Rejection))
        assertEquals(RejectionCounter.Outcome.Backoff, counter.onFailure(ReconnectPolicy.FailureKind.Retry))
        assertEquals(1, counter.consecutive)
        assertEquals(RejectionCounter.Outcome.Backoff, counter.onFailure(ReconnectPolicy.FailureKind.Rejection))
        assertEquals(RejectionCounter.Outcome.UnpairRejections, counter.onFailure(ReconnectPolicy.FailureKind.Rejection))
        assertEquals(3, counter.consecutive)
    }

    @Test
    fun connectedResetsTheStreak() {
        val counter = RejectionCounter()
        counter.onFailure(ReconnectPolicy.FailureKind.Rejection)
        counter.onFailure(ReconnectPolicy.FailureKind.Rejection)
        counter.onConnected()
        assertEquals(0, counter.consecutive)
        assertEquals(RejectionCounter.Outcome.Backoff, counter.onFailure(ReconnectPolicy.FailureKind.Rejection))
        assertEquals(1, counter.consecutive)
    }

    @Test
    fun deviceNameIsNeverBlank() {
        assertEquals("Pixel", ReconnectPolicy.deviceName("Pixel", "sdk"))
        assertEquals("sdk", ReconnectPolicy.deviceName(null, "sdk"))
        assertEquals("sdk", ReconnectPolicy.deviceName("  ", "sdk"))
        assertEquals("Android", ReconnectPolicy.deviceName(null, null))
        assertEquals("Android", ReconnectPolicy.deviceName("", " "))
        assertEquals("Living Room", ReconnectPolicy.deviceName(" Living Room ", "Pixel"))
    }
}
