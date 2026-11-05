package dev.ivo.droiddrop

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.ivo.droiddrop.core.tls.DeviceIdentity
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.security.auth.x500.X500Principal

/** Non-exportable P-256 key in AndroidKeyStore; its auto-generated self-signed certificate is the device certificate. */
object AndroidIdentity {
    private const val ALIAS = "droiddrop-identity"
    private const val PROVIDER = "AndroidKeyStore"

    @Synchronized
    fun loadOrCreate(): DeviceIdentity {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        if (!keyStore.containsAlias(ALIAS)) {
            val now = System.currentTimeMillis()
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
                .setCertificateSubject(X500Principal("CN=DroidDrop Android"))
                .setCertificateSerialNumber(BigInteger.valueOf(now))
                .setCertificateNotBefore(Date(now - 86_400_000L))
                .setCertificateNotAfter(Date(now + 20L * 365 * 86_400_000L))
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply { initialize(spec) }.generateKeyPair()
        }
        val entry = keyStore.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
        return DeviceIdentity(entry.privateKey, entry.certificate as X509Certificate)
    }
}
