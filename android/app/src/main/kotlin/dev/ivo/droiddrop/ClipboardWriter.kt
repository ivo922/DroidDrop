package dev.ivo.droiddrop

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/** Applies text to the system clipboard. Never throws; call on the main thread. */
class ClipboardWriter(private val setClip: (String) -> Unit) {
    /** False when the system clipboard rejected the text (for example a binder transaction that is too large). */
    fun write(text: String): Boolean = try {
        setClip(text)
        true
    } catch (e: RuntimeException) {
        false
    }

    companion object {
        fun forContext(context: Context): ClipboardWriter {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            return ClipboardWriter { text -> clipboard.setPrimaryClip(ClipData.newPlainText("DroidDrop", text)) }
        }
    }
}
