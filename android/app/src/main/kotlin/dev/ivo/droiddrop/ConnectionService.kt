package dev.ivo.droiddrop

import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import dev.ivo.droiddrop.core.clipboard.ClipRules
import dev.ivo.droiddrop.core.pairing.Base64Url
import dev.ivo.droiddrop.core.pairing.PairUri
import dev.ivo.droiddrop.core.protocol.Frame
import dev.ivo.droiddrop.core.protocol.Message
import dev.ivo.droiddrop.core.session.DroidDropClient
import dev.ivo.droiddrop.core.session.MessageSender
import dev.ivo.droiddrop.core.session.SessionListener
import dev.ivo.droiddrop.core.tls.DeviceIdentity
import dev.ivo.droiddrop.core.transfer.FileReceiver
import dev.ivo.droiddrop.core.transfer.PartialStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

class ConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val loopMutex = Mutex()
    /** Ends the reconnect wait early: a new network, the app opened, or a file queued. */
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            wake.trySend(Unit)
        }
    }
    private lateinit var store: PairingStore
    private lateinit var locator: MacLocator
    private lateinit var clipboard: ClipboardWriter
    private lateinit var partials: PartialStore
    private lateinit var downloads: DownloadsDestination
    private lateinit var hold: LockedClipHold
    /** Lock and unlock, delivered on the main thread like the clip writes they release. */
    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> hold.onLocked()
                Intent.ACTION_SCREEN_ON ->
                    hold.onScreenOn(getSystemService(KeyguardManager::class.java).isKeyguardLocked)?.let(::applyHeld)
                Intent.ACTION_USER_PRESENT -> hold.onUnlocked()?.let(::applyHeld)
            }
        }

        private fun applyHeld(text: String) {
            Log.d(TAG, "applied held clip")
            applyClip(text)
        }
    }
    private var loop: Job? = null

    override fun onCreate() {
        super.onCreate()
        store = PairingStore(this)
        locator = MacLocator(this)
        clipboard = ClipboardWriter.forContext(this)
        hold = LockedClipHold(lockedNow(), ClipHoldSetting(this)::load)
        val lockEvents = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        // Exported: SystemUI sends USER_PRESENT under an app uid, which never reaches a non-exported receiver.
        // All three are protected broadcasts, so no app can fake them.
        registerReceiver(lockReceiver, lockEvents, Context.RECEIVER_EXPORTED)
        Notifications.createChannel(this)
        partials = PartialStore(File(cacheDir, "partials"))
        downloads = DownloadsDestination(this)
        TransferNotifications.createChannel(this)
        scope.launch(Dispatchers.IO) { runCatching { partials.prune() } }
        scope.launch(Dispatchers.IO) { runCatching { pruneOutgoingSpool() } }
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
        scope.launch {
            ConnectionStatus.state.collect { state ->
                getSystemService(NotificationManager::class.java)
                    .notify(Notifications.ID, Notifications.build(this@ConnectionService, state))
                ClipboardTileService.refresh(this@ConnectionService)
            }
        }
        scope.launch {
            combine(Transfers.incoming, Transfers.sender.status, Transfers.sender.pending) { incoming, outgoing, pending ->
                Triple(incoming, outgoing, pending)
            }.conflate().collect { (incoming, outgoing, pending) ->
                TransferNotifications.showIncoming(this@ConnectionService, incoming)
                TransferNotifications.showOutgoing(this@ConnectionService, outgoing, pending, macName())
                delay(PROGRESS_INTERVAL_MILLIS) // at most two updates a second
            }
        }
        scope.launch {
            Transfers.received.collect { TransferNotifications.showOutcome(this@ConnectionService, it, macName()) }
        }
        scope.launch {
            Transfers.sender.outcomes.collect { TransferNotifications.showOutcome(this@ConnectionService, it, macName()) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pairUri = intent?.getStringExtra(EXTRA_PAIR_URI)?.let(PairUri::parse)
        if (pairUri == null) ConnectionStatus.state.update { it.onServiceStart(store.load()?.name) }
        startForeground(
            Notifications.ID,
            Notifications.build(this, ConnectionStatus.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        if (pairUri == null && loop?.isActive == true) {
            wake.trySend(Unit) // someone wants the Mac now: skip the rest of the backoff
        } else {
            loop?.cancel()
            loop = scope.launch {
                loopMutex.withLock {
                    AndroidIdentity.awaitPendingDelete()
                    val identity = loadIdentity()
                    currentCoroutineContext().ensureActive()
                    val client = DroidDropClient(identity, deviceName())
                    // pair() blocks in client.run until that session ends. A failed first pair
                    // leaves Failed on screen. A failed re-pair keeps that message until the saved
                    // Mac connects or the first search round ends. CancellationException propagates.
                    if (pairUri != null) {
                        val paired = pair(client, pairUri)
                        if (!paired && store.load() == null) {
                            // Nothing to reconnect to: the app keeps showing the Failed state, the notification goes.
                            // startId: a newer pair or start that arrived meanwhile keeps the service.
                            stopSelf(startId)
                            return@withLock
                        }
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
        TransferNotifications.cancelProgress(this)
        unregisterReceiver(lockReceiver)
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Retries with backoff instead of throwing: the service scope has no exception handler, so a keystore error
     * would crash the process, and START_STICKY would crash-loop it.
     */
    private suspend fun loadIdentity(): DeviceIdentity {
        var backoff = ReconnectPolicy.INITIAL_BACKOFF_MILLIS
        while (true) {
            try {
                return withContext(Dispatchers.IO) { AndroidIdentity.loadOrCreate() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "could not load the identity", e)
                ConnectionStatus.state.value = ConnectionState.Failed("Could not load this phone's identity. Retrying…")
                delay(backoff)
                backoff = ReconnectPolicy.nextBackoff(backoff, woke = false)
            }
        }
    }

    /** Pairs and then keeps that session as the live connection. Returns false if pairing failed. */
    private suspend fun pair(client: DroidDropClient, uri: PairUri): Boolean {
        ConnectionStatus.state.value = ConnectionState.Pairing(uri.name)
        val fingerprint = Base64Url.decode32(uri.fingerprint)!!
        val secret = Base64Url.decode32(uri.secret)!!
        var lastError: Exception? = null
        var sawPinMismatch = false
        for (host in uri.hosts) {
            var paired = false
            val transfers = newTransferSession()
            try {
                client.run(host, uri.port, fingerprint, secret, listener(
                    transfers,
                    onPaired = { name ->
                        store.save(PairedMac(name, uri.fingerprint, uri.hosts, uri.port))
                        store.rememberAddress(host, uri.port)
                        paired = true
                    },
                ))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (ReconnectPolicy.alreadyPaired(e)) {
                    // Keep the QR's fresh addresses: they are why the user scanned it again.
                    store.save(PairedMac(uri.name, uri.fingerprint, uri.hosts, uri.port))
                    store.rememberAddress(host, uri.port)
                    paired = true
                } else if (ReconnectPolicy.classify(e) == ReconnectPolicy.FailureKind.PinMismatch) {
                    sawPinMismatch = true
                    Log.w(TAG, "pairing via $host rejected the pinned certificate", e)
                } else {
                    Log.w(TAG, "pairing via $host failed", e)
                    lastError = e
                }
            } finally {
                transfers.close()
                Clips.link.detach()
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
        var backoff = ReconnectPolicy.INITIAL_BACKOFF_MILLIS
        val history = ReconnectHistory()
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
            val round = ReconnectRound(history)
            for (endpoint in candidates) {
                val transfers = newTransferSession()
                try {
                    client.run(endpoint.host, endpoint.port, fingerprint, null, listener(
                        transfers,
                        onConnected = {
                            connected = true
                            history.onConnected()
                            store.rememberAddress(endpoint.host, endpoint.port)
                        },
                    ))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "session with $endpoint ended", e)
                    if (!connected) round.failed(endpoint, ReconnectPolicy.classify(e))
                } finally {
                    transfers.close()
                    // Sessions run one at a time under loopMutex, so this cannot clear a newer session's sender.
                    Clips.link.detach()
                }
                if (connected) break
            }

            if (connected) {
                announceSearch = true
                backoff = ReconnectPolicy.INITIAL_BACKOFF_MILLIS
                continue
            }
            // Never unpairs (see ReconnectPolicy): it shows the problem and keeps trying until the user pairs again.
            val verdict = round.verdict()
            if (verdict.staleHosts.isNotEmpty()) store.forgetAddresses(verdict.staleHosts)
            val problem = verdict.problem
            if (problem != null) ConnectionStatus.state.value = ConnectionState.Failed(problem.message(mac.name))
            announceSearch = problem == null
            val woke = withTimeoutOrNull(backoff) { wake.receive() } != null
            backoff = ReconnectPolicy.nextBackoff(backoff, woke)
        }
    }

    /** One per client.run. Always close it in a finally: that cancels the send job, which holds the sender's lock. */
    private fun newTransferSession() = TransferSession(
        scope,
        FileReceiver(partials, downloads),
        Transfers.sender,
        { Transfers.incoming.value = it },
        { Transfers.received.tryEmit(it) },
    )

    /** Deletes spooled copies of non-regular files (see OutgoingFiles.open) that a crash left behind. */
    private fun pruneOutgoingSpool() {
        val cutoff = System.currentTimeMillis() - OUTGOING_SPOOL_MAX_AGE_MILLIS
        File(cacheDir, "outgoing").listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    private fun macName(): String = store.load()?.name ?: "Mac"

    /** Screen off, or the lock screen showing. */
    private fun lockedNow(): Boolean =
        getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
            !getSystemService(PowerManager::class.java).isInteractive

    /** Main thread. Clip text is never logged. */
    private fun applyClip(text: String) {
        if (clipboard.write(text)) {
            // Before the clip listener runs: it is dispatched later on this same looper.
            Clips.link.outbox.noteRemote(text)
        } else {
            Log.w(TAG, "could not apply clip")
        }
    }

    private fun listener(
        transfers: TransferSession,
        onPaired: (String) -> Unit = {},
        onConnected: () -> Unit = {},
    ) = object : SessionListener {
        override fun onPaired(serverName: String) = onPaired(serverName)

        override fun onConnected(serverName: String, sender: MessageSender) {
            // Not only in onCreate: the service runs for weeks. Here, on the reader thread, no offer can race it.
            runCatching { partials.prune() }
            onConnected()
            transfers.connected(sender)
            Clips.link.attach(sender)
            ConnectionStatus.state.value = ConnectionState.Connected(serverName)
        }

        // Runs on the session's IO thread and must not throw. Clip text is never logged.
        override fun onMessage(message: Message) {
            if (transfers.handle(message)) return
            if (message !is Message.Clip) {
                Log.d(TAG, "received $message")
                return
            }
            val text = ClipRules.acceptIncoming(message)
            if (text == null) {
                Log.d(TAG, "ignored clip ${message.id}")
                return
            }
            scope.launch(Dispatchers.Main) {
                val now = hold.onClip(text, lockedNow())
                if (now != null) applyClip(now) else Log.d(TAG, "held clip ${message.id}")
            }
        }

        override fun onChunk(chunk: Frame.Chunk) = transfers.chunk(chunk)
    }

    private fun deviceName(): String = ReconnectPolicy.deviceName(
        Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME),
        Build.MODEL,
    )

    companion object {
        private const val TAG = "ConnectionService"
        private const val EXTRA_PAIR_URI = "pairUri"
        private const val PROGRESS_INTERVAL_MILLIS = 500L
        private const val OUTGOING_SPOOL_MAX_AGE_MILLIS = 24 * 60 * 60 * 1_000L

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
