package dev.ivo.droiddrop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon

object Notifications {
    const val ID = 1
    private const val CHANNEL = "connection"
    private const val REQUEST_SEND_CLIPBOARD = 1

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Connection", NotificationManager.IMPORTANCE_LOW))
    }

    fun build(context: Context, state: ConnectionState): Notification {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("DroidDrop")
            .setContentText(state.describe())
            .setContentIntent(open)
            .setOngoing(true)
        if (state is ConnectionState.Connected) builder.addAction(sendClipboardAction(context))
        return builder.build()
    }

    private fun sendClipboardAction(context: Context): Notification.Action {
        val intent = Intent(context, SendClipboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(context, REQUEST_SEND_CLIPBOARD, intent, PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(null as Icon?, "Send clipboard", pending).build()
    }
}
