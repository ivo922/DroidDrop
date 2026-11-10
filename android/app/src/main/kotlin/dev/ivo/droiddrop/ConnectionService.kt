package dev.ivo.droiddrop

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import dev.ivo.droiddrop.core.pairing.Base64Url
import dev.ivo.droiddrop.core.pairing.PairUri
import dev.ivo.droiddrop.core.protocol.Message
import dev.ivo.droiddrop.core.session.DroidDropClient
import dev.ivo.droiddrop.core.session.MessageSender
import dev.ivo.droiddrop.core.session.SessionListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class ConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val loopMutex = Mutex()
    private val networkChanged = Channel<Unit>(Channel.CONFLATED)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            networkChanged.trySend(Unit)
        }
    }
    private lateinit var store: PairingStore
    private lateinit var locator: MacLocator
    private var loop: Job? = null

    override fun onCreate() {
        super.onCreate()
        store = PairingStore(this)
        locator = MacLocator(this)
        Notifications.createChannel(this)
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
        scope.launch {
            ConnectionStatus.state.collect { state ->
                getSystemService(NotificationManager::class.java)
                    .notify(Notifications.ID, Notifications.build(this@ConnectionService, state))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            Notifications.ID,
            Notifications.build(this, ConnectionStatus.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        val pairUri = intent?.getStringExtra(EXTRA_PAIR_URI)?.let(PairUri::parse)
        if (pairUri != null || loop?.isActive != true) {
            loop?.cancel()
            loop = scope.launch {
                loopMutex.withLock {
                    AndroidIdentity.awaitPendingDelete()
                    val identity = withContext(Dispatchers.IO) { AndroidIdentity.loadOrCreate() }
                    currentCoroutineContext().ensureActive()
                    val client = DroidDropClient(identity, deviceName())
                    // pair() blocks in client.run until that session ends. A failed first pair
                    // leaves Failed on screen. A failed re-pair keeps that message until the saved
                    // Mac connects or the first search round ends. CancellationException propagates.
                    if (pairUri != null) {
                        val paired = pair(client, pairUri)
                        if (!paired && store.load() == null) return@withLock
                        connectLoop(client, keepFailure = !paired)
                    } else {
                        connectLoop(client)
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Pairs and then keeps that session as the live connection. Returns false if pairing failed. */
    private suspend fun pair(client: DroidDropClient, uri: PairUri): Boolean {
        ConnectionStatus.state.value = ConnectionState.Pairing(uri.name)
        val fingerprint = Base64Url.decode32(uri.fingerprint)!!
        val secret = Base64Url.decode32(uri.secret)!!
            var lastError: Exception? = null
        var sawPinMismatch = false
        for (host in uri.hosts) {
            var paired = false
            try {
                client.run(host, uri.port, fingerprint, secret, listener(
                    onPaired = { name ->
                        store.save(PairedMac(name, uri.fingerprint, uri.hosts, uri.port))
                        store.rememberAddress(host, uri.port)
                        paired = true
                    },
                ))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (ReconnectPolicy.classify(e) == ReconnectPolicy.FailureKind.PinMismatch) {
                    sawPinMismatch = true
                    Log.w(TAG, "pairing via $host rejected the pinned certificate", e)
                } else {
                    Log.w(TAG, "pairing via $host failed", e)
                    lastError = e
                }
            }
            if (paired) return true
        }
        ConnectionStatus.state.value = if (sawPinMismatch && lastError == null) {
            ConnectionState.Failed("The Mac's certificate does not match the pairing code.")
        } else {
            ConnectionState.Failed("Pairing failed: ${lastError?.message ?: "Mac not reachable"}")
        }
        return false
    }

    private suspend fun connectLoop(client: DroidDropClient, keepFailure: Boolean = false) {
        var backoff = INITIAL_BACKOFF_MILLIS
        val rejections = RejectionCounter()
        var announceSearch = !keepFailure
        while (true) {
            currentCoroutineContext().ensureActive()
            val mac = store.load() ?: run {
                ConnectionStatus.state.value = ConnectionState.Unpaired
                stopSelf()
                return
            }
            if (announceSearch) ConnectionStatus.state.value = ConnectionState.Searching(mac.name)
            val fingerprint = Base64Url.decode32(mac.fingerprint)!!
            val candidates = buildList {
                locator.find(mac.fingerprint)?.let(::add)
                mac.hosts.forEach { add(Endpoint(it, mac.port)) }
            }.distinct()

            var connected = false
            var pinMismatches = 0
            var otherFailures = 0
            for (endpoint in candidates) {
                try {
                    client.run(endpoint.host, endpoint.port, fingerprint, null, listener(
                        onConnected = {
                            connected = true
                            rejections.onConnected()
                            store.rememberAddress(endpoint.host, endpoint.port)
                        },
                    ))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!connected) {
                        when (rejections.onFailure(ReconnectPolicy.classify(e))) {
                            RejectionCounter.Outcome.Backoff -> {
                                otherFailures++
                                Log.w(TAG, "session with $endpoint ended", e)
                            }
                            RejectionCounter.Outcome.UnpairPinMismatch -> {
                                pinMismatches++
                                Log.w(TAG, "certificate at $endpoint did not match the pin", e)
                            }
                            RejectionCounter.Outcome.UnpairRejections -> {
                                Log.w(TAG, "Mac rejected the phone", e)
                                forgetPairing("${mac.name} no longer accepts this phone. Unpair and pair again.")
                                return
                            }
                        }
                    } else {
                        Log.w(TAG, "session with $endpoint ended", e)
                    }
                }
                if (connected) break
            }
            if (!connected && pinMismatches > 0 && otherFailures == 0) {
                forgetPairing("The paired Mac's certificate changed. Pair again.")
                return
            }

            announceSearch = true
            if (connected) {
                backoff = INITIAL_BACKOFF_MILLIS
                continue
            }
            withTimeoutOrNull(backoff) { networkChanged.receive() }
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MILLIS)
        }
    }

    /**
     * Clears the paired Mac and deletes the keystore identity so the next pair presents a new fingerprint.
     * Runs to completion even when the service scope is cancelling.
     */
    private suspend fun forgetPairing(message: String) {
        withContext(NonCancellable) {
            store.clear()
            withContext(Dispatchers.IO) {
                runCatching { AndroidIdentity.delete() }.onFailure { Log.e(TAG, "identity delete failed", it) }
            }
            ConnectionStatus.state.value = ConnectionState.Failed(message)
            stopSelf()
        }
    }

    private fun listener(onPaired: (String) -> Unit = {}, onConnected: () -> Unit = {}) = object : SessionListener {
        override fun onPaired(serverName: String) = onPaired(serverName)

        override fun onConnected(serverName: String, sender: MessageSender) {
            onConnected()
            ConnectionStatus.state.value = ConnectionState.Connected(serverName)
        }

        override fun onMessage(message: Message) {
            Log.d(TAG, "received $message")
        }
    }

    private fun deviceName(): String = ReconnectPolicy.deviceName(
        Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME),
        Build.MODEL,
    )

    companion object {
        private const val TAG = "ConnectionService"
        private const val EXTRA_PAIR_URI = "pairUri"
        private const val INITIAL_BACKOFF_MILLIS = 1_000L
        private const val MAX_BACKOFF_MILLIS = 60_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ConnectionService::class.java))
        }

        fun pair(context: Context, uri: String) {
            context.startForegroundService(Intent(context, ConnectionService::class.java).putExtra(EXTRA_PAIR_URI, uri))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
        }
    }
}
