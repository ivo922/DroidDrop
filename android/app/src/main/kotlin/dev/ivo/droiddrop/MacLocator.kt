package dev.ivo.droiddrop

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

data class Endpoint(val host: String, val port: Int)

/** Finds the paired Mac via Bonjour (`_droiddrop._tcp`, TXT `fp`). Call [close] when the owner is destroyed. */
class MacLocator(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val wifi = context.getSystemService(WifiManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()

    suspend fun find(fingerprint: String, timeoutMillis: Long = 5_000): Endpoint? {
        val lock = wifi.createMulticastLock("droiddrop-nsd").apply {
            setReferenceCounted(false)
            acquire()
        }
        val result = CompletableDeferred<Endpoint?>()
        val infoCallbacks = CopyOnWriteArrayList<NsdManager.ServiceInfoCallback>()
        // onServiceFound runs on NsdManager's thread and can race the cleanup below; once it starts, register nothing.
        val registration = Any()
        var finished = false
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(service: NsdServiceInfo) {
                val callback = object : NsdManager.ServiceInfoCallback {
                    override fun onServiceUpdated(info: NsdServiceInfo) {
                        val advertised = info.attributes["fp"]?.decodeToString()?.trimEnd('\u0000')?.trim()
                        val address = info.hostAddresses.firstOrNull { it is Inet4Address }
                        if (advertised == fingerprint && address != null) {
                            result.complete(Endpoint(address.hostAddress!!, info.port))
                        }
                    }
                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}
                    override fun onServiceLost() {}
                    override fun onServiceInfoCallbackUnregistered() {}
                }
                synchronized(registration) {
                    if (finished) return
                    infoCallbacks += callback
                    nsd.registerServiceInfoCallback(service, executor, callback)
                }
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { result.complete(null) }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(service: NsdServiceInfo) {}
        }
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
            return withTimeoutOrNull(timeoutMillis) { result.await() }
        } finally {
            synchronized(registration) { finished = true }
            infoCallbacks.forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } }
            runCatching { nsd.stopServiceDiscovery(discovery) }
            runCatching { lock.release() }
        }
    }

    /** Stops the callback thread; [find] must not be called afterwards. */
    fun close() {
        executor.shutdown()
    }

    private companion object {
        const val SERVICE_TYPE = "_droiddrop._tcp"
    }
}
