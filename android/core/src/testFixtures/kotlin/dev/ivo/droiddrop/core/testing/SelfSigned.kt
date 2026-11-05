package dev.ivo.droiddrop.core.testing

import dev.ivo.droiddrop.core.tls.DeviceIdentity
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Date

/** P-256 self-signed identity for JVM tests and devcli (Android uses AndroidKeyStore instead). */
object SelfSigned {
    fun generate(commonName: String): DeviceIdentity {
        val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val name = X500Name("CN=$commonName")
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            name,
            BigInteger.valueOf(now),
            Date(now - 86_400_000L),
            Date(now + 20L * 365 * 86_400_000L),
            name,
            keyPair.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
        return DeviceIdentity(keyPair.private, JcaX509CertificateConverter().getCertificate(builder.build(signer)))
    }
}
