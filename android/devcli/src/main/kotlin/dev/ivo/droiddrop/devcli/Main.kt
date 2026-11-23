package dev.ivo.droiddrop.devcli

import dev.ivo.droiddrop.core.clipboard.ClipOutbox
import dev.ivo.droiddrop.core.pairing.Base64Url
import dev.ivo.droiddrop.core.pairing.PairUri
import dev.ivo.droiddrop.core.protocol.Frame
import dev.ivo.droiddrop.core.protocol.Message
import dev.ivo.droiddrop.core.protocol.MessageCodec
import dev.ivo.droiddrop.core.protocol.ProtocolException
import dev.ivo.droiddrop.core.session.DroidDropClient
import dev.ivo.droiddrop.core.session.MessageSender
import dev.ivo.droiddrop.core.session.SessionListener
import dev.ivo.droiddrop.core.testing.SelfSigned
import dev.ivo.droiddrop.core.tls.DeviceIdentity
import dev.ivo.droiddrop.core.transfer.DirectoryDestination
import dev.ivo.droiddrop.core.transfer.FileReceiver
import dev.ivo.droiddrop.core.transfer.FileSender
import dev.ivo.droiddrop.core.transfer.OutgoingFile
import dev.ivo.droiddrop.core.transfer.PartialStore
import dev.ivo.droiddrop.core.transfer.TransferOutcome
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import kotlin.system.exitProcess

private const val PASSWORD = "droiddrop-devcli"

/** Thrown from listener callbacks to end a session on purpose. */
private class Done : RuntimeException()

private data class SavedServer(val name: String, val fingerprint: String, val host: String, val port: Int)

fun main(args: Array<String>) {
    val home = File(System.getenv("DROIDDROP_DEVCLI_HOME") ?: "${System.getProperty("user.home")}/.droiddrop-devcli")
    home.mkdirs()
    val client = DroidDropClient(loadOrCreateIdentity(File(home, "identity.p12")), "devcli")
    when (args.firstOrNull()) {
        "pair" -> pair(client, home, args.getOrNull(1) ?: usage())
        "connect" -> connect(client, home, ping = "--ping" in args)
        "send" -> send(client, home, args.drop(1).ifEmpty { usage() })
        "receive" -> receive(client, home, args.drop(1))
        "clip" -> clip(client, home, args.getOrNull(1) ?: usage())
        else -> usage()
    }
}

private fun usage(): Nothing {
    System.err.println(
        "usage: devcli pair '<droiddrop://pair?...>' | devcli connect [--ping] | devcli send <file>... | " +
            "devcli receive <dir> [--count N] [--batch-end] | devcli clip <text>",
    )
    exitProcess(2)
}

private fun pair(client: DroidDropClient, home: File, uriText: String) {
    val uri = PairUri.parse(uriText) ?: run { println("ERROR invalid pairing URI"); exitProcess(1) }
    val fingerprint = Base64Url.decode32(uri.fingerprint)!!
    val secret = Base64Url.decode32(uri.secret)!!
    for (host in uri.hosts) {
        try {
            runBlocking {
                client.run(host, uri.port, fingerprint, secret, object : SessionListener {
                    override fun onPaired(serverName: String) {
                        saveServer(home, SavedServer(serverName, uri.fingerprint, host, uri.port))
                        println("PAIRED $serverName")
                    }

                    override fun onConnected(serverName: String, sender: MessageSender) = throw Done()
                })
            }
        } catch (e: Done) {
            return
        } catch (e: ProtocolException) {
            if (e.code != ProtocolException.ALREADY_PAIRED) {
                println("ERROR $host: ${e.message}")
                continue
            }
            saveServer(home, SavedServer(uri.name, uri.fingerprint, host, uri.port))
            println("ALREADY_PAIRED ${uri.name}")
            return
        } catch (e: Exception) {
            println("ERROR $host: ${e.message}")
        }
    }
    exitProcess(1)
}

private fun connect(client: DroidDropClient, home: File, ping: Boolean) {
    val server = loadServer(home) ?: run { println("ERROR not paired"); exitProcess(1) }
    try {
        runBlocking {
            client.run(server.host, server.port, Base64Url.decode32(server.fingerprint)!!, null, object : SessionListener {
                override fun onConnected(serverName: String, sender: MessageSender) {
                    println("CONNECTED $serverName")
                    if (ping) sender.send(Message.Ping(42))
                }

                override fun onMessage(message: Message) {
                    if (ping && message == Message.Pong(42)) {
                        println("PONG 42")
                        throw Done()
                    }
                    println("MESSAGE ${MessageCodec.encode(message).decodeToString()}")
                }
            })
        }
        println("CLOSED")
    } catch (e: Done) {
        return
    } catch (e: Exception) {
        println("ERROR ${e.message}")
        exitProcess(1)
    }
}

/** Sends one clip as an explicit tap would: prints SENT, or NOT_SENT <decision> and exits 1. */
private fun clip(client: DroidDropClient, home: File, text: String) {
    val server = loadServer(home) ?: run { println("ERROR not paired"); exitProcess(1) }
    var sent = false
    try {
        runBlocking {
            client.run(server.host, server.port, Base64Url.decode32(server.fingerprint)!!, null, object : SessionListener {
                override fun onConnected(serverName: String, sender: MessageSender) {
                    println("CONNECTED $serverName")
                    when (val decision = ClipOutbox().decide(text, automatic = false)) {
                        is ClipOutbox.Decision.Send -> {
                            sender.send(decision.clip)
                            sent = true
                            println("SENT")
                        }
                        else -> println("NOT_SENT $decision")
                    }
                    throw Done()
                }
            })
        }
        println("CLOSED")
    } catch (e: Done) {
        // Session ended on purpose.
    } catch (e: Exception) {
        println("ERROR ${oneLine(e.message ?: e.toString())}")
    }
    if (!sent) exitProcess(1)
}

/** Stdout lines are one event each, so embedded newlines would split them. */
private fun oneLine(text: String) = text.replace('\n', ' ').replace('\r', ' ')

private fun send(client: DroidDropClient, home: File, paths: List<String>) {
    val server = loadServer(home) ?: run { println("ERROR not paired"); exitProcess(1) }
    val unreadable = mutableListOf<String>()
    val files = paths.mapNotNull { path ->
        val file = File(path)
        try {
            val mime = try { Files.probeContentType(file.toPath()) } catch (_: Exception) { null }
            OutgoingFile(file.name, mime ?: "application/octet-stream", FileChannel.open(file.toPath(), StandardOpenOption.READ))
        } catch (e: Exception) {
            unreadable += file.name
            null
        }
    }
    if (files.isEmpty()) {
        unreadable.forEach { println("FAILED ${oneLine(it)} unreadable") }
        exitProcess(1)
    }
    val fileSender = FileSender()
    val replies = Channel<Message>(Channel.UNLIMITED)
    var allSent = unreadable.isEmpty()
    var outcomesSeen = 0
    try {
        runBlocking {
            val scope = this
            var senderJob: Job? = null
            val outcomesJob = launch(start = CoroutineStart.UNDISPATCHED) {
                fileSender.outcomes.take(files.size).collect { outcome ->
                    outcomesSeen++
                    when (outcome) {
                        is TransferOutcome.Sent -> println("SENT ${oneLine(outcome.name)}")
                        is TransferOutcome.Failed -> {
                            allSent = false
                            println("FAILED ${oneLine(outcome.name)} ${oneLine(outcome.reason)}")
                        }
                        is TransferOutcome.Received -> Unit
                    }
                }
            }
            val sessionJob = launch {
                client.run(server.host, server.port, Base64Url.decode32(server.fingerprint)!!, null, object : SessionListener {
                    override fun onConnected(serverName: String, sender: MessageSender) {
                        println("CONNECTED $serverName")
                        unreadable.forEach { println("FAILED ${oneLine(it)} unreadable") }
                        fileSender.enqueue(files)
                        senderJob = scope.launch(Dispatchers.IO) { fileSender.run(sender, replies) }
                    }

                    override fun onMessage(message: Message) {
                        when (message) {
                            is Message.FileAccept, is Message.FileReject, is Message.FileResult, is Message.FileCancel ->
                                replies.trySend(message)
                            else -> Unit
                        }
                    }
                })
            }
            sessionJob.invokeOnCompletion { outcomesJob.cancel() }
            try {
                outcomesJob.join()
            } finally {
                sessionJob.cancel()
                senderJob?.cancel()
            }
            sessionJob.join()
            senderJob?.join()
            if (outcomesSeen < files.size) {
                println("CLOSED")
                allSent = false
            }
        }
    } catch (e: Exception) {
        println("ERROR ${oneLine(e.message ?: e.toString())}")
        allSent = false
    } finally {
        files.forEach { it.release() }
    }
    exitProcess(if (allSent) 0 else 1)
}

private fun receive(client: DroidDropClient, home: File, args: List<String>) {
    val directory = args.firstOrNull()?.takeUnless { it.startsWith("--") } ?: usage()
    var count = 1
    var awaitBatchEnd = false
    var i = 1
    while (i < args.size) {
        when (args[i]) {
            "--count" -> count = args.getOrNull(i + 1)?.toIntOrNull()?.takeIf { it > 0 } ?: usage()
            "--batch-end" -> awaitBatchEnd = true
            else -> usage()
        }
        i += if (args[i] == "--count") 2 else 1
    }
    val server = loadServer(home) ?: run { println("ERROR not paired"); exitProcess(1) }
    val receiver = FileReceiver(PartialStore(File(home, "partials")), DirectoryDestination(File(directory)))
    var link: MessageSender? = null
    var outcomes = 0
    var allReceived = true
    var batchEndSeen = false

    /** Ends the session once every expected file has an outcome and, with --batch-end, the batch_end has arrived. */
    fun maybeDone() {
        if (outcomes >= count && (!awaitBatchEnd || batchEndSeen)) throw Done()
    }

    /** Sends the reply first, so the peer hears the verdict before the session is torn down. */
    fun finish(reply: Message, outcome: TransferOutcome) {
        link!!.send(reply)
        when (outcome) {
            is TransferOutcome.Received -> println("RECEIVED ${oneLine(outcome.location)}")
            is TransferOutcome.Failed -> {
                allReceived = false
                println("FAILED ${oneLine(outcome.name)} ${oneLine(outcome.reason)}")
            }
            is TransferOutcome.Sent -> return
        }
        outcomes++
        maybeDone()
    }

    try {
        runBlocking {
            client.run(server.host, server.port, Base64Url.decode32(server.fingerprint)!!, null, object : SessionListener {
                override fun onConnected(serverName: String, sender: MessageSender) {
                    link = sender
                    println("CONNECTED $serverName")
                }

                override fun onMessage(message: Message) {
                    when (message) {
                        is Message.FileOffer -> link!!.send(receiver.offer(message))
                        is Message.FileDone -> receiver.done(message)?.let { (reply, outcome) -> finish(reply, outcome) }
                        is Message.FileCancel -> receiver.cancel(message.transferId)
                        is Message.BatchEnd -> if (awaitBatchEnd && !batchEndSeen) {
                            batchEndSeen = true
                            println("BATCH_END ${message.count} ${message.sent}")
                            maybeDone()
                        }
                        else -> Unit
                    }
                }

                override fun onChunk(chunk: Frame.Chunk) {
                    receiver.chunk(chunk)?.let { (reply, outcome) -> finish(reply, outcome) }
                }
            })
        }
        println("CLOSED")
        allReceived = false
    } catch (e: Done) {
        // All expected files have an outcome.
    } catch (e: Exception) {
        println("ERROR ${oneLine(e.message ?: e.toString())}")
        allReceived = false
    } finally {
        receiver.close()
    }
    exitProcess(if (allReceived) 0 else 1)
}

private fun loadOrCreateIdentity(file: File): DeviceIdentity {
    val store = KeyStore.getInstance("PKCS12")
    if (file.exists()) {
        file.inputStream().use { store.load(it, PASSWORD.toCharArray()) }
        val key = store.getKey("identity", PASSWORD.toCharArray()) as PrivateKey
        return DeviceIdentity(key, store.getCertificate("identity") as X509Certificate)
    }
    val identity = SelfSigned.generate("DroidDrop devcli")
    store.load(null, null)
    store.setKeyEntry("identity", identity.privateKey, PASSWORD.toCharArray(), arrayOf(identity.certificate))
    file.outputStream().use { store.store(it, PASSWORD.toCharArray()) }
    return identity
}

private fun saveServer(home: File, server: SavedServer) {
    File(home, "server.json").writeText(buildJsonObject {
        put("name", server.name)
        put("fingerprint", server.fingerprint)
        put("host", server.host)
        put("port", server.port)
    }.toString())
}

private fun loadServer(home: File): SavedServer? {
    val file = File(home, "server.json")
    if (!file.exists()) return null
    val json = Json.parseToJsonElement(file.readText()).jsonObject
    return SavedServer(
        json.getValue("name").jsonPrimitive.content,
        json.getValue("fingerprint").jsonPrimitive.content,
        json.getValue("host").jsonPrimitive.content,
        json.getValue("port").jsonPrimitive.int,
    )
}
