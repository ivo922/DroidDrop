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

class MainActivity : ComponentActivity() {
    private val scope = MainScope()
    private var scanAfterLocalNetworkGrant = false
    private var permissionRequestOpen = false
    private lateinit var store: PairingStore
    private val receivedFiles: ReceivedFilesModel by viewModels()
    private val clipboard by lazy { getSystemService(ClipboardManager::class.java) }
    private val receivedViewStore by lazy { ReceivedViewStore(this) }

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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
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
                val received by receivedFiles.list.snapshot.collectAsStateWithLifecycle()
                var showAll by rememberSaveable { mutableStateOf(false) }
                var receivedView by remember { mutableStateOf(receivedViewStore.load()) }
                // Keeps the main screen's scroll position while View all is open.
                val screens = rememberSaveableStateHolder()
                BackHandler(enabled = showAll) { showAll = false }
                if (showAll) {
                    ReceivedFilesScreen(
                        received = received?.value,
                        nowMillis = received?.atMillis ?: 0L,
                        view = receivedView,
                        onViewChange = {
                            receivedView = it
                            receivedViewStore.save(it)
                        },
                        onBack = { showAll = false },
                        onOpen = ::openReceived,
                    )
                } else {
                    screens.SaveableStateProvider("main") {
                        MainScreen(
                            state = state,
                            paired = paired,
                            phoneCode = phoneCode,
                            onPair = ::scanPairingCode,
                            onSendFiles = { filePicker.launch(arrayOf("*/*")) },
                            onSendClipboard = ::sendClipboard,
                            onUnpair = ::unpair,
                            onAllowBackground = ::requestBatteryExemption,
                            onViewAll = { showAll = true },
                            received = received?.value,
                            nowMillis = received?.atMillis ?: 0L,
                            onOpen = ::openReceived,
                        )
                    }
                }
            }
        }
        // The list follows Download/DroidDrop/: refreshed on resume and whenever a file arrives.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Transfers.received.collect { if (it is TransferOutcome.Received) refreshReceived() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshReceived()
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

    private fun unpair() {
        ConnectionService.stop(this)
        Transfers.sender.cancelAll() // the next pairing, maybe with another Mac, must not get this queue
        store.clear()
        ConnectionStatus.state.value = ConnectionState.Unpaired
        AndroidIdentity.enqueueDelete()
    }

    private fun refreshReceived() = receivedFiles.list.refresh()

    private fun openReceived(file: ReceivedFile) {
        val fileGone = try {
            startActivity(FileViewer.intent(this, file.uri))
            return
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            // MediaStore refuses the read grant for a row deleted since the list loaded.
            true
        } catch (e: IllegalArgumentException) {
            true
        }
        Toast.makeText(this, ReceivedFileText.openFailure(file.name, fileGone), Toast.LENGTH_LONG).show()
        if (fileGone) refreshReceived()
    }

    private fun requestBatteryExemption() {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName".toUri()))
    }
}
