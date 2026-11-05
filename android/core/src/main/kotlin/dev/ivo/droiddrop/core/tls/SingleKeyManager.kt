package dev.ivo.droiddrop.core.tls

import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

/** Presents [identity] for both client and server authentication. */
class SingleKeyManager(private val identity: DeviceIdentity) : X509ExtendedKeyManager() {
    private val algorithm = identity.privateKey.algorithm

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = aliasFor(keyType)?.let { arrayOf(it) }
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = aliasForAny(keyType)
    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = aliasForAny(keyType)
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = aliasFor(keyType)?.let { arrayOf(it) }
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = aliasFor(keyType)
    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = aliasFor(keyType)
    override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(identity.certificate)
    override fun getPrivateKey(alias: String?): PrivateKey = identity.privateKey

    private fun aliasFor(keyType: String?) = if (keyType == null || keyType == algorithm) ALIAS else null
    private fun aliasForAny(keyTypes: Array<out String>?) = if (keyTypes == null || algorithm in keyTypes) ALIAS else null

    private companion object {
        const val ALIAS = "droiddrop"
    }
}
