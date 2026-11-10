package dev.ivo.droiddrop

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the connection after boot or an app update. Both broadcasts may start a foreground service from the background. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val paired = PairingStore(context).load() != null
        if (BootPolicy.shouldStart(intent.action, paired, LocalNetwork.allowed(context))) {
            ConnectionService.start(context)
        }
    }
}
