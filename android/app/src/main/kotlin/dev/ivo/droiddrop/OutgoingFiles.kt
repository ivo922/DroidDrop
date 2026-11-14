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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Turns shared or picked URIs into queued [OutgoingFile]s. URIs are opened here while the activity that received them
 * is alive (its grant is valid), never by the service (R9): under START_STICKY a service's grants end when
 * onStartCommand returns.
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

    /**
     * Outlives the activity: a slow open (a cloud provider downloading, a pipe being spooled) must not be cancelled
     * when the user leaves the translucent share window. If the URI grant ends first, the open fails with a toast.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Starts [sendNow] in the process scope; [onDone] runs on the main thread when it ends (the caller finishes). */
    fun send(activity: ComponentActivity, uris: List<Uri>, onDone: () -> Unit = {}) {
        val context = activity.applicationContext
        scope.launch {
            try {
                sendNow(context, uris)
            } finally {
                onDone()
            }
        }
    }

    /** Gate, open each URI on Dispatchers.IO, enqueue on Transfers.sender, start ConnectionService, toast. */
    private suspend fun sendNow(context: Context, uris: List<Uri>) {
        val mac = PairingStore(context).load()
        val refusal = SendGate.refusal(
            paired = mac != null,
            localNetworkAllowed = LocalNetwork.allowed(context),
            fileCount = uris.size,
        )
        if (refusal != null) {
            toast(context, refusal)
            return
        }
        val opened = mutableListOf<OutgoingFile>()
        val failed = mutableListOf<String>()
        var handedOver = false
        try {
            withContext(Dispatchers.IO) {
                for (uri in uris) {
                    try {
                        opened += open(context, uri)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed += displayName(context, uri)
                    }
                }
            }
            if (opened.isNotEmpty()) {
                Transfers.sender.enqueue(opened)
                handedOver = true
                try {
                    ConnectionService.start(context)
                } catch (e: RuntimeException) {
                    // The queue is in memory and waits for the next start (R5).
                    toast(context, "Could not start DroidDrop. Open the app and try again.")
                }
                toast(context, "Sending ${opened.size} ${if (opened.size == 1) "file" else "files"} to ${mac?.name ?: "Mac"}")
            }
        } finally {
            // Failed mid-open: nothing owns these descriptors yet.
            if (!handedOver) opened.forEach(OutgoingFile::release)
        }
        failed.forEach { toast(context, "Could not open $it") }
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
