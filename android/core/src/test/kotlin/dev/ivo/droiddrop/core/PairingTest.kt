package dev.ivo.droiddrop.core

import dev.ivo.droiddrop.core.pairing.Base64Url
import dev.ivo.droiddrop.core.pairing.Pairing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

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
    fun tamperedInputsInvalidateProofs() {
        for (v in vectors) {
            val secret = Base64Url.decode32(v.str("secret"))!!
            val serverFp = Base64Url.decode32(v.str("serverFingerprint"))!!
            val clientFp = Base64Url.decode32(v.str("clientFingerprint"))!!
            val clientProof = Base64Url.decode32(v.str("clientProof"))!!
            val serverProof = Base64Url.decode32(v.str("serverProof"))!!
            val badSecret = secret.flipFirstBit()
            val badServerFp = serverFp.flipFirstBit()
            val badClientFp = clientFp.flipFirstBit()

            assertFalse(Pairing.isValidClientProof(clientProof, badSecret, serverFp, clientFp))
            assertFalse(Pairing.isValidClientProof(clientProof, secret, badServerFp, clientFp))
            assertFalse(Pairing.isValidClientProof(clientProof, secret, serverFp, badClientFp))
            assertFalse(Pairing.isValidServerProof(serverProof, badSecret, serverFp, clientFp))
            assertFalse(Pairing.isValidServerProof(serverProof, secret, badServerFp, clientFp))
            assertFalse(Pairing.isValidServerProof(serverProof, secret, serverFp, badClientFp))
        }
    }

    @Test
    fun rejectsWrongSizedInputs() {
        val ok = ByteArray(32)
        val short = ByteArray(31)
        assertThrows<IllegalArgumentException> { Pairing.clientProof(short, ok, ok) }
        assertThrows<IllegalArgumentException> { Pairing.clientProof(ok, short, ok) }
        assertThrows<IllegalArgumentException> { Pairing.serverProof(ok, ok, ByteArray(33)) }
        assertThrows<IllegalArgumentException> { Pairing.isValidServerProof(ok, short, ok, ok) }
        assertThrows<IllegalArgumentException> { Pairing.displayCode(ByteArray(8)) }
    }

    private fun ByteArray.flipFirstBit(): ByteArray = copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }

    @Test
    fun newSecretIs32RandomBytes() {
        val a = Pairing.newSecret()
        assertEquals(32, a.size)
        assertFalse(a.contentEquals(Pairing.newSecret()))
    }
}
