package dev.ivo.droiddrop

import dev.ivo.droiddrop.core.protocol.ProtocolException
import dev.ivo.droiddrop.core.tls.PinMismatchException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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
    fun pinMismatchIsFoundEvenWhenNested() {
        val pin = PinMismatchException("peer certificate fingerprint is not pinned")
        val ssl = SSLHandshakeException("handshake")
        ssl.initCause(pin)
        val wrapped = IOException("session failed")
        wrapped.initCause(ssl)

        assertEquals(ReconnectPolicy.FailureKind.PinMismatch, ReconnectPolicy.classify(pin))
        assertEquals(ReconnectPolicy.FailureKind.PinMismatch, ReconnectPolicy.classify(ssl))
        assertEquals(ReconnectPolicy.FailureKind.PinMismatch, ReconnectPolicy.classify(wrapped))

        val counter = ReconnectHistory()
        counter.record(ReconnectPolicy.classify(wrapped))
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
        val counter = ReconnectHistory()
        for (error in samples) {
            assertEquals(ReconnectPolicy.FailureKind.Retry, ReconnectPolicy.classify(error), error.toString())
            counter.record(ReconnectPolicy.classify(error))
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
        val counter = ReconnectHistory()
        for (error in samples) {
            assertEquals(ReconnectPolicy.FailureKind.Retry, ReconnectPolicy.classify(error), error.toString())
            counter.record(ReconnectPolicy.classify(error))
        }
        assertEquals(0, counter.consecutive)
    }

    @Test
    fun threeConsecutiveRejectionsMeanRejectedAndRetriesDoNotResetTheStreak() {
        val counter = ReconnectHistory()
        counter.record(ReconnectPolicy.FailureKind.Rejection)
        counter.record(ReconnectPolicy.FailureKind.Retry)
        assertEquals(1, counter.consecutive)
        counter.record(ReconnectPolicy.FailureKind.Rejection)
        assertFalse(counter.rejected)
        counter.record(ReconnectPolicy.FailureKind.Rejection)
        assertTrue(counter.rejected)
        assertEquals(3, counter.consecutive)
    }

    @Test
    fun connectedResetsTheStreak() {
        val counter = ReconnectHistory()
        repeat(3) { counter.record(ReconnectPolicy.FailureKind.Rejection) }
        counter.onConnected()
        assertEquals(0, counter.consecutive)
        assertFalse(counter.rejected)
    }

    // A pin mismatch only proves that some other device answered (a reassigned DHCP address, a dev server on the
    // default port, or an attacker on café Wi-Fi), so the round never unpairs: it drops that remembered address.
    @Test
    fun pinMismatchMarksTheAddressStaleWithoutUnpairing() {
        val round = ReconnectRound(ReconnectHistory())
        round.failed(Endpoint("10.0.0.5", 47321), ReconnectPolicy.FailureKind.PinMismatch)
        val verdict = round.verdict()
        assertEquals(setOf("10.0.0.5"), verdict.staleHosts)
        assertEquals(ReconnectRound.Problem.CertificateChanged, verdict.problem)
    }

    // A Mac reset has a new fingerprint: the mismatching host is dropped and Bonjour never matches again, so later
    // rounds have nothing to try. The pair-again hint must stay until a connection succeeds.
    @Test
    fun certificateChangedStaysUntilAConnection() {
        val history = ReconnectHistory()
        ReconnectRound(history).failed(Endpoint("10.0.0.5", 47321), ReconnectPolicy.FailureKind.PinMismatch)
        val first = ReconnectRound(history).also {
            it.failed(Endpoint("10.0.0.5", 47321), ReconnectPolicy.FailureKind.PinMismatch)
        }.verdict()
        assertEquals(ReconnectRound.Problem.CertificateChanged, first.problem)
        assertEquals(ReconnectRound.Problem.CertificateChanged, ReconnectRound(history).verdict().problem)
        val retrying = ReconnectRound(history).also {
            it.failed(Endpoint("10.0.0.6", 47321), ReconnectPolicy.FailureKind.Retry)
        }
        assertEquals(ReconnectRound.Problem.CertificateChanged, retrying.verdict().problem)
        history.onConnected()
        assertNull(ReconnectRound(history).verdict().problem)
    }

    @Test
    fun mismatchNextToAnotherFailureIsJustSearching() {
        val round = ReconnectRound(ReconnectHistory())
        round.failed(Endpoint("10.0.0.5", 47321), ReconnectPolicy.FailureKind.PinMismatch)
        round.failed(Endpoint("10.0.0.6", 47321), ReconnectPolicy.FailureKind.Retry)
        val verdict = round.verdict()
        assertEquals(setOf("10.0.0.5"), verdict.staleHosts)
        assertNull(verdict.problem)
    }

    @Test
    fun rejectionsAcrossRoundsReportRejected() {
        val rejections = ReconnectHistory()
        repeat(2) {
            val round = ReconnectRound(rejections)
            round.failed(Endpoint("10.0.0.5", 47321), ReconnectPolicy.FailureKind.Rejection)
            assertNull(round.verdict().problem)
        }
        val round = ReconnectRound(rejections)
        round.failed(Endpoint("10.0.0.5", 47321), ReconnectPolicy.FailureKind.Rejection)
        val verdict = round.verdict()
        assertEquals(ReconnectRound.Problem.Rejected, verdict.problem)
        assertTrue(verdict.staleHosts.isEmpty())
    }

    @Test
    fun retriesOnlyAreJustSearching() {
        val round = ReconnectRound(ReconnectHistory())
        round.failed(Endpoint("10.0.0.5", 47321), ReconnectPolicy.FailureKind.Retry)
        assertNull(round.verdict().problem)
        assertTrue(round.verdict().staleHosts.isEmpty())
    }

    @Test
    fun problemMessagesNameTheMacAndSayPairAgain() {
        assertEquals(
            "MacBook no longer accepts this phone. Pair again.",
            ReconnectRound.Problem.Rejected.message("MacBook"),
        )
        assertEquals(
            "MacBook answered with a different certificate. If DroidDrop was reset on the Mac, pair again.",
            ReconnectRound.Problem.CertificateChanged.message("MacBook"),
        )
    }

    @Test
    fun alreadyPairedIsRecognizedOnlyByItsCode() {
        val already = ProtocolException(ProtocolException.ALREADY_PAIRED, "server closed after pair_request")
        assertTrue(ReconnectPolicy.alreadyPaired(already))
        assertTrue(ReconnectPolicy.alreadyPaired(IOException("wrapped", already)))
        assertFalse(ReconnectPolicy.alreadyPaired(ProtocolException(ProtocolException.SESSION_CLOSED, "pairing rejected")))
        assertFalse(ReconnectPolicy.alreadyPaired(SocketException("Connection reset")))
    }

    @Test
    fun backoffDoublesUpToTheCap() {
        assertEquals(2_000L, ReconnectPolicy.nextBackoff(1_000L, woke = false))
        assertEquals(60_000L, ReconnectPolicy.nextBackoff(32_000L, woke = false))
        assertEquals(60_000L, ReconnectPolicy.nextBackoff(60_000L, woke = false))
    }

    @Test
    fun aWakeResetsTheBackoff() {
        // A new network, an opened app or a queued file means "try now", and the next wait starts short again.
        assertEquals(ReconnectPolicy.INITIAL_BACKOFF_MILLIS, ReconnectPolicy.nextBackoff(60_000L, woke = true))
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
