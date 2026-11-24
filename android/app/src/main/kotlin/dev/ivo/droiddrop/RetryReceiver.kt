package dev.ivo.droiddrop

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** The Retry action, tap and swipe of a sender summary. Everything is an explicit broadcast to this receiver. */
class RetryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val batch = intent.getStringExtra(EXTRA_BATCH) ?: return
        when (intent.action) {
            RETRY -> retry(context, batch)
            DISMISS -> Transfers.retry.clear(batch)
        }
    }

    private fun retry(context: Context, batch: String) {
        val files = Transfers.retry.take(batch)
        TransferNotifications.cancelSentSummary(context)
        if (files.isEmpty()) {
            // The process died since the summary was posted (the notification outlived the open files), or a newer
            // summary replaced this one and its files.
            Toast.makeText(context, "Share the files again.", Toast.LENGTH_LONG).show()
            return
        }
        Transfers.sender.enqueue(files)
        try {
            ConnectionService.start(context)
        } catch (e: RuntimeException) {
            // The queue is in memory and waits for the next start, as in OutgoingFiles.
            Toast.makeText(context, "Could not start DroidDrop. Open the app and try again.", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val RETRY = "dev.ivo.droiddrop.RETRY"
        const val DISMISS = "dev.ivo.droiddrop.DISMISS"
        const val EXTRA_BATCH = "batch"
    }
}
