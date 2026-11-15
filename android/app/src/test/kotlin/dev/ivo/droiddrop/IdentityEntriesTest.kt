package dev.ivo.droiddrop

import dev.ivo.droiddrop.core.testing.SelfSigned
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.KeyStoreSpi
import java.security.cert.Certificate
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import javax.crypto.spec.SecretKeySpec

class IdentityEntriesTest {
    /** In-memory keystore whose getEntry can be made to throw, like a flaky AndroidKeyStore read. */
    private class FakeSpi : KeyStoreSpi() {
        val entries = mutableMapOf<String, KeyStore.Entry>()
        var failReads = false
        var deletes = 0

        override fun engineGetEntry(alias: String, protParam: KeyStore.ProtectionParameter?): KeyStore.Entry? {
            if (failReads) throw KeyStoreException("keystore daemon unavailable")
            return entries[alias]
        }
        override fun engineSetEntry(alias: String, entry: KeyStore.Entry, protParam: KeyStore.ProtectionParameter?) {
            entries[alias] = entry
        }
        override fun engineDeleteEntry(alias: String) {
            deletes++
            entries.remove(alias)
        }
        override fun engineContainsAlias(alias: String) = alias in entries
        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
        override fun engineGetKey(alias: String, password: CharArray?): Key? = null
        override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String): Certificate? = null
        override fun engineGetCreationDate(alias: String): Date? = null
        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<Certificate>?) = Unit
        override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<Certificate>?) = Unit
        override fun engineSetCertificateEntry(alias: String, cert: Certificate) = Unit
        override fun engineAliases(): Enumeration<String> = Collections.enumeration(entries.keys)
        override fun engineSize() = entries.size
        override fun engineIsKeyEntry(alias: String) = alias in entries
        override fun engineIsCertificateEntry(alias: String) = false
        override fun engineGetCertificateAlias(cert: Certificate): String? = null
        override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
    }

    private val spi = FakeSpi()
    private val keyStore = object : KeyStore(spi, null, "fake") {}.apply { load(null) }
    private val stored = SelfSigned.generate("stored")
    private val fresh = SelfSigned.generate("fresh")
    private var generated = 0

    private fun privateKeyEntry(identity: dev.ivo.droiddrop.core.tls.DeviceIdentity) =
        KeyStore.PrivateKeyEntry(identity.privateKey, arrayOf(identity.certificate))

    private fun load() = IdentityEntries.loadOrReplace({ keyStore }, ALIAS) {
        generated++
        spi.entries[ALIAS] = privateKeyEntry(fresh)
    }

    @Test
    fun keepsAUsableStoredIdentity() {
        spi.entries[ALIAS] = privateKeyEntry(stored)
        val result = load()
        assertArrayEquals(stored.fingerprint, result.identity.fingerprint)
        assertFalse(result.replaced)
        assertEquals(0, generated)
    }

    @Test
    fun aReadThatThrowsNeverDeletesTheIdentity() {
        spi.entries[ALIAS] = privateKeyEntry(stored)
        spi.failReads = true
        assertThrows<KeyStoreException> { load() }
        assertEquals(0, spi.deletes)
        assertEquals(0, generated)
        assertTrue(ALIAS in spi.entries)
    }

    @Test
    fun replacesAnEntryOfTheWrongType() {
        spi.entries[ALIAS] = KeyStore.SecretKeyEntry(SecretKeySpec(ByteArray(16), "AES"))
        val result = load()
        assertEquals(1, spi.deletes)
        assertEquals(1, generated)
        assertTrue(result.replaced)
        assertArrayEquals(fresh.fingerprint, result.identity.fingerprint)
    }

    @Test
    fun generatesWhenNothingIsStored() {
        val result = load()
        assertEquals(1, generated)
        assertEquals(0, spi.deletes)
        assertArrayEquals(fresh.fingerprint, result.identity.fingerprint)
    }

    @Test
    fun failsWhenGenerationLeavesNoUsableEntry() {
        assertThrows<IllegalStateException> { IdentityEntries.loadOrReplace({ keyStore }, ALIAS) {} }
    }

    private companion object {
        const val ALIAS = "droiddrop-identity"
    }
}
