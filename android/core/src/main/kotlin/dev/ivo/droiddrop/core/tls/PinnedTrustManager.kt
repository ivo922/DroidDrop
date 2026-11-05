package dev.ivo.droiddrop.core.tls

import dev.ivo.droiddrop.core.pairing.Pairing
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

/** The peer presented a certificate whose fingerprint is not pinned. */
class PinMismatchException(message: String) : CertificateException(message)

/** Trusts exactly the peers whose leaf-certificate fingerprint [acceptPeer] approves. No chain or hostname checks. */
class PinnedTrustManager(private val acceptPeer: (ByteArray) -> Boolean) : X509ExtendedTrustManager() {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun check(chain: Array<out X509Certificate>?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("peer sent no certificate")
        if (!acceptPeer(Pairing.fingerprint(leaf.encoded))) throw PinMismatchException("peer certificate fingerprint is not pinned")
    }

    companion object {
        /** Constant-time match against a single pinned fingerprint. */
        fun pinnedTo(fingerprint: ByteArray): (ByteArray) -> Boolean {
            val expected = fingerprint.copyOf()
            return { MessageDigest.isEqual(it, expected) }
        }
    }
}
