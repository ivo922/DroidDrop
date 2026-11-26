package dev.ivo.droiddrop

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.ZoneId

/** Quick actions shown when the user presses and holds a received file in any list or gallery view. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceivedFileActionsSheet(
    file: ReceivedFile,
    nowMillis: Long,
    onDismiss: () -> Unit,
    onShare: (ReceivedFile) -> Unit,
    onOpenWith: (ReceivedFile) -> Unit,
    onOpenFolder: () -> Unit,
    onRequestDelete: (ReceivedFile) -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var acted by remember(file.uri) { mutableStateOf(false) }
    fun runOnce(action: () -> Unit) {
        if (acted) return
        acted = true
        onDismiss()
        action()
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FileTile(
                    file,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)),
                    thumbnailSize = 48.dp,
                    emojiSize = 24.sp,
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        file.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        ReceivedFileText.sheetDetail(
                            file.name,
                            file.addedMillis,
                            nowMillis,
                            ZoneId.systemDefault(),
                            Formatter.formatShortFileSize(context, file.size),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                SheetAction(icon = R.drawable.ic_share, label = ReceivedFileText.ACTION_SHARE) {
                    runOnce { onShare(file) }
                }
                SheetAction(icon = R.drawable.ic_open_in_new, label = ReceivedFileText.ACTION_OPEN_WITH) {
                    runOnce { onOpenWith(file) }
                }
                SheetAction(icon = R.drawable.ic_folder, label = ReceivedFileText.ACTION_OPEN_FOLDER) {
                    runOnce(onOpenFolder)
                }
                SheetAction(
                    icon = R.drawable.ic_delete,
                    label = ReceivedFileText.ACTION_DELETE,
                    tint = MaterialTheme.colorScheme.error,
                    iconTint = MaterialTheme.colorScheme.error,
                ) {
                    runOnce { onRequestDelete(file) }
                }
            }
        }
    }
}

@Composable
private fun SheetAction(
    icon: Int,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon, tint = iconTint)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

/** Confirms permanently deleting [file] from Download/DroidDrop/. */
@Composable
fun DeleteFileDialog(file: ReceivedFile, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var confirmed by remember(file.uri) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ReceivedFileText.DELETE_DIALOG_TITLE) },
        text = { Text(ReceivedFileText.deleteConfirmation(file.name)) },
        confirmButton = {
            TextButton(
                onClick = {
                    if (!confirmed) {
                        confirmed = true
                        onConfirm()
                    }
                },
            ) {
                Text(ReceivedFileText.ACTION_DELETE, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(ReceivedFileText.ACTION_CANCEL) }
        },
    )
}
