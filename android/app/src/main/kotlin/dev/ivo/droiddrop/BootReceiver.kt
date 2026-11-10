package dev.ivo.droiddrop

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Restarts the connection after boot or an app update. Both broadcasts may start a foreground service from the background. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val paired = PairingStore(context).load() != null
        if (!BootPolicy.shouldStart(intent.action, paired, LocalNetwork.allowed(context))) return
        // ForegroundServiceStartNotAllowedException is an IllegalStateException; a crash here would repeat on every boot.
        try {
            ConnectionService.start(context)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "could not start the connection service", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "could not start the connection service", e)
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
