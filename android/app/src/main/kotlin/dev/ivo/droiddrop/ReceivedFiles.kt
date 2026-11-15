package dev.ivo.droiddrop

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log

/** One row of the received files list. [addedMillis] is when MediaStore got the file, i.e. when it was received. */
data class ReceivedFile(val uri: Uri, val name: String, val size: Long, val addedMillis: Long, val mime: String?)

/** Reads Download/DroidDrop/ from MediaStore. The app sees its own files without a storage permission. */
object ReceivedFiles {
    private const val TAG = "ReceivedFiles"

    /** Where DownloadsDestination saves and this lists, spelled as MediaStore stores it (with the trailing slash). */
    const val RELATIVE_PATH = "Download/DroidDrop/"

    /** DATE_ADDED has one-second resolution, so `_id` keeps a multi-file drop in a stable order. */
    const val SORT_ORDER = "${MediaStore.MediaColumns.DATE_ADDED} DESC, ${MediaStore.MediaColumns._ID} DESC"

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
        context.contentResolver.query(collection, columns, selection, arrayOf(RELATIVE_PATH), SORT_ORDER)?.use { cursor ->
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
