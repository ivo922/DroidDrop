package dev.ivo.droiddrop

import android.text.format.Formatter
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId

private val TileSize = 48.dp

/** Thumbnail or kind tile, name and detail line, size. The whole row opens the file. */
@Composable
fun ReceivedFileRow(file: ReceivedFile, nowMillis: Long, onOpen: (ReceivedFile) -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onOpen(file) }.padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileTile(file)
        Column(modifier = Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                ReceivedFileText.detail(file.name, file.addedMillis, nowMillis, ZoneId.systemDefault()),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(Formatter.formatShortFileSize(context, file.size), style = MaterialTheme.typography.bodySmall)
    }
}

/** The kind's emoji (R3), replaced by the MediaStore thumbnail for images and videos once it has loaded. */
@Composable
private fun FileTile(file: ReceivedFile) {
    val context = LocalContext.current
    val kind = FileKind.of(file.mime, file.name)
    val px = with(LocalDensity.current) { TileSize.roundToPx() }
    val thumbnail by produceState<ImageBitmap?>(null, file.uri) {
        if (!kind.hasThumbnail) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(file.uri, Size(px, px), null).asImageBitmap() }.getOrNull()
        }
    }
    Box(
        modifier = Modifier.size(TileSize).clip(RoundedCornerShape(8.dp)),
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
