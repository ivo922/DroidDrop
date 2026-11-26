package dev.ivo.droiddrop

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.ZoneId

private typealias Day = ReceivedDays.Day<ReceivedFile>

/** Square size gallery thumbnails are loaded at: about a third of a phone's width. */
private val GalleryThumbnail = 120.dp

/** Every received file, grouped by day and shown as a [view] the user picks with "View as". */
@Composable
fun ReceivedFilesScreen(
    received: List<ReceivedFile>?,
    nowMillis: Long,
    view: ReceivedView,
    onViewChange: (ReceivedView) -> Unit,
    onBack: () -> Unit,
    onOpen: (ReceivedFile) -> Unit,
    onActions: (ReceivedFile) -> Unit,
) {
    val context = LocalContext.current
    val files = received.orEmpty()
    // One "now" for the whole list (stamped at the last refresh), so days agree on Today and Yesterday.
    val days = remember(files, nowMillis) { ReceivedDays.group(files, nowMillis, ZoneId.systemDefault()) { it.addedMillis } }
    Scaffold { insets ->
        val direction = LocalLayoutDirection.current
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = insets.calculateStartPadding(direction),
                top = insets.calculateTopPadding(),
                end = insets.calculateEndPadding(direction),
                bottom = insets.calculateBottomPadding() + 32.dp,
            ),
        ) {
            item {
                IconButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp, top = 8.dp)) {
                    Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                }
            }
            item {
                Column(
                    modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Received files", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Medium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (files.isNotEmpty()) {
                            Text(
                                "${ReceivedFileText.count(files.size)} · ${Formatter.formatShortFileSize(context, files.sumOf { it.size })}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        ViewAsMenu(view, onViewChange)
                    }
                }
            }
            // A file deleted while this screen is open can empty it.
            ReceivedFileText.placeholder(loaded = received != null, empty = files.isEmpty())?.let { text ->
                item { Box(Modifier.padding(horizontal = 16.dp)) { EmptyReceived(text) } }
            }
            when (view) {
                ReceivedView.TIMELINE -> timeline(days, onOpen, onActions)
                ReceivedView.GALLERY -> gallery(days, onOpen, onActions)
            }
        }
    }
}

@Composable
private fun ViewAsMenu(view: ReceivedView, onViewChange: (ReceivedView) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { open = true },
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(start = 14.dp, end = 10.dp),
            modifier = Modifier.height(36.dp),
        ) {
            Text("View as ", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Normal)
            Text(view.label, color = MaterialTheme.colorScheme.onSurface)
            Icon(
                painterResource(R.drawable.ic_chevron_down),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 4.dp).size(18.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ReceivedView.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    leadingIcon = { RowIcon(option.icon, size = 20.dp) },
                    trailingIcon = if (option == view) {
                        { RowIcon(R.drawable.ic_check, size = 20.dp, tint = MaterialTheme.colorScheme.primary) }
                    } else {
                        null
                    },
                    onClick = {
                        open = false
                        onViewChange(option)
                    },
                )
            }
        }
    }
}

private val ReceivedView.icon: Int
    get() = when (this) {
        ReceivedView.TIMELINE -> R.drawable.ic_timeline
        ReceivedView.GALLERY -> R.drawable.ic_gallery
    }

/** A date rail beside each day's plain rows, with the day's file count and size. */
private fun LazyListScope.timeline(days: List<Day>, onOpen: (ReceivedFile) -> Unit, onActions: (ReceivedFile) -> Unit) {
    items(days, key = { "timeline:${it.items.first().uri}" }) { day -> TimelineDay(day, onOpen, onActions) }
}

@Composable
private fun TimelineDay(day: Day, onOpen: (ReceivedFile) -> Unit, onActions: (ReceivedFile) -> Unit) {
    val context = LocalContext.current
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DateRail(day)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    ReceivedDays.summary(day, Formatter.formatShortFileSize(context, day.items.sumOf { it.size })),
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 6.dp),
                )
                day.items.forEach { file -> TimelineRow(file, onOpen, onActions) }
            }
        }
    }
}

@Composable
private fun DateRail(day: Day) {
    Column(
        modifier = Modifier.width(56.dp).padding(top = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(day.weekday, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val today = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primary)
        Box(
            modifier = Modifier.size(40.dp).then(if (day.isToday) today else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                day.dayOfMonth,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
                color = if (day.isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            day.month,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TimelineRow(file: ReceivedFile, onOpen: (ReceivedFile) -> Unit, onActions: (ReceivedFile) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = { onOpen(file) },
                onLongClick = { onActions(file) },
                onLongClickLabel = ReceivedFileText.ACTIONS_LABEL,
            )
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileTile(
            file,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)),
            thumbnailSize = 40.dp,
            emojiSize = 20.sp,
            background = MaterialTheme.colorScheme.surfaceContainer,
        )
        FileText(file, modifier = Modifier.weight(1f))
        FileSize(file)
    }
}

/** Per day: photos and videos as a three-column grid, then the other files as grouped rows. */
private fun LazyListScope.gallery(days: List<Day>, onOpen: (ReceivedFile) -> Unit, onActions: (ReceivedFile) -> Unit) {
    days.forEach { day ->
        val (media, others) = day.items.partition { FileKind.of(it.mime, it.name).hasThumbnail }
        val rows = media.chunked(3)
        stickyHeader(key = "gallery:${day.items.first().uri}") { GalleryHeading(day) }
        itemsIndexed(rows, key = { _, row -> row.first().uri }) { index, row ->
            GalleryRow(row, index, rows.size, onOpen, onActions)
        }
        if (rows.isNotEmpty() && others.isNotEmpty()) item { Spacer(Modifier.height(8.dp)) }
        itemsIndexed(others, key = { _, file -> file.uri }) { index, file ->
            Box(Modifier.padding(horizontal = 16.dp)) {
                SegmentRow(
                    index = index,
                    count = others.size,
                    onClick = { onOpen(file) },
                    onLongClick = { onActions(file) },
                    onLongClickLabel = ReceivedFileText.ACTIONS_LABEL,
                    minHeight = 64.dp,
                ) {
                    FileTile(file, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)), thumbnailSize = 40.dp, emojiSize = 20.sp)
                    FileText(file, modifier = Modifier.weight(1f))
                    FileSize(file)
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun GalleryHeading(day: Day) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(day.heading, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(
            ReceivedFileText.count(day.items.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Row [index] of [count] in one day's grid; a short last row keeps its cells a third wide. */
@Composable
private fun GalleryRow(
    row: List<ReceivedFile>,
    index: Int,
    count: Int,
    onOpen: (ReceivedFile) -> Unit,
    onActions: (ReceivedFile) -> Unit,
) {
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .padding(bottom = if (index < count - 1) 3.dp else 0.dp)
            .clip(segmentShape(index, count)),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        row.forEach { file ->
            // Overlay the clickable surface on top of the opaque thumbnail so press/long-press ripples stay visible.
            Box(modifier = Modifier.weight(1f).aspectRatio(1f)) {
                FileTile(
                    file,
                    modifier = Modifier.fillMaxSize(),
                    thumbnailSize = GalleryThumbnail,
                    emojiSize = 32.sp,
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .combinedClickable(
                            onClick = { onOpen(file) },
                            onLongClick = { onActions(file) },
                            onLongClickLabel = ReceivedFileText.ACTIONS_LABEL,
                        )
                        .semantics { contentDescription = file.name },
                )
            }
        }
        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
    }
}

/** Name over "JPG · 14:02": the day is in the heading. */
@Composable
private fun FileText(file: ReceivedFile, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(file.name, style = MaterialTheme.typography.bodyLarge, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            ReceivedFileText.timeDetail(file.name, file.addedMillis, ZoneId.systemDefault()),
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FileSize(file: ReceivedFile) {
    Text(
        Formatter.formatShortFileSize(LocalContext.current, file.size),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
