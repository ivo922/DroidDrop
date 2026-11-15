package dev.ivo.droiddrop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MainScreen(
    state: ConnectionState,
    paired: Boolean,
    phoneCode: String,
    onPair: () -> Unit,
    onSendFiles: () -> Unit,
    onUnpair: () -> Unit,
    onAllowBackground: () -> Unit,
    received: List<ReceivedFile>?,
    nowMillis: Long,
    onOpen: (ReceivedFile) -> Unit,
) {
    Scaffold { insets ->
        val direction = LocalLayoutDirection.current
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = insets.calculateStartPadding(direction) + 16.dp,
                top = insets.calculateTopPadding(),
                end = insets.calculateEndPadding(direction) + 16.dp,
                bottom = insets.calculateBottomPadding() + 24.dp,
            ),
        ) {
            item {
                Text(
                    "DroidDrop",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 4.dp, top = 24.dp, bottom = 20.dp),
                )
            }
            item { StatusCard(state, paired, onPair, onSendFiles) }

            item { SectionHeader("This phone") }
            val settings = if (paired) 3 else 2
            item {
                SegmentRow(index = 0, count = settings, minHeight = 64.dp) {
                    RowIcon(R.drawable.ic_phone)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Phone code", style = MaterialTheme.typography.bodyLarge)
                        // Selectable so the automatic clipboard send can be tried on a device.
                        SelectionContainer {
                            Text(
                                phoneCode,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item {
                SegmentRow(index = 1, count = settings, onClick = onAllowBackground) {
                    RowIcon(R.drawable.ic_battery)
                    Text("Allow running in background", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    RowIcon(R.drawable.ic_chevron_right, size = 20.dp)
                }
            }
            if (paired) {
                item {
                    SegmentRow(index = 2, count = settings, onClick = onUnpair) {
                        RowIcon(R.drawable.ic_unlink, tint = MaterialTheme.colorScheme.error)
                        Text("Unpair", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 24.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text("Received", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    if (!received.isNullOrEmpty()) {
                        Text(
                            ReceivedFileText.count(received.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // Null until the first query returns: show nothing rather than a "nothing here" that may be wrong.
            ReceivedFileText.placeholder(loaded = received != null, empty = received.isNullOrEmpty())?.let { text ->
                item { EmptyReceived(text) }
            }
            // One "now" for the whole list (stamped at the last refresh), so rows agree on Today and Yesterday.
            val files = received.orEmpty()
            itemsIndexed(files, key = { _, file -> file.uri }) { index, file ->
                ReceivedFileRow(file, nowMillis, onOpen, index = index, count = files.size)
            }
        }
    }
}

/** What the phone is doing, with the actions that make sense in that state. */
@Composable
private fun StatusCard(state: ConnectionState, paired: Boolean, onPair: () -> Unit, onSendFiles: () -> Unit) {
    val showPair = !paired || state is ConnectionState.Failed
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(state)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(state.describe(), style = MaterialTheme.typography.titleMedium, fontSize = 18.sp, lineHeight = 24.sp)
                    state.hint()?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val wide = Modifier.weight(1f).height(48.dp)
                if (showPair) {
                    Button(onClick = onPair, modifier = wide) { ButtonLabel(R.drawable.ic_qr_scan, if (paired) "Pair again" else "Pair with Mac") }
                }
                if (paired && showPair) {
                    FilledTonalButton(onClick = onSendFiles, modifier = wide) { ButtonLabel(R.drawable.ic_upload, "Send files") }
                } else if (paired) {
                    Button(onClick = onSendFiles, modifier = wide) { ButtonLabel(R.drawable.ic_upload, "Send files") }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(state: ConnectionState) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (state) {
        is ConnectionState.Connected -> colors.primaryContainer to colors.onPrimaryContainer
        is ConnectionState.Failed -> colors.errorContainer to colors.onErrorContainer
        else -> colors.secondaryContainer to colors.onSecondaryContainer
    }
    Box(
        modifier = Modifier.size(48.dp).clip(CircleShape).background(container),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            is ConnectionState.Connected -> Icon(painterResource(R.drawable.ic_laptop), contentDescription = null, tint = content)
            is ConnectionState.Pairing, is ConnectionState.Searching ->
                CircularProgressIndicator(modifier = Modifier.size(22.dp), color = content, strokeWidth = 2.5.dp)
            ConnectionState.Unpaired -> Icon(painterResource(R.drawable.ic_qr_scan), contentDescription = null, tint = content)
            is ConnectionState.Failed -> Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = content)
        }
    }
}

@Composable
private fun ButtonLabel(icon: Int, text: String) {
    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
    Text(text, modifier = Modifier.padding(start = 8.dp))
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun EmptyReceived(text: String) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                RowIcon(R.drawable.ic_inbox)
            }
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun RowIcon(icon: Int, size: Dp = 24.dp, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(size))
}

/**
 * One row of a grouped list: rows sit 2dp apart and only the group's outer corners are rounded. Clickable when
 * [onClick] is set.
 */
@Composable
fun SegmentRow(
    index: Int,
    count: Int,
    onClick: (() -> Unit)? = null,
    minHeight: Dp = 56.dp,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = if (index < count - 1) 2.dp else 0.dp)
            .clip(segmentShape(index, count))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = minHeight)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

private fun segmentShape(index: Int, count: Int): Shape {
    val top = if (index == 0) 20.dp else 0.dp
    val bottom = if (index == count - 1) 20.dp else 0.dp
    return RoundedCornerShape(topStart = top, topEnd = top, bottomEnd = bottom, bottomStart = bottom)
}
