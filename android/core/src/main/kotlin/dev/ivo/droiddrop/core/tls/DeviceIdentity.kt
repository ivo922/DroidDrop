package dev.ivo.droiddrop.core.tls

import dev.ivo.droiddrop.core.pairing.Pairing
import java.security.PrivateKey
import java.security.cert.X509Certificate

class DeviceIdentity(val privateKey: PrivateKey, val certificate: X509Certificate) {
    val fingerprint: ByteArray by lazy { Pairing.fingerprint(certificate.encoded) }
}
