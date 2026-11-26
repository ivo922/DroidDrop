package dev.ivo.droiddrop

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds the received list across configuration changes, so a rotation neither flashes the empty text nor loses the
 * scroll position (the restored LazyColumn finds its rows already there). Also owns file deletion so a rotation
 * cannot cancel the post-delete refresh or drop its error toast.
 */
class ReceivedFilesModel(application: Application) : AndroidViewModel(application) {
    private val deletingUris = mutableSetOf<Uri>()
    private val _messages = Channel<String>(Channel.BUFFERED)

    /** One-shot toast messages from background file actions (for example delete failures). */
    val messages = _messages.receiveAsFlow()

    /** Rows plus the time they were read, so date labels move on at every refresh. */
    val list: LatestLoad<List<ReceivedFile>?> = LatestLoad(viewModelScope, System::currentTimeMillis, ::loadFiles)

    private suspend fun loadFiles(): List<ReceivedFile>? =
        withContext(Dispatchers.IO) { ReceivedFiles.query(getApplication()) } ?: list.snapshot.value?.value

    /** Deletes [file] off the main thread, deduplicating rapid taps for the same URI. */
    fun delete(file: ReceivedFile) {
        if (!deletingUris.add(file.uri)) return
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                val result = withContext(Dispatchers.IO) {
                    ReceivedFiles.delete(context, file.uri).also { outcome ->
                        if (ReceivedFileActions.shouldCancelNotificationAfterDelete(outcome)) {
                            TransferNotifications.cancelReceivedIfMatching(context, file.uri)
                        }
                    }
                }
                ReceivedFileText.deleteMessage(file.name, result)?.let { _messages.trySend(it) }
                if (ReceivedFileActions.shouldRefreshAfterDelete(result)) list.refresh()
            } finally {
                deletingUris.remove(file.uri)
            }
        }
    }
}
