package dev.ivo.droiddrop

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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

    private val runtimePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        permissionRequestOpen = false
        if (localNetworkAllowed()) {
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
                    val code = withContext(Dispatchers.IO) {
                        Pairing.displayCode(AndroidIdentity.loadOrCreate().fingerprint)
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
                    onUnpair = ::unpair,
                    onAllowBackground = ::requestBatteryExemption,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (localNetworkAllowed()) {
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

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Android 17 blocks LAN sockets until this runtime permission is granted. Older releases allow them with INTERNET. */
    private fun localNetworkAllowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN ||
            checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

    private fun requestMissingPermissions() {
        val missing = buildList {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN &&
                checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.ACCESS_LOCAL_NETWORK)
            }
        }
        if (missing.isEmpty() || permissionRequestOpen) return
        permissionRequestOpen = true
        runtimePermissions.launch(missing.toTypedArray())
    }

    private fun resumeSavedConnection() {
        store.load()?.let { mac ->
            if (ConnectionStatus.state.value == ConnectionState.Unpaired) {
                ConnectionStatus.state.value = ConnectionState.Searching(mac.name)
            }
            ConnectionService.start(this)
        }
    }

    private fun scanPairingCode() {
        if (!localNetworkAllowed()) {
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
                OutlinedButton(onClick = onUnpair) { Text("Unpair") }
            }
            OutlinedButton(onClick = onAllowBackground) { Text("Allow running in background") }
            Text("Phone code: $phoneCode", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}
