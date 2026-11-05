package dev.ivo.droiddrop.core.pairing

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Pairing {
    private val random = SecureRandom()

    fun fingerprint(certificateDer: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(certificateDer)

    fun displayCode(fingerprint: ByteArray): String =
        HexFormat.of().withUpperCase().formatHex(fingerprint, 0, 8).chunked(4).joinToString(" ")

    fun clientProof(secret: ByteArray, serverFingerprint: ByteArray, clientFingerprint: ByteArray): ByteArray =
        hmac(secret, "client", serverFingerprint, clientFingerprint)

    fun serverProof(secret: ByteArray, serverFingerprint: ByteArray, clientFingerprint: ByteArray): ByteArray =
        hmac(secret, "server", serverFingerprint, clientFingerprint)

    fun isValidClientProof(proof: ByteArray, secret: ByteArray, serverFingerprint: ByteArray, clientFingerprint: ByteArray): Boolean =
        MessageDigest.isEqual(proof, clientProof(secret, serverFingerprint, clientFingerprint))

    fun isValidServerProof(proof: ByteArray, secret: ByteArray, serverFingerprint: ByteArray, clientFingerprint: ByteArray): Boolean =
        MessageDigest.isEqual(proof, serverProof(secret, serverFingerprint, clientFingerprint))

    fun newSecret(): ByteArray = ByteArray(32).also(random::nextBytes)

    private fun hmac(secret: ByteArray, role: String, serverFingerprint: ByteArray, clientFingerprint: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        mac.update("droiddrop-pair-v1|$role|".toByteArray(Charsets.US_ASCII))
        mac.update(serverFingerprint)
        mac.update(clientFingerprint)
        return mac.doFinal()
    }
}
