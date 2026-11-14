package dev.ivo.droiddrop

import android.content.Context
import androidx.core.content.edit

data class PairedMac(val name: String, val fingerprint: String, val hosts: List<String>, val port: Int)

class PairingStore(context: Context) {
    private val prefs = context.getSharedPreferences("pairing", Context.MODE_PRIVATE)
    private val lock = Any()

    fun load(): PairedMac? = synchronized(lock) { read() }

    fun save(mac: PairedMac) = synchronized(lock) { write(mac) }

    /** Puts a working address first so the next reconnect tries it before NSD results go stale. */
    fun rememberAddress(host: String, port: Int) {
        synchronized(lock) {
            val mac = read() ?: return
            write(mac.copy(hosts = (listOf(host) + mac.hosts).distinct().take(4), port = port))
        }
    }

    /** Drops remembered addresses where another certificate answered (a reassigned DHCP address, another server). */
    fun forgetAddresses(hosts: Set<String>) {
        synchronized(lock) {
            val mac = read() ?: return
            write(mac.copy(hosts = mac.hosts.filterNot { it in hosts }))
        }
    }

    fun clear() = synchronized(lock) { prefs.edit { clear() } }

    private fun read(): PairedMac? {
        val fingerprint = prefs.getString(KEY_FP, null) ?: return null
        return PairedMac(
            name = prefs.getString(KEY_NAME, null) ?: "Mac",
            fingerprint = fingerprint,
            hosts = prefs.getString(KEY_HOSTS, "")!!.split(',').filter { it.isNotEmpty() },
            port = prefs.getInt(KEY_PORT, 47321),
        )
    }

    private fun write(mac: PairedMac) = prefs.edit {
        putString(KEY_NAME, mac.name)
        putString(KEY_FP, mac.fingerprint)
        putString(KEY_HOSTS, mac.hosts.joinToString(","))
        putInt(KEY_PORT, mac.port)
    }

    private companion object {
        const val KEY_NAME = "name"
        const val KEY_FP = "fp"
        const val KEY_HOSTS = "hosts"
        const val KEY_PORT = "port"
    }
}
