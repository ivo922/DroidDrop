package dev.ivo.droiddrop.devcli

import dev.ivo.droiddrop.core.pairing.Base64Url
import dev.ivo.droiddrop.core.pairing.PairUri
import dev.ivo.droiddrop.core.protocol.Message
import dev.ivo.droiddrop.core.protocol.MessageCodec
import dev.ivo.droiddrop.core.session.DroidDropClient
import dev.ivo.droiddrop.core.session.MessageSender
import dev.ivo.droiddrop.core.session.SessionListener
import dev.ivo.droiddrop.core.testing.SelfSigned
import dev.ivo.droiddrop.core.tls.DeviceIdentity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
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
        else -> usage()
    }
}

private fun usage(): Nothing {
    System.err.println("usage: devcli pair '<droiddrop://pair?...>' | devcli connect [--ping]")
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
