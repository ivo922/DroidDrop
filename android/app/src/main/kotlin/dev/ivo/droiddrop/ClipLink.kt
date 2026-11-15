package dev.ivo.droiddrop

import dev.ivo.droiddrop.core.clipboard.ClipOutbox
import dev.ivo.droiddrop.core.session.MessageSender
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/**
 * Sends phone clipboard text over the live session, if there is one. Nothing is queued: a send while disconnected
 * is refused, never delivered later. Pure, so it can be unit tested.
 */
class ClipLink(val outbox: ClipOutbox = ClipOutbox()) {
    enum class Result { Sent, NotConnected, Empty, TooLarge, Skipped, Failed }

    private val sender = AtomicReference<MessageSender?>(null)

    fun attach(sender: MessageSender) = this.sender.set(sender)

    fun detach() = sender.set(null)

    /** Blocking socket write; call on Dispatchers.IO. Never throws. */
    fun send(text: String, automatic: Boolean, sensitive: Boolean = false): Result {
        val clip = when (val decision = outbox.decide(text, automatic, sensitive)) {
            is ClipOutbox.Decision.Send -> decision.clip
            ClipOutbox.Decision.Empty -> return Result.Empty
            ClipOutbox.Decision.TooLarge -> return Result.TooLarge
            ClipOutbox.Decision.FromMac, ClipOutbox.Decision.Sensitive -> return Result.Skipped
        }
        val live = sender.get() ?: return Result.NotConnected
        return try {
            live.send(clip)
            Result.Sent // written to the socket; the protocol has no clip acknowledgement
        } catch (e: IOException) {
            Result.Failed
        } catch (e: RuntimeException) {
            Result.Failed
        }
    }

    companion object {
        /** The toast for a send, or null: automatic sends are silent, and so is a skipped one. */
        fun toast(result: Result, macName: String?, automatic: Boolean): String? {
            if (automatic) return null
            val mac = macName ?: "Mac"
            return when (result) {
                Result.Sent -> "Sent to $mac"
                Result.NotConnected ->
                    if (macName == null) "Pair DroidDrop with your Mac first." else "Not connected to $mac."
                Result.Empty -> "No text to send."
                Result.TooLarge -> "Too much text to send (limit 256 KB)."
                Result.Failed -> "Could not send to $mac."
                Result.Skipped -> null
            }
        }
    }
}

/** Process-wide clip link: ConnectionService attaches each session, activities send through it. */
object Clips {
    val link = ClipLink()
}
