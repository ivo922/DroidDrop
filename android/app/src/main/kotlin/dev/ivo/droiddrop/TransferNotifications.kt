package dev.ivo.droiddrop

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import dev.ivo.droiddrop.core.transfer.Direction
import dev.ivo.droiddrop.core.transfer.TransferOutcome
import dev.ivo.droiddrop.core.transfer.TransferStatus
import java.util.concurrent.atomic.AtomicInteger

/** Progress and completion notifications for file transfers. The connection notification is [Notifications.ID]. */
object TransferNotifications {
    private const val CHANNEL = "transfers"
    private const val INCOMING_ID = 2
    private const val OUTGOING_ID = 3
    private const val RECEIVED_ID = 4 // replaced by the next success (R12)
    private const val SENT_ID = 5
    private const val FIRST_FAILURE_ID = 100
    private const val PROGRESS_MAX = 1000

    private val nextFailureId = AtomicInteger(FIRST_FAILURE_ID)

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "File transfers", NotificationManager.IMPORTANCE_DEFAULT))
    }

    /** Shows [status], or removes the incoming progress notification when it is null. */
    fun showIncoming(context: Context, status: TransferStatus?) = showProgress(context, INCOMING_ID, status)

    /** Shows [status]; else, when files are [pending] with nothing active (for example while offline), the waiting line. */
    fun showOutgoing(context: Context, status: TransferStatus?, pending: Int, macName: String) {
        if (status != null || pending <= 0) return showProgress(context, OUTGOING_ID, status)
        val notification = base(context, android.R.drawable.stat_sys_upload)
            .setContentTitle("DroidDrop")
            .setContentText(TransferText.waiting(pending, macName))
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "Cancel", cancelIntent(context))
            .build()
        manager(context).notify(OUTGOING_ID, notification)
    }

    fun cancelProgress(context: Context) {
        manager(context).cancel(INCOMING_ID)
        manager(context).cancel(OUTGOING_ID)
    }

    fun showOutcome(context: Context, outcome: TransferOutcome, macName: String) {
        val (title, body) = TransferText.outcome(outcome, macName)
        val builder = base(context, android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
        val id = when (outcome) {
            is TransferOutcome.Received -> {
                openIntent(context, Uri.parse(outcome.location))?.let(builder::setContentIntent)
                RECEIVED_ID
            }
            is TransferOutcome.Sent -> SENT_ID
            is TransferOutcome.Failed -> nextFailureId.getAndIncrement()
        }
        manager(context).notify(id, builder.build())
    }

    private fun showProgress(context: Context, id: Int, status: TransferStatus?) {
        if (status == null) return manager(context).cancel(id)
        val icon = if (status.direction == Direction.INCOMING) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_upload
        val builder = base(context, icon)
            .setContentTitle("DroidDrop")
            .setContentText(TransferText.progress(status))
            .setProgress(PROGRESS_MAX, progressDone(status), false)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
        if (id == OUTGOING_ID) builder.addAction(0, "Cancel", cancelIntent(context))
        manager(context).notify(id, builder.build())
    }

    /** Explicit broadcast to [CancelSendingReceiver] (R10). */
    private fun cancelIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, CancelSendingReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

    /** Progress in thousandths: setProgress takes Ints, and bytesDone * 1000 cannot overflow a Long for any real file. */
    private fun progressDone(status: TransferStatus): Int =
        if (status.size > 0) (status.bytesDone * PROGRESS_MAX / status.size).toInt() else 0

    private fun base(context: Context, icon: Int): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(icon)
            .setContentIntent(
                PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
            )

    /** Opens the saved file in a viewer. Null when the location is not a content URI. */
    private fun openIntent(context: Context, uri: Uri): PendingIntent? {
        if (uri.scheme != "content") return null
        return PendingIntent.getActivity(context, uri.hashCode(), FileViewer.intent(context, uri), PendingIntent.FLAG_IMMUTABLE)
    }

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)
}
