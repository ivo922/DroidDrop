import DroidDropKit
import Foundation

setvbuf(stdout, nil, _IOLBF, 0)

/// Each stdout line is one protocol event, so embedded newlines would split it.
func emit(_ line: String) {
    print(line.replacingOccurrences(of: "\n", with: " "))
}

func fail(_ error: Any) -> Never {
    emit("ERROR \(error)")
    exit(1)
}

func usage() -> Never {
    FileHandle.standardError.write(Data("usage: droiddrop-server [--port N] [--state DIR] [--host IP] [--advertise]\n".utf8))
    exit(2)
}

var port: UInt16 = 47321
var stateDirectory = FileManager.default.temporaryDirectory.appending(path: "droiddrop-server")
var host = "127.0.0.1"
var advertise = false

var arguments = CommandLine.arguments.dropFirst()
while let argument = arguments.popFirst() {
    switch argument {
    case "--port":
        guard let value = arguments.popFirst().flatMap({ UInt16($0) }) else { usage() }
        port = value
    case "--state":
        guard let value = arguments.popFirst(), !value.isEmpty else { usage() }
        stateDirectory = URL(fileURLWithPath: value)
    case "--host":
        guard let value = arguments.popFirst(), !value.isEmpty else { usage() }
        host = value
    case "--advertise":
        advertise = true
    default:
        usage()
    }
}

let identity: Identity
let peers: PeerStore
do {
    identity = try IdentityStore(directory: stateDirectory).loadOrCreate(commonName: "DroidDrop Test Server")
    peers = try PeerStore(url: stateDirectory.appending(path: "peers.json"))
} catch {
    fail(error)
}

let server = DroidDropServer(
    identity: identity,
    peers: peers,
    configuration: .init(name: "droiddrop-server", port: port, advertise: advertise)
)
server.onPeerPaired = { emit("PAIRED \($0.name) \(Base64URL.encode($0.fingerprint))") }
server.onConnectedPeersChanged = { emit("CONNECTED \($0.map(\.name).joined(separator: ","))") }
server.onMessage = { peer, message in
    let json = (try? MessageCodec.encode(message)).map { String(decoding: $0, as: UTF8.self) } ?? "?"
    emit("MESSAGE \(peer.name) \(json)")
}

/// Confined to the main queue.
var listening = false

func printPairURI() {
    guard listening else { return emit("ERROR not listening yet") }
    emit("PAIR_URI \(server.openPairingWindow(hosts: [host]).string)")
}

server.start { result in
    switch result {
    case .success(let port):
        emit("LISTENING \(port)")
        DispatchQueue.main.async {
            listening = true
            printPairURI()
        }
    case .failure(let error):
        fail(error)
    }
}

Thread.detachNewThread {
    while let line = readLine() {
        let parts = line.split(separator: " ", maxSplits: 2).map(String.init)
        switch parts.first {
        case "pair" where parts.count == 1:
            DispatchQueue.main.async(execute: printPairURI)
        case "send" where parts.count == 3:
            guard let fingerprint = Base64URL.decode32(parts[1]) else {
                emit("ERROR bad fingerprint")
                continue
            }
            let message: Message
            do {
                message = try MessageCodec.decode(Data(parts[2].utf8))
            } catch {
                emit("ERROR bad message: \(error)")
                continue
            }
            if case .unknown(let type) = message {
                emit("ERROR cannot send unknown message type \(type)")
                continue
            }
            guard server.connectedPeers().contains(where: { $0.fingerprint == fingerprint }) else {
                emit("ERROR peer not connected")
                continue
            }
            server.send(message, to: fingerprint)
        default:
            emit("ERROR unknown command")
        }
    }
}

dispatchMain()
