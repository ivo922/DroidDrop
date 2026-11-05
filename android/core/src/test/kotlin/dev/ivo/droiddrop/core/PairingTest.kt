package dev.ivo.droiddrop.core

import dev.ivo.droiddrop.core.pairing.Base64Url
import dev.ivo.droiddrop.core.pairing.Pairing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PairingTest {
    private val vectors = Fixtures.load("pairing").list("vectors")

    @Test
    fun fingerprintsAndProofsMatchVectors() {
        for (v in vectors) {
            val secret = Base64Url.decode32(v.str("secret"))!!
            val serverFp = Pairing.fingerprint(v.str("serverCertificateHex").hexToBytes())
            val clientFp = Pairing.fingerprint(v.str("clientCertificateHex").hexToBytes())
            assertEquals(v.str("serverFingerprint"), Base64Url.encode(serverFp))
            assertEquals(v.str("clientFingerprint"), Base64Url.encode(clientFp))
            assertEquals(v.str("serverDisplayCode"), Pairing.displayCode(serverFp))
            assertEquals(v.str("clientDisplayCode"), Pairing.displayCode(clientFp))

            val clientProof = Pairing.clientProof(secret, serverFp, clientFp)
            val serverProof = Pairing.serverProof(secret, serverFp, clientFp)
            assertEquals(v.str("clientProof"), Base64Url.encode(clientProof))
            assertEquals(v.str("serverProof"), Base64Url.encode(serverProof))
            assertTrue(Pairing.isValidClientProof(clientProof, secret, serverFp, clientFp))
            assertTrue(Pairing.isValidServerProof(serverProof, secret, serverFp, clientFp))
            assertFalse(Pairing.isValidClientProof(serverProof, secret, serverFp, clientFp))
        }
    }

    @Test
    fun newSecretIs32RandomBytes() {
        val a = Pairing.newSecret()
        assertEquals(32, a.size)
        assertFalse(a.contentEquals(Pairing.newSecret()))
    }
}
