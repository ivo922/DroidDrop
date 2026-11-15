package dev.ivo.droiddrop

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Opens a received file in a viewer. Shared by the "Received …" notification and the received list (R7). */
object FileViewer {
    /** ACTION_VIEW for [uri] with its MediaStore type and a read grant for the viewer. */
    fun intent(context: Context, uri: Uri): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, context.contentResolver.getType(uri))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
