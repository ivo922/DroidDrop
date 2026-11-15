package dev.ivo.droiddrop

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log

/** One row of the received files list. [addedMillis] is when MediaStore got the file, i.e. when it was received. */
data class ReceivedFile(val uri: Uri, val name: String, val size: Long, val addedMillis: Long, val mime: String?)

/** Reads Download/DroidDrop/ from MediaStore. The app sees its own files without a storage permission. */
object ReceivedFiles {
    private const val TAG = "ReceivedFiles"

    /** MediaStore stores RELATIVE_PATH with a trailing slash. */
    val relativePath: String = "${Environment.DIRECTORY_DOWNLOADS}/DroidDrop/"

    /** The app's own non-pending rows there, newest first (R4). Empty on any failure. Blocking. */
    fun query(context: Context): List<ReceivedFile> = try {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val columns = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0"
        val order = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        context.contentResolver.query(collection, columns, selection, arrayOf(relativePath), order)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ReceivedFile(
                            uri = ContentUris.withAppendedId(collection, cursor.getLong(0)),
                            name = cursor.getString(1) ?: "file",
                            size = cursor.getLong(2),
                            addedMillis = cursor.getLong(3) * 1000, // DATE_ADDED is in seconds
                            mime = cursor.getString(4),
                        ),
                    )
                }
            }
        } ?: emptyList()
    } catch (e: Exception) {
        Log.w(TAG, "could not list received files", e)
        emptyList()
    }
}
