package dev.ivo.droiddrop

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Activity-side clip sending: reads the clipboard (the caller must have window focus), sends, toasts. */
object OutgoingClip {
    /**
     * Outlives the calling activity, so a translucent send window can finish at once: a clip write can wait behind
     * a file chunk on a stalled socket for up to the 90 s read timeout, and that window would swallow every touch.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The first item's text and whether a password manager marked the clip sensitive. Null text when there is no
     * clip or no text, or the read was denied.
     */
    fun read(context: Context): Pair<String?, Boolean> {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val clip = clipboard.primaryClip ?: return null to false
        // Not coerceToText: it reads a URI's whole file on this thread, and returns the exception text on failure.
        val text = if (clip.itemCount > 0) clip.getItemAt(0).text?.toString() else null
        val sensitive = clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
        return text to sensitive
    }

    /** Sends in the background through the live session and toasts the result. Null text sends as empty. */
    fun send(context: Context, text: String?, automatic: Boolean, sensitive: Boolean = false) {
        val app = context.applicationContext
        scope.launch {
            val result = Clips.link.send(text.orEmpty(), automatic, sensitive)
            val message = ClipLink.toast(result, PairingStore(app).load()?.name, automatic) ?: return@launch
            launch(Dispatchers.Main) { Toast.makeText(app, message, Toast.LENGTH_SHORT).show() }
        }
    }
}
