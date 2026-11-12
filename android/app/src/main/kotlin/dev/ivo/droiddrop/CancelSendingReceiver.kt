package dev.ivo.droiddrop

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** The "Cancel" action on the outgoing notification (R10). */
class CancelSendingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Transfers.sender.cancelAll()
    }
}
