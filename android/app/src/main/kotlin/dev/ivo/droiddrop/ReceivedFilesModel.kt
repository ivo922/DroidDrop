package dev.ivo.droiddrop

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Holds the received list across configuration changes, so a rotation neither flashes the empty text nor loses the
 * scroll position (the restored LazyColumn finds its rows already there).
 */
class ReceivedFilesModel(application: Application) : AndroidViewModel(application) {
    /** Rows plus the time they were read, so date labels move on at every refresh. */
    val list = LatestLoad(viewModelScope, System::currentTimeMillis) {
        withContext(Dispatchers.IO) { ReceivedFiles.query(application) }
    }
}
