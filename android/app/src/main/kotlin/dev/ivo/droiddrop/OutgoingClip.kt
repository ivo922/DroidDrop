package dev.ivo.droiddrop

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Activity-side clip sending: reads the clipboard (the caller must have window focus), sends, toasts. */
object OutgoingClip {
    /**
     * The first item's text and whether a password manager marked the clip sensitive. Null text when there is no
     * clip or no text, or the read was denied.
     */
    fun read(context: Context): Pair<String?, Boolean> {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val clip = clipboard.primaryClip ?: return null to false
        val text = if (clip.itemCount > 0) clip.getItemAt(0).coerceToText(context)?.toString() else null
        val sensitive = clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
        return text to sensitive
    }

    /** Sends on Dispatchers.IO through the live session and shows the result. Null text sends as empty. */
    suspend fun send(context: Context, text: String?, automatic: Boolean, sensitive: Boolean = false) {
        val result = withContext(Dispatchers.IO) { Clips.link.send(text.orEmpty(), automatic, sensitive) }
        val message = ClipLink.toast(result, PairingStore(context).load()?.name, automatic) ?: return
        // The application context: the activity usually finishes right after this.
        Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
    }
}
