package dev.ivo.droiddrop

import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import dev.ivo.droiddrop.core.tls.DeviceIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.security.auth.x500.X500Principal

/** Non-exportable P-256 key in AndroidKeyStore; its auto-generated self-signed certificate is the device certificate. */
object AndroidIdentity {
    private const val TAG = "AndroidIdentity"
    private const val ALIAS = "droiddrop-identity"
    private const val PROVIDER = "AndroidKeyStore"

    private val deletes = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _generation = MutableStateFlow(0)

    /** Bumps after [delete] so the UI reloads the fingerprint. Call [loadOrCreate] off the main thread. */
    val generation: StateFlow<Int> = _generation

    @Volatile
    private var pendingDelete: Job? = null

    @Synchronized
    fun loadOrCreate(): DeviceIdentity {
        if (Looper.getMainLooper() == Looper.myLooper()) {
            Log.e(TAG, "loadOrCreate called on the main thread")
        }
        val loaded = IdentityEntries.loadOrReplace({ KeyStore.getInstance(PROVIDER).apply { load(null) } }, ALIAS) {
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
        if (loaded.replaced) Log.w(TAG, "replaced unusable keystore entry $ALIAS")
        return loaded.identity
    }

    /** Removes `droiddrop-identity` so the next [loadOrCreate] mints a new fingerprint. */
    @Synchronized
    fun delete() {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        if (keyStore.containsAlias(ALIAS)) keyStore.deleteEntry(ALIAS)
        _generation.value += 1
    }

    /** [delete] on a process-wide IO scope so an activity teardown cannot cancel it. */
    fun enqueueDelete(): Job {
        val job = deletes.launch {
            runCatching { delete() }.onFailure { Log.e(TAG, "identity delete failed", it) }
        }
        pendingDelete = job
        return job
    }

    suspend fun awaitPendingDelete() {
        pendingDelete?.join()
    }
}
