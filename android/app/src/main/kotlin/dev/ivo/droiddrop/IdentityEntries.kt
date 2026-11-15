package dev.ivo.droiddrop

import dev.ivo.droiddrop.core.tls.DeviceIdentity
import java.security.KeyStore
import java.security.cert.X509Certificate

/**
 * Keeps the stored identity, replacing it only when the entry was read and is unusable (wrong type, no X.509
 * certificate). A read that throws propagates and deletes nothing: a flaky keystore must not cost the pairing,
 * because a new fingerprint makes the Mac reject the phone. Pure, so it can be unit tested.
 */
internal object IdentityEntries {
    class Loaded(val identity: DeviceIdentity, val replaced: Boolean)

    /** [open] returns a loaded keystore; it is called again after [generate] to see the new entry. */
    fun loadOrReplace(open: () -> KeyStore, alias: String, generate: () -> Unit): Loaded {
        val keyStore = open()
        var replaced = false
        if (keyStore.containsAlias(alias)) {
            usable(keyStore, alias)?.let { return Loaded(it, replaced = false) }
            keyStore.deleteEntry(alias)
            replaced = true
        }
        generate()
        val identity = usable(open(), alias) ?: error("keystore did not produce a usable identity")
        return Loaded(identity, replaced)
    }

    private fun usable(keyStore: KeyStore, alias: String): DeviceIdentity? {
        val entry = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry ?: return null
        val certificate = entry.certificate as? X509Certificate ?: return null
        return DeviceIdentity(entry.privateKey, certificate)
    }
}
