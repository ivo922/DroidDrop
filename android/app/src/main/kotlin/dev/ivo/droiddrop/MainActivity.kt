package dev.ivo.droiddrop

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import dev.ivo.droiddrop.core.pairing.Pairing
import dev.ivo.droiddrop.core.pairing.PairUri
import dev.ivo.droiddrop.core.transfer.TransferOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private val scope = MainScope()
    /** The received list (View all) is open. Activity-level so a summary tap can open it (R9). */
    private var showAll by mutableStateOf(false)
    private var scanAfterLocalNetworkGrant = false
    private var permissionRequestOpen = false
    private var pendingInstallApk: File? = null
    private lateinit var store: PairingStore
    private val receivedFiles: ReceivedFilesModel by viewModels()
    private val updateModel: UpdateModel by viewModels()
    private val clipboard by lazy { getSystemService(ClipboardManager::class.java) }
    private val receivedViewStore by lazy { ReceivedViewStore(this) }
    private val clipHoldSetting by lazy { ClipHoldSetting(this) }

    // Only a focused app may read the clipboard, so automatic sends run only while this screen is in front.
    // A clip the service just applied from the Mac also lands here; ClipOutbox skips it as FromMac.
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        val (text, sensitive) = OutgoingClip.read(this)
        OutgoingClip.send(this, text, automatic = true, sensitive = sensitive)
    }

    private val runtimePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        permissionRequestOpen = false
        if (LocalNetwork.allowed(this)) {
            resumeSavedConnection()
            if (scanAfterLocalNetworkGrant) {
                scanAfterLocalNetworkGrant = false
                scanPairingCode()
            }
        } else if (scanAfterLocalNetworkGrant) {
            scanAfterLocalNetworkGrant = false
            Toast.makeText(this, "Local network access is required to reach your Mac.", Toast.LENGTH_LONG).show()
        }
    }

    // The picked URIs are granted to this activity, so they are opened here and not by the service (R9).
    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) OutgoingFiles.send(this, uris)
    }

    private val unknownSourcesSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val apk = pendingInstallApk ?: return@registerForActivityResult
        if (packageManager.canRequestPackageInstalls() && apk.exists()) {
            pendingInstallApk = null
            startApkInstaller(apk)
        } else {
            pendingInstallApk = null
            Toast.makeText(this, "Allow installing unknown apps for DroidDrop to finish updating.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        showAll = savedInstanceState?.getBoolean(STATE_SHOW_ALL) == true
        if (savedInstanceState == null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) {
            openReceivedIfAsked(intent)
        }
        store = PairingStore(this)
        requestMissingPermissions()

        setContent {
            DroidDropTheme {
                val state by ConnectionStatus.state.collectAsStateWithLifecycle()
                val generation by AndroidIdentity.generation.collectAsStateWithLifecycle()
                var phoneCode by remember { mutableStateOf("…") }
                LaunchedEffect(generation) {
                    AndroidIdentity.awaitPendingDelete()
                    // A keystore error must not crash the screen; the service reports it as Failed.
                    val code = withContext(Dispatchers.IO) {
                        runCatching { Pairing.displayCode(AndroidIdentity.loadOrCreate().fingerprint) }.getOrDefault("unavailable")
                    }
                    ensureActive()
                    phoneCode = code
                }
                val paired = remember(state) { store.load() != null }
                val updateState by updateModel.state.collectAsStateWithLifecycle()
                val received by receivedFiles.list.snapshot.collectAsStateWithLifecycle()
                var receivedView by remember { mutableStateOf(receivedViewStore.load()) }
                var holdClips by remember { mutableStateOf(clipHoldSetting.load()) }
                var actionFileUri by rememberSaveable { mutableStateOf<String?>(null) }
                var pendingDeleteUri by rememberSaveable { mutableStateOf<String?>(null) }
                val files = received?.value
                val nowMillis = received?.atMillis ?: 0L
                val loadedUris = remember(files) { files?.mapTo(mutableSetOf()) { it.uri.toString() } }
                val actionFile = remember(files, actionFileUri) { files?.firstOrNull { it.uri.toString() == actionFileUri } }
                val pendingDelete = remember(files, pendingDeleteUri) { files?.firstOrNull { it.uri.toString() == pendingDeleteUri } }
                LaunchedEffect(loadedUris, actionFileUri, pendingDeleteUri) {
                    actionFileUri = ReceivedFileActions.reconcileSelection(actionFileUri, loadedUris)
                    pendingDeleteUri = ReceivedFileActions.reconcileSelection(pendingDeleteUri, loadedUris)
                }
                // Keeps the main screen's scroll position while View all is open.
                val screens = rememberSaveableStateHolder()
                BackHandler(enabled = showAll) { showAll = false }
                if (showAll) {
                    ReceivedFilesScreen(
                        received = files,
                        nowMillis = nowMillis,
                        view = receivedView,
                        onViewChange = {
                            receivedView = it
                            receivedViewStore.save(it)
                        },
                        onBack = { showAll = false },
                        onOpen = ::openReceived,
                        onActions = { actionFileUri = it.uri.toString() },
                    )
                } else {
                    screens.SaveableStateProvider("main") {
                        MainScreen(
                            state = state,
                            paired = paired,
                            phoneCode = phoneCode,
                            currentVersion = updateModel.currentVersionName,
                            updateState = updateState,
                            onCheckUpdates = updateModel::checkManually,
                            onDownloadUpdate = updateModel::downloadUpdate,
                            onDismissUpdate = updateModel::dismissBanner,
                            onPair = ::scanPairingCode,
                            onSendFiles = { filePicker.launch(arrayOf("*/*")) },
                            onSendClipboard = ::sendClipboard,
                            onUnpair = ::unpair,
                            onAllowBackground = ::requestBatteryExemption,
                            holdClips = holdClips,
                            onHoldClipsChange = {
                                holdClips = it
                                clipHoldSetting.save(it)
                            },
                            onViewAll = { showAll = true },
                            received = files,
                            nowMillis = nowMillis,
                            onOpen = ::openReceived,
                            onActions = { actionFileUri = it.uri.toString() },
                        )
                    }
                }
                actionFile?.let { file ->
                    ReceivedFileActionsSheet(
                        file = file,
                        nowMillis = nowMillis,
                        onDismiss = { actionFileUri = null },
                        onShare = ::shareReceived,
                        onOpenWith = ::openReceivedWith,
                        onOpenFolder = ::openReceivedFolder,
                        onRequestDelete = { pendingDeleteUri = it.uri.toString() },
                    )
                }
                pendingDelete?.let { file ->
                    DeleteFileDialog(
                        file = file,
                        onConfirm = {
                            if (pendingDeleteUri != null) {
                                pendingDeleteUri = null
                                receivedFiles.delete(file)
                            }
                        },
                        onDismiss = { pendingDeleteUri = null },
                    )
                }
            }
        }
        // The list follows Download/DroidDrop/: refreshed on resume and whenever a file arrives.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Transfers.received.collect { if (it is TransferOutcome.Received) refreshReceived() } }
                launch {
                    receivedFiles.messages.collect { message ->
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                    }
                }
                launch {
                    updateModel.messages.collect { message ->
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                    }
                }
                launch {
                    updateModel.installRequests.collect { apk ->
                        installUpdateApk(apk)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshReceived()
        updateModel.checkIfDue()
        clipboard.addPrimaryClipChangedListener(clipListener)
        if (LocalNetwork.allowed(this)) {
            resumeSavedConnection()
        } else {
            ConnectionService.stop(this)
            when (ConnectionStatus.state.value) {
                is ConnectionState.Connected, is ConnectionState.Searching, is ConnectionState.Pairing ->
                    ConnectionStatus.state.value = ConnectionState.Failed(
                        "Local network access is required to reach your Mac.",
                    )
                else -> Unit
            }
        }
    }

    override fun onPause() {
        clipboard.removePrimaryClipChangedListener(clipListener)
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun requestMissingPermissions() {
        val missing = buildList {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (!LocalNetwork.allowed(this@MainActivity)) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
        if (missing.isEmpty() || permissionRequestOpen) return
        permissionRequestOpen = true
        runtimePermissions.launch(missing.toTypedArray())
    }

    private fun resumeSavedConnection() {
        if (store.load() != null) ConnectionService.start(this)
    }

    private fun scanPairingCode() {
        if (!LocalNetwork.allowed(this)) {
            scanAfterLocalNetworkGrant = true
            requestMissingPermissions()
            return
        }
        scope.launch {
            AndroidIdentity.awaitPendingDelete()
            val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
            GmsBarcodeScanning.getClient(this@MainActivity, options).startScan()
                .addOnSuccessListener { barcode ->
                    val raw = barcode.rawValue
                    if (raw != null && PairUri.parse(raw) != null) {
                        ConnectionService.pair(this@MainActivity, raw)
                    } else {
                        Toast.makeText(this@MainActivity, "Not a DroidDrop pairing code", Toast.LENGTH_LONG).show()
                    }
                }
                .addOnFailureListener {
                    Toast.makeText(this@MainActivity, "Scanner unavailable: ${it.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    // This screen has window focus while its button is tapped, so it reads the clipboard itself (no SendClipboardActivity).
    private fun sendClipboard() {
        val (text, sensitive) = OutgoingClip.read(this)
        OutgoingClip.send(this, text, automatic = false, sensitive = sensitive)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SHOW_ALL, showAll)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openReceivedIfAsked(intent)
    }

    private fun openReceivedIfAsked(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_SHOW_RECEIVED, false) == true) {
            showAll = true
            intent.removeExtra(EXTRA_SHOW_RECEIVED) // a recreation must not reopen it
        }
    }

    private fun unpair() {
        ConnectionService.stop(this)
        // The next pairing, maybe with another Mac, must not get this queue, these retries or that half batch (R10).
        Transfers.sender.cancelAll(silent = true)
        Transfers.retry.clear()
        TransferNotifications.cancelSentSummary(this)
        Transfers.incomingBatches.clear()
        store.clear()
        ConnectionStatus.state.value = ConnectionState.Unpaired
        AndroidIdentity.enqueueDelete()
    }

    private fun refreshReceived() = receivedFiles.list.refresh()

    private fun openReceived(file: ReceivedFile) {
        scope.launch {
            val intent = prepareFileIntent(file) { FileViewer.intent(this@MainActivity, file.uri) } ?: return@launch
            launchFileIntent(file, intent)
        }
    }

    private fun openReceivedWith(file: ReceivedFile) =
        launchExistingFileIntent(file) {
            val viewIntent = FileViewer.intent(this@MainActivity, file.uri)
            if (!FileViewer.hasHandler(this@MainActivity, viewIntent)) throw ActivityNotFoundException()
            FileViewer.openWithIntent(viewIntent)
        }

    private fun shareReceived(file: ReceivedFile) =
        launchExistingFileIntent(file) { FileViewer.shareIntent(this@MainActivity, file) }

    /** Verifies the MediaStore row still exists before opening a chooser, and builds the intent off the main thread. */
    private fun launchExistingFileIntent(file: ReceivedFile, buildIntent: () -> Intent) {
        scope.launch {
            val intent = prepareFileIntent(file) {
                val existence = ReceivedFiles.checkExists(this@MainActivity, file.uri)
                if (ReceivedFileActions.onExistenceCheck(existence) == ReceivedFileActions.ExistenceDecision.FILE_GONE) {
                    throw IllegalArgumentException("File gone: ${file.uri}")
                }
                buildIntent()
            } ?: return@launch
            launchFileIntent(file, intent)
        }
    }

    private suspend fun prepareFileIntent(file: ReceivedFile, buildIntent: () -> Intent): Intent? = try {
        withContext(Dispatchers.IO) { buildIntent() }
    } catch (e: ActivityNotFoundException) {
        reportOpenFailure(file, fileGone = false)
        null
    } catch (e: SecurityException) {
        reportOpenFailure(file, fileGone = true)
        null
    } catch (e: IllegalArgumentException) {
        reportOpenFailure(file, fileGone = true)
        null
    }

    private fun launchFileIntent(file: ReceivedFile, intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            reportOpenFailure(file, fileGone = false)
        } catch (e: SecurityException) {
            // MediaStore refuses the read grant for a row deleted since the list loaded.
            reportOpenFailure(file, fileGone = true)
        } catch (e: IllegalArgumentException) {
            reportOpenFailure(file, fileGone = true)
        }
    }

    private fun reportOpenFailure(file: ReceivedFile, fileGone: Boolean) {
        Toast.makeText(this, ReceivedFileText.openFailure(file.name, fileGone), Toast.LENGTH_LONG).show()
        if (fileGone) refreshReceived()
    }

    private fun openReceivedFolder() {
        val opened = ReceivedFileActions.openFolder { target ->
            runCatching { startActivity(FileViewer.folderIntent(target)) }.isSuccess
        }
        if (!opened) {
            Toast.makeText(this, ReceivedFileText.folderFailure(), Toast.LENGTH_LONG).show()
        }
    }

    private fun requestBatteryExemption() {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName".toUri()))
    }

    private fun installUpdateApk(apk: File) {
        if (!packageManager.canRequestPackageInstalls()) {
            pendingInstallApk = apk
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:$packageName".toUri())
            unknownSourcesSettings.launch(intent)
            return
        }
        startApkInstaller(apk)
    }

    private fun startApkInstaller(apk: File) {
        try {
            startActivity(AppUpdater.installIntent(this, apk))
        } catch (e: Exception) {
            Toast.makeText(this, "Could not start package installer: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        /** Set by the receiver summary's tap: open the received list (R9). */
        const val EXTRA_SHOW_RECEIVED = "show_received"
        private const val STATE_SHOW_ALL = "showAll"
    }
}
