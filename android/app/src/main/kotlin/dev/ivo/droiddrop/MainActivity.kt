package dev.ivo.droiddrop

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import dev.ivo.droiddrop.core.pairing.Pairing
import dev.ivo.droiddrop.core.pairing.PairUri
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
    private val clipboard by lazy { getSystemService(ClipboardManager::class.java) }

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
        super.onCreate(savedInstanceState)
        store = PairingStore(this)
        requestMissingPermissions()

        setContent {
            MaterialTheme {
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
                MainScreen(
                    state = state,
                    paired = paired,
                    phoneCode = phoneCode,
                    onPair = ::scanPairingCode,
                    onSendFiles = { filePicker.launch(arrayOf("*/*")) },
                    onUnpair = ::unpair,
                    onAllowBackground = ::requestBatteryExemption,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
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

    private fun unpair() {
        ConnectionService.stop(this)
        Transfers.sender.cancelAll() // the next pairing, maybe with another Mac, must not get this queue
        store.clear()
        ConnectionStatus.state.value = ConnectionState.Unpaired
        AndroidIdentity.enqueueDelete()
    }

    private fun requestBatteryExemption() {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName".toUri()))
    }
}

@Composable
private fun MainScreen(
    state: ConnectionState,
    paired: Boolean,
    phoneCode: String,
    onPair: () -> Unit,
    onSendFiles: () -> Unit,
    onUnpair: () -> Unit,
    onAllowBackground: () -> Unit,
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(24.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("DroidDrop", style = MaterialTheme.typography.headlineMedium)
            Text(state.describe(), style = MaterialTheme.typography.titleMedium)
            if (!paired || state is ConnectionState.Failed) {
                Button(onClick = onPair) { Text(if (paired) "Pair again" else "Pair with Mac") }
            }
            if (paired) {
                Button(onClick = onSendFiles) { Text("Send files") }
                OutlinedButton(onClick = onUnpair) { Text("Unpair") }
            }
            OutlinedButton(onClick = onAllowBackground) { Text("Allow running in background") }
            // Selectable so the automatic clipboard send can be tried on a device.
            SelectionContainer {
                Text("Phone code: $phoneCode", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
