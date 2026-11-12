package dev.ivo.droiddrop

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Share-sheet target ("Send to Mac"). It has no layout: it opens the shared URIs while its grant is valid (R9),
 * queues them, and finishes. A text-only share has no URIs and gets "Nothing to send." (text arrives in milestone 5).
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = sharedUris(intent)
        lifecycleScope.launch {
            OutgoingFiles.send(this@ShareActivity, uris)
            finish()
        }
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
