package dev.ivo.droiddrop

import android.app.Application
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class Available(val manifest: UpdateManifest, val showBanner: Boolean) : UpdateUiState
    data class Downloading(val manifest: UpdateManifest, val progress: Float) : UpdateUiState
    data class Error(val manifest: UpdateManifest?, val message: String) : UpdateUiState
}

/** Coordinates throttled daily checks, manual checks, and APK download across configuration changes. */
class UpdateModel(application: Application) : AndroidViewModel(application) {
    private val store = UpdateStore(application)
    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    private val _messages = Channel<String>(Channel.BUFFERED)
    private val _installRequests = Channel<File>(Channel.BUFFERED)
    private var busy = false

    val state: StateFlow<UpdateUiState> = _state.asStateFlow()
    val messages = _messages.receiveAsFlow()
    val installRequests = _installRequests.receiveAsFlow()

    val currentVersionName: String
    val currentVersionCode: Long

    init {
        val info = application.packageManager.getPackageInfo(
            application.packageName,
            PackageManager.PackageInfoFlags.of(0),
        )
        currentVersionName = info.versionName ?: "0.0.0"
        currentVersionCode = info.longVersionCode
        viewModelScope.launch(Dispatchers.IO) {
            AppUpdater.cleanStaleDownloads(application.cacheDir)
        }
    }

    fun checkIfDue() {
        if (busy) return
        val now = System.currentTimeMillis()
        if (!UpdatePolicy.shouldAutoCheck(store.lastCheckMillis(), now)) return
        runCheck(manual = false)
    }

    fun checkManually() {
        val current = _state.value
        if (current is UpdateUiState.Available && !current.showBanner) {
            _state.value = current.copy(showBanner = true)
            return
        }
        if (busy) return
        runCheck(manual = true)
    }

    fun dismissBanner(manifest: UpdateManifest) {
        store.dismiss(manifest.versionCode)
        val current = _state.value
        if (current is UpdateUiState.Available && current.manifest.versionCode == manifest.versionCode) {
            _state.value = current.copy(showBanner = false)
        } else if (current is UpdateUiState.Error) {
            _state.value = UpdateUiState.Available(manifest, showBanner = false)
        }
    }

    fun downloadUpdate(manifest: UpdateManifest) {
        if (busy) return
        busy = true
        _state.value = UpdateUiState.Downloading(manifest, progress = 0f)
        viewModelScope.launch {
            try {
                val apkFile = withContext(Dispatchers.IO) {
                    AppUpdater.downloadAndVerify(
                        asset = manifest.android,
                        cacheDir = getApplication<Application>().cacheDir,
                        versionCode = manifest.versionCode,
                    ) { progress ->
                        _state.value = UpdateUiState.Downloading(manifest, progress)
                    }
                }
                _state.value = UpdateUiState.Available(manifest, showBanner = true)
                _installRequests.trySend(apkFile)
            } catch (e: Exception) {
                _state.value = UpdateUiState.Error(manifest, e.message ?: "Could not download update.")
            } finally {
                busy = false
            }
        }
    }

    private fun runCheck(manual: Boolean) {
        busy = true
        val prior = _state.value
        if (manual) _state.value = UpdateUiState.Checking
        viewModelScope.launch {
            try {
                val manifest = withContext(Dispatchers.IO) { AppUpdater.fetchLatest() }
                store.recordCheck(System.currentTimeMillis())
                if (UpdatePolicy.isNewer(manifest.versionCode, currentVersionCode)) {
                    val showBanner = UpdatePolicy.shouldShowBanner(
                        remoteVersionCode = manifest.versionCode,
                        dismissedVersionCode = store.dismissedVersionCode(),
                        manual = manual,
                    )
                    _state.value = UpdateUiState.Available(manifest, showBanner = showBanner)
                } else {
                    _state.value = UpdateUiState.Idle
                    if (manual) _messages.trySend("You're up to date (v$currentVersionName)")
                }
            } catch (e: Exception) {
                _state.value = if (prior is UpdateUiState.Available) prior else UpdateUiState.Idle
                if (manual) _messages.trySend("Could not check for updates: ${e.message ?: "network error"}")
            } finally {
                busy = false
            }
        }
    }
}
