package dev.ivo.droiddrop

import android.app.DownloadManager
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract

/** Intents for opening, sharing and locating received files. Shared by notifications and the received lists (R7). */
object FileViewer {
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val GOOGLE_DOCUMENTS_UI = "com.google.android.documentsui"
    private const val AOSP_DOCUMENTS_UI = "com.android.documentsui"

    /** ACTION_VIEW for [uri] with its MediaStore type and a read grant for the viewer. */
    fun intent(context: Context, uri: Uri): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, context.contentResolver.getType(uri))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    /** True when at least one installed activity can handle [intent]. */
    fun hasHandler(context: Context, intent: Intent): Boolean =
        context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()

    /** App chooser wrapping [viewIntent], bypassing any default viewer. */
    fun openWithIntent(viewIntent: Intent): Intent =
        Intent.createChooser(viewIntent, ReceivedFileText.OPEN_WITH_TITLE)

    /** Android Sharesheet for [file] with a read grant, filename title, preview ClipData, and DroidDrop excluded. */
    fun shareIntent(context: Context, file: ReceivedFile): Intent {
        val mime = context.contentResolver.getType(file.uri) ?: file.mime ?: "*/*"
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, file.uri)
            .putExtra(Intent.EXTRA_TITLE, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newUri(context.contentResolver, file.name, file.uri) }
        return Intent.createChooser(send, null)
            .putExtra(Intent.EXTRA_TITLE, file.name)
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, ShareActivity::class.java)))
    }

    /** Intent for [target] when opening Download/DroidDrop/ or falling back to the system Downloads screen. */
    fun folderIntent(target: ReceivedFileActions.FolderTarget): Intent = when (target) {
        ReceivedFileActions.FolderTarget.GOOGLE_DOCUMENTS_UI -> directoryIntent(GOOGLE_DOCUMENTS_UI)
        ReceivedFileActions.FolderTarget.AOSP_DOCUMENTS_UI -> directoryIntent(AOSP_DOCUMENTS_UI)
        ReceivedFileActions.FolderTarget.DOWNLOADS -> Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
    }

    private fun directoryIntent(packageName: String): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(
                DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, ReceivedFiles.FOLDER_DOCUMENT_ID),
                DocumentsContract.Document.MIME_TYPE_DIR,
            )
            .setPackage(packageName)
}
