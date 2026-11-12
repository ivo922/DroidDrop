package dev.ivo.droiddrop

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import dev.ivo.droiddrop.core.transfer.OutgoingFile
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns shared or picked URIs into queued [OutgoingFile]s. URIs are opened here, in the activity that received them
 * while its grant is valid, never by the service (R9): under START_STICKY a service's grants end when onStartCommand returns.
 */
object OutgoingFiles {
    private const val DEFAULT_MIME = "application/octet-stream"

    /** Opens [uri] while the caller still holds its grant (R9). Throws when it cannot be opened. Blocking. */
    fun open(context: Context, uri: Uri): OutgoingFile {
        val resolver = context.contentResolver
        val name = displayName(context, uri)
        val mime = resolver.getType(uri) ?: DEFAULT_MIME // R7
        val pfd = resolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException("no descriptor for $uri")
        try {
            if (pfd.statSize >= 0) {
                val channel = FileInputStream(pfd.fileDescriptor).channel
                return OutgoingFile(name, mime, channel) { closeQuietly(pfd) }
            }
            // Not a regular file (a pipe or socket): it cannot be re-read after a reconnect, so spool it.
            val dir = File(context.cacheDir, "outgoing").also { it.mkdirs() }
            val copy = File(dir, UUID.randomUUID().toString())
            try {
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                    copy.outputStream().use { output -> input.copyTo(output) }
                }
                val channel = FileChannel.open(copy.toPath(), StandardOpenOption.READ)
                return OutgoingFile(name, mime, channel) { copy.delete() }
            } catch (e: Throwable) {
                copy.delete()
                throw e
            }
        } catch (e: Throwable) {
            closeQuietly(pfd)
            throw e
        }
    }

    /** Gate, open each URI on Dispatchers.IO, enqueue on Transfers.sender, start ConnectionService, toast. */
    suspend fun send(activity: ComponentActivity, uris: List<Uri>) {
        val mac = PairingStore(activity).load()
        val refusal = SendGate.refusal(
            paired = mac != null,
            localNetworkAllowed = LocalNetwork.allowed(activity),
            fileCount = uris.size,
        )
        if (refusal != null) {
            toast(activity, refusal)
            return
        }
        val opened = mutableListOf<OutgoingFile>()
        val failed = mutableListOf<String>()
        var handedOver = false
        try {
            withContext(Dispatchers.IO) {
                for (uri in uris) {
                    try {
                        opened += open(activity, uri)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed += displayName(activity, uri)
                    }
                }
            }
            if (opened.isNotEmpty()) {
                Transfers.sender.enqueue(opened)
                handedOver = true
                try {
                    ConnectionService.start(activity)
                } catch (e: RuntimeException) {
                    // The queue is in memory and waits for the next start (R5).
                    toast(activity, "Could not start DroidDrop. Open the app and try again.")
                }
                toast(activity, "Sending ${opened.size} ${if (opened.size == 1) "file" else "files"} to ${mac?.name ?: "Mac"}")
            }
        } finally {
            // Cancelled mid-open (the activity went away): nothing owns these descriptors yet.
            if (!handedOver) opened.forEach(OutgoingFile::release)
        }
        failed.forEach { toast(activity, "Could not open $it") }
    }

    private fun displayName(context: Context, uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
        }.getOrNull()
        return fromProvider?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "file"
    }

    private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    private fun closeQuietly(pfd: ParcelFileDescriptor) {
        try {
            pfd.close()
        } catch (_: IOException) {
            // Already closed.
        }
    }
}
