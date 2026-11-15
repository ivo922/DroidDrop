package dev.ivo.droiddrop

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.text.format.Formatter
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.time.ZoneId

private val TileSize = 48.dp

/** Process-wide, so thumbnails survive scrolling and rotation. 16 MiB is about 200 tiles at xxhdpi. */
private val thumbnails = ThumbnailCache<Pair<Uri, Int>, ImageBitmap>(16L shl 20) { it.width.toLong() * it.height * 4 }

/**
 * A [px]-square thumbnail, or null when there is none. Cancelled with the calling coroutine (a row scrolled away), so
 * a fast fling past many videos does not leave decoding work queued.
 */
private suspend fun loadThumbnail(context: Context, uri: Uri, px: Int): ImageBitmap? {
    val signal = CancellationSignal()
    val cancelOnLeave = currentCoroutineContext().job.invokeOnCompletion { if (it != null) signal.cancel() }
    return try {
        context.contentResolver.loadThumbnail(uri, Size(px, px), signal).asImageBitmap()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null // no thumbnail for this file (or the load was cancelled): the kind's emoji stays
    } finally {
        cancelOnLeave.dispose()
    }
}

/** Thumbnail or kind tile, name and detail line, size; row [index] of [count] in the grouped list. The whole row opens the file. */
@Composable
fun ReceivedFileRow(file: ReceivedFile, nowMillis: Long, onOpen: (ReceivedFile) -> Unit, index: Int, count: Int) {
    val context = LocalContext.current
    SegmentRow(index = index, count = count, onClick = { onOpen(file) }, minHeight = 72.dp) {
        FileTile(file)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                ReceivedFileText.detail(file.name, file.addedMillis, nowMillis, ZoneId.systemDefault()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            Formatter.formatShortFileSize(context, file.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The kind's emoji (R3), replaced by the MediaStore thumbnail for images and videos once it has loaded. */
@Composable
private fun FileTile(file: ReceivedFile) {
    val context = LocalContext.current
    val kind = FileKind.of(file.mime, file.name)
    val px = with(LocalDensity.current) { TileSize.roundToPx() }
    val key = file.uri to px
    val thumbnail by produceState(thumbnails.get(key), key) {
        if (!kind.hasThumbnail || value != null) return@produceState
        value = withContext(Dispatchers.IO) { loadThumbnail(context, file.uri, px) }?.also { thumbnails.put(key, it) }
    }
    Box(
        modifier = Modifier
            .size(TileSize)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        val image = thumbnail
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(TileSize))
        } else {
            Text(kind.symbol, fontSize = 24.sp)
        }
    }
}
