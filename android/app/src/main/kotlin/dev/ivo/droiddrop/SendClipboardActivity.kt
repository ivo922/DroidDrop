package dev.ivo.droiddrop

import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * "Send clipboard" from the notification and the Quick Settings tile. It has no layout. Android lets an app read the
 * clipboard only while it has window focus, so the read waits for onWindowFocusChanged(true).
 */
class SendClipboardActivity : ComponentActivity() {
    private var handled = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true
        val (text, sensitive) = OutgoingClip.read(this)
        lifecycleScope.launch {
            OutgoingClip.send(this@SendClipboardActivity, text, automatic = false, sensitive = sensitive)
            finish()
        }
    }
}
