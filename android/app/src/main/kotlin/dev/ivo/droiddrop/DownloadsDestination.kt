package dev.ivo.droiddrop

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import dev.ivo.droiddrop.core.transfer.Destination
import java.io.File
import java.io.IOException

/**
 * Saves into `Download/DroidDrop/` through MediaStore, so no storage permission is needed.
 * MediaStore renames a collision to `name (1).ext` itself (R8).
 */
class DownloadsDestination(private val context: Context) : Destination {
    override fun save(file: File, name: String): String {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/DroidDrop")
            put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(name))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore refused $name")
        try {
            val output = resolver.openOutputStream(uri) ?: throw IOException("cannot open $uri")
            output.use { out -> file.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        file.delete()
        return uri.toString()
    }

    /**
     * The type of the name's own extension (R7), so MediaStore never appends one. The extension starts at the last
     * non-leading `.`, as in FileName, and is matched without the dot.
     */
    private fun mimeFor(name: String): String {
        val dot = name.lastIndexOf('.')
        val extension = if (dot <= 0) "" else name.substring(dot + 1)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase()) ?: "application/octet-stream"
    }
}
