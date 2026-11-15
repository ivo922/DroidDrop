package dev.ivo.droiddrop

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * Share-sheet target ("Send to Mac"). It has no layout: it opens the shared URIs while its grant is valid (R9),
 * queues them, and finishes. A text share (SendGate.sharesText) sends EXTRA_TEXT as a clip; EXTRA_SUBJECT is not sent.
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val hasStream = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) != null
        if (SendGate.sharesText(intent.action == Intent.ACTION_SEND, hasStream, text)) {
            OutgoingClip.send(this, text, automatic = false)
            finish()
            return
        }
        // The activity stays until the files are open: the share's URI grant lasts as long as it does (R9).
        // The manifest's configChanges keep a rotation from recreating it, which would queue the files twice.
        OutgoingFiles.send(this, sharedUris(intent), onDone = ::finish)
    }

    private fun sharedUris(intent: Intent): List<Uri> {
        val found = LinkedHashSet<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND ->
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(found::add)
            Intent.ACTION_SEND_MULTIPLE ->
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(found::addAll)
        }
        val clip = intent.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(found::add)
        }
        return found.toList()
    }
}
