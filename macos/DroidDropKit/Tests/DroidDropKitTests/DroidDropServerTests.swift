import Foundation
import Network
import Testing
@testable import DroidDropKit

@Suite struct DroidDropServerTests {
    let phone = try! Identity.generate(commonName: "Phone")

    func makeServer(peersURL: URL = temporaryDirectory().appending(path: "peers.json"),
                    idleTimeout: TimeInterval = DroidDropServer.idleTimeout,
                    handshakeTimeout: TimeInterval = DroidDropServer.handshakeTimeout,
                    pendingPerHost: Int = DroidDropServer.pendingPerHost,
                    configure: (DroidDropServer) -> Void = { _ in }) async throws -> (DroidDropServer, UInt16) {
        let server = DroidDropServer(
            identity: try Identity.generate(commonName: "Mac"),
            peers: try PeerStore(url: peersURL),
            configuration: .init(name: "Test Mac", port: 0, advertise: false, idleTimeout: idleTimeout,
                                 handshakeTimeout: handshakeTimeout, pendingPerHost: pendingPerHost)
        )
        configure(server)
        return (server, try await server.start())
    }

    func pair(_ server: DroidDropServer, port: UInt16, as phone: Identity? = nil,
              replacing: Data? = nil) async throws -> (FramedConnection, EventRecorder) {
        let phone = phone ?? self.phone
        let uri = server.openPairingWindow(hosts: ["127.0.0.1"], replacing: replacing)
        #expect(uri.port == Int(port))
        let (connection, events) = try TestClient.connect(port: port, identity: phone, pinnedServer: uri.fingerprint)
        #expect(try await events.nextMessage() == .hello(v: 1, name: "Test Mac", platform: .macos))
        connection.send(.hello(v: 1, name: "Pixel", platform: .android))
        let proof = Pairing.clientProof(secret: uri.secret, serverFingerprint: uri.fingerprint, clientFingerprint: phone.fingerprint)
        connection.send(.pairRequest(proof: Base64URL.encode(proof)))
        guard case .pairResult(true, let serverProof?, _) = try await events.nextMessage() else {
            throw UnexpectedEvent(description: "pairing was rejected")
        }
        #expect(Pairing.isValidServerProof(Base64URL.decode32(serverProof)!, secret: uri.secret,
                                           serverFingerprint: uri.fingerprint, clientFingerprint: phone.fingerprint))
        return (connection, events)
    }

    func reconnect(_ server: DroidDropServer, port: UInt16) async throws -> (FramedConnection, EventRecorder) {
        let (connection, events) = try TestClient.connect(port: port, identity: phone, pinnedServer: server.identity.fingerprint)
        #expect(try await events.nextMessage() == .hello(v: 1, name: "Test Mac", platform: .macos))
        connection.send(.hello(v: 1, name: "Pixel", platform: .android))
        return (connection, events)
    }

    @Test func pairsPersistsPeerAndAnswersPings() async throws {
        let (server, port) = try await makeServer()
        defer { server.stop() }
        let (connection, events) = try await pair(server, port: port)

        #expect(server.pairedPeers().map(\.name) == ["Pixel"])
        #expect(server.connectedPeers().map(\.fingerprint) == [phone.fingerprint])
        connection.send(.ping(id: 7))
        #expect(try await events.nextMessage() == .pong(id: 7))
    }

    @Test func rejectsBadProofAndDoesNotPersist() async throws {
        let (server, port) = try await makeServer()
        defer { server.stop() }
        let uri = server.openPairingWindow(hosts: ["127.0.0.1"])
        let (connection, events) = try TestClient.connect(port: port, identity: phone, pinnedServer: uri.fingerprint)
        _ = try await events.nextMessage()
        connection.send(.hello(v: 1, name: "Pixel", platform: .android))
        connection.send(.pairRequest(proof: Base64URL.encode(Data(repeating: 0, count: 32))))

        #expect(try await events.nextMessage() == .pairResult(ok: false, proof: nil, reason: "bad_proof"))
        try await events.expectClosed()
        #expect(server.pairedPeers().isEmpty)
    }

    @Test func rejectsPairRequestAfterWindowClosesBeforeClosing() async throws {
        let (server, port) = try await makeServer()
        defer { server.stop() }
        let uri = server.openPairingWindow(hosts: ["127.0.0.1"])
        let (connection, events) = try TestClient.connect(port: port, identity: phone, pinnedServer: uri.fingerprint)
        _ = try await events.nextMessage()
        server.closePairingWindow()
        connection.send(.hello(v: 1, name: "Pixel", platform: .android))
        let proof = Pairing.clientProof(secret: uri.secret, serverFingerprint: uri.fingerprint, clientFingerprint: phone.fingerprint)
        connection.send(.pairRequest(proof: Base64URL.encode(proof)))

        #expect(try await events.nextMessage() == .pairResult(ok: false, proof: nil, reason: "window_closed"))
        try await events.expectClosed()
        #expect(server.pairedPeers().isEmpty)
    }

    @Test func refusesUnknownClientWhenNoWindowIsOpen() async throws {
        let (server, port) = try await makeServer()
        defer { server.stop() }
        let (_, events) = try TestClient.connect(port: port, identity: phone, pinnedServer: server.identity.fingerprint)
        try await events.expectClosedWithoutFrames()
    }

    @Test func pairedPhoneReconnectsWithoutWindow() async throws {
        let (server, port) = try await makeServer()
        defer { server.stop() }
        let (first, _) = try await pair(server, port: port)
        first.cancel()

        let (connection, events) = try await reconnect(server, port: port)
        connection.send(.ping(id: 1))
        #expect(try await events.nextMessage() == .pong(id: 1))
    }

    @Test func newSessionReplacesOldOne() async throws {
        let (server, port) = try await makeServer()
        defer { server.stop() }
        let (_, firstEvents) = try await pair(server, port: port)
        let (second, secondEvents) = try await reconnect(server, port: port)

        try await firstEvents.expectClosed()
        second.send(.ping(id: 2))
        #expect(try await secondEvents.nextMessage() == .pong(id: 2))
        #expect(server.connectedPeers().count == 1)
    }

    @Test func unpairDisconnectsAndRefusesReconnect() async throws {
        let (server, port) = try await makeServer()
        defer { server.stop() }
        let (_, events) = try await pair(server, port: port)

        try server.unpair(fingerprint: phone.fingerprint)
        try await events.expectClosed()
        #expect(server.pairedPeers().isEmpty)

        let (_, retryEvents) = try TestClient.connect(port: port, identity: phone, pinnedServer: server.identity.fingerprint)
        try await retryEvents.expectClosedWithoutFrames()
    }

    @Test func deliversAppMessages() async throws {
        let received = EventRecorder()
        let (server, port) = try await makeServer { server in
            server.onMessage = { _, message in received.record(.frame(.control(try! MessageCodec.encode(message)))) }
        }
        defer { server.stop() }
        let (connection, _) = try await pair(server, port: port)

        let clip = Message.clip(id: "5b2c8f9e-1d3a-4e7b-9c6d-0a1b2c3d4e5f", mime: "text/plain", text: "hi")
        connection.send(clip)
        #expect(try await received.nextMessage() == clip)
    }

    @Test func pairAgainReplacesTheStalePeer() async throws {
        let (server, port) = try await makeServer()
        defer { server.stop() }
        let (stale, _) = try await pair(server, port: port)
        stale.cancel()
        let reinstalled = try Identity.generate(commonName: "Phone")
        _ = try await pair(server, port: port, as: reinstalled, replacing: phone.fingerprint)
        #expect(server.pairedPeers().map(\.fingerprint) == [reinstalled.fingerprint])
    }

    @Test func pairNewPhoneKeepsExistingPeers() async throws {
        let (server, port) = try await makeServer()
        defer { server.stop() }
        _ = try await pair(server, port: port)
        let second = try Identity.generate(commonName: "Phone 2")
        _ = try await pair(server, port: port, as: second)
        #expect(Set(server.pairedPeers().map(\.fingerprint)) == [phone.fingerprint, second.fingerprint])
    }

    @Test func broadcastReachesEstablishedSessions() async throws {
        let (server, port) = try await makeServer()
        defer { server.stop() }
        let (_, events) = try await pair(server, port: port)
        let clip = Message.clip(id: "6f1c0d2e-8a4b-4c3d-9e5f-0a1b2c3d4e5f", mime: "text/plain", text: "hi")
        server.broadcast(clip)
        #expect(try await events.nextMessage() == clip)
    }

    @Test func broadcastBeforeConnectIsNotReplayed() async throws {
        let (server, port) = try await makeServer()
        defer { server.stop() }
        server.broadcast(.clip(id: "6f1c0d2e-8a4b-4c3d-9e5f-0a1b2c3d4e5f", mime: "text/plain", text: "stale"))
        let (connection, events) = try await pair(server, port: port)
        connection.send(.ping(id: 1))
        #expect(try await events.nextMessage() == .pong(id: 1))
    }

    @Test func unpairBeforeHelloClosesTheNewSession() async throws {
        let (server, port) = try await makeServer()
        defer { server.stop() }
        let (first, _) = try await pair(server, port: port)
        first.cancel()
        let (connection, events) = try TestClient.connect(port: port, identity: phone, pinnedServer: server.identity.fingerprint)
        #expect(try await events.nextMessage() == .hello(v: 1, name: "Test Mac", platform: .macos))

        try server.unpair(fingerprint: phone.fingerprint)
        connection.send(.hello(v: 1, name: "Pixel", platform: .android))
        connection.send(.ping(id: 3))
        try await events.expectClosedWithoutFrames()
        #expect(server.connectedPeers().isEmpty)
    }

    @Test func unpairReportsWriteFailureAndKeepsPeer() async throws {
        let directory = temporaryDirectory()
        let (server, port) = try await makeServer(peersURL: directory.appending(path: "peers.json"))
        defer { server.stop() }
        let (connection, events) = try await pair(server, port: port)

        try FileManager.default.setAttributes([.posixPermissions: 0o555], ofItemAtPath: directory.path)
        defer { try? FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: directory.path) }
        #expect(throws: (any Error).self) { try server.unpair(fingerprint: phone.fingerprint) }
        #expect(server.pairedPeers().map(\.fingerprint) == [phone.fingerprint])
        connection.send(.ping(id: 4))
        #expect(try await events.nextMessage() == .pong(id: 4))
    }

    @Test func silentPairedClientIsClosedAfterIdleTimeout() async throws {
        let (server, port) = try await makeServer(idleTimeout: 0.5)
        defer { server.stop() }
        let (_, events) = try await pair(server, port: port)

        let started = ContinuousClock.now
        try await events.expectClosed()
        #expect(ContinuousClock.now - started < .seconds(3))
        #expect(server.connectedPeers().isEmpty)
    }

    // The dev CLI or another app may hold 47321: the server must take another port and advertise it.
    @Test func fallsBackToAnotherPortWhenTaken() async throws {
        let blocker = try NWListener(using: .tcp, on: .any)
        let blockerReady = DispatchSemaphore(value: 0)
        blocker.stateUpdateHandler = { if case .ready = $0 { blockerReady.signal() } }
        blocker.newConnectionHandler = { $0.cancel() }
        blocker.start(queue: DispatchQueue(label: "test.blocker"))
        defer { blocker.cancel() }
        #expect(blockerReady.wait(timeout: .now() + 5) == .success)
        let taken = try #require(blocker.port?.rawValue)

        let server = DroidDropServer(
            identity: try Identity.generate(commonName: "Mac"),
            peers: try PeerStore(url: temporaryDirectory().appending(path: "peers.json")),
            configuration: .init(name: "Test Mac", port: taken, advertise: false)
        )
        defer { server.stop() }
        let port = try await server.start()
        #expect(port != taken)
        #expect(server.openPairingWindow(hosts: ["127.0.0.1"]).port == Int(port))
    }

    // A listener that dies after it was ready (mDNSResponder restart, network teardown) must come back by itself:
    // otherwise the menu says "No phone connected" while no phone can ever connect.
    @Test func listenerFailureAfterReadyRestartsIt() async throws {
        let states = Recorded<UInt16?>()
        let (server, _) = try await makeServer { $0.onListeningChanged = { states.append($0) } }
        defer { server.stop() }
        server.failListenerForTesting()
        try await eventually { states.values.count >= 2 }
        #expect(states.values.first == .some(nil))
        let restarted = try #require(states.values.last ?? nil)
        let (connection, events) = try await pair(server, port: restarted)
        connection.send(.ping(id: 1))
        #expect(try await events.nextMessage() == .pong(id: 1))
    }

    // A serving listener on the default port that fails must come back on that port (phones remember it), not
    // fall back to an OS-assigned one as a port taken at startup does.
    @Test func failedListenerOnAFixedPortComesBackOnIt() async throws {
        let probe = try NWListener(using: .tcp, on: .any)
        let probed = DispatchSemaphore(value: 0)
        probe.stateUpdateHandler = { if case .ready = $0 { probed.signal() } }
        probe.newConnectionHandler = { $0.cancel() }
        probe.start(queue: DispatchQueue(label: "test.probe"))
        #expect(probed.wait(timeout: .now() + 5) == .success)
        let fixed = try #require(probe.port?.rawValue)
        probe.cancel()
        try await Task.sleep(for: .milliseconds(200))

        let states = Recorded<UInt16?>()
        let server = DroidDropServer(
            identity: try Identity.generate(commonName: "Mac"),
            peers: try PeerStore(url: temporaryDirectory().appending(path: "peers.json")),
            configuration: .init(name: "Test Mac", port: fixed, advertise: false)
        )
        server.onListeningChanged = { states.append($0) }
        defer { server.stop() }
        #expect(try await server.start() == fixed)
        server.failListenerForTesting()
        try await eventually { states.values.count >= 2 }
        #expect(states.values.first == .some(nil))
        #expect(states.values.last == .some(fixed))
    }

    @Test func stopBeforeReadyEndsStart() async throws {
        let server = DroidDropServer(
            identity: try Identity.generate(commonName: "Mac"),
            peers: try PeerStore(url: temporaryDirectory().appending(path: "peers.json")),
            configuration: .init(name: "Test Mac", port: 0, advertise: false)
        )
        let result = Recorded<Bool>()
        server.start { result.append((try? $0.get()) != nil) }
        server.stop()
        try await eventually { !result.values.isEmpty }
        #expect(result.values.count == 1)
    }

    // A LAN device that opens sockets and never finishes TLS must not hold them for the 90 s idle timeout.
    @Test func silentConnectionIsClosedAfterHandshakeTimeout() async throws {
        let (server, port) = try await makeServer(handshakeTimeout: 0.5)
        defer { server.stop() }
        let raw = RawClient(port: port)
        defer { raw.cancel() }
        try await eventually(timeout: 3) { raw.closed }
    }

    @Test func unpairedSessionIsClosedAfterHandshakeTimeout() async throws {
        let (server, port) = try await makeServer(handshakeTimeout: 0.5)
        defer { server.stop() }
        _ = server.openPairingWindow(hosts: ["127.0.0.1"])
        let (_, events) = try TestClient.connect(port: port, identity: phone, pinnedServer: server.identity.fingerprint)
        #expect(try await events.nextMessage() == .hello(v: 1, name: "Test Mac", platform: .macos))
        let started = ContinuousClock.now
        try await events.expectClosed()
        #expect(ContinuousClock.now - started < .seconds(3))
    }

    // One device cannot take more than `pendingPerHost` unauthenticated sockets, so it cannot lock out the phone's.
    @Test func pendingConnectionsPerHostAreCapped() async throws {
        let (server, port) = try await makeServer(handshakeTimeout: 30, pendingPerHost: 2)
        defer { server.stop() }
        let first = RawClient(port: port)
        let second = RawClient(port: port)
        defer { first.cancel(); second.cancel() }
        try await Task.sleep(for: .milliseconds(300))
        let third = RawClient(port: port)
        defer { third.cancel() }
        try await eventually(timeout: 3) { third.closed }
        #expect(!first.closed)
        #expect(!second.closed)
    }

    @Test func pairedSessionDoesNotCountAsPending() async throws {
        let (server, port) = try await makeServer(handshakeTimeout: 30, pendingPerHost: 1)
        defer { server.stop() }
        let (connection, events) = try await pair(server, port: port)
        connection.send(.ping(id: 1))
        #expect(try await events.nextMessage() == .pong(id: 1))
        let raw = RawClient(port: port)
        defer { raw.cancel() }
        try await Task.sleep(for: .milliseconds(300))
        #expect(!raw.closed)
    }

    // MARK: Sessions and chunks

    @Test func reportsSessionStartAndEnd() async throws {
        let log = Recorded<String>()
        let (server, port) = try await makeServer { server in
            server.onSessionStarted = { _, _ in log.append("start") }
            server.onSessionEnded = { _ in log.append("end") }
        }
        defer { server.stop() }
        let (connection, _) = try await pair(server, port: port)
        try await eventually { log.values == ["start"] }

        connection.cancel()
        try await eventually { log.values == ["start", "end"] }
    }

    @Test func replacedSessionEndsBeforeNewOneStarts() async throws {
        let log = Recorded<String>()
        let (server, port) = try await makeServer { server in
            server.onSessionStarted = { _, _ in log.append("start") }
            server.onSessionEnded = { _ in log.append("end") }
        }
        defer { server.stop() }
        _ = try await pair(server, port: port)
        let (connection, events) = try await reconnect(server, port: port)
        connection.send(.ping(id: 1))
        #expect(try await events.nextMessage() == .pong(id: 1))
        try await eventually { log.values.count >= 3 }
        #expect(log.values == ["start", "end", "start"])
    }

    @Test func deliversChunksFromEstablishedSessions() async throws {
        let chunks = Recorded<(Data, String, UInt64, Data)>()
        let (server, port) = try await makeServer { server in
            server.onChunk = { chunks.append(($0, $1, $2, $3)) }
        }
        defer { server.stop() }
        let (connection, _) = try await pair(server, port: port)

        let id = "0f8e2a4c-1b3d-4e5f-8a9b-0c1d2e3f4a5b"
        connection.send(.chunk(transferId: id, offset: 7, data: Data([1, 2, 3])))
        try await eventually { !chunks.values.isEmpty }
        let chunk = try #require(chunks.values.first)
        #expect(chunk.0 == phone.fingerprint)
        #expect(chunk.1 == id)
        #expect(chunk.2 == 7)
        #expect(chunk.3 == Data([1, 2, 3]))
    }

    @Test func chunkBeforePairingClosesConnection() async throws {
        let chunks = Recorded<String>()
        let (server, port) = try await makeServer { server in
            server.onChunk = { _, id, _, _ in chunks.append(id) }
        }
        defer { server.stop() }
        let uri = server.openPairingWindow(hosts: ["127.0.0.1"])
        let (connection, events) = try TestClient.connect(port: port, identity: phone, pinnedServer: uri.fingerprint)
        #expect(try await events.nextMessage() == .hello(v: 1, name: "Test Mac", platform: .macos))
        connection.send(.hello(v: 1, name: "Pixel", platform: .android))
        connection.send(.chunk(transferId: "0f8e2a4c-1b3d-4e5f-8a9b-0c1d2e3f4a5b", offset: 0, data: Data([1])))

        try await events.expectClosed()
        #expect(chunks.values.isEmpty)
        #expect(server.pairedPeers().isEmpty)
    }

    @Test func sendChunkReachesPhoneAndCompletes() async throws {
        let (server, port) = try await makeServer()
        defer { server.stop() }
        let (_, events) = try await pair(server, port: port)

        let id = "0f8e2a4c-1b3d-4e5f-8a9b-0c1d2e3f4a5b"
        let results = Recorded<Bool>()
        server.sendChunk(transferId: id, offset: 0, data: Data([9]), to: phone.fingerprint) { results.append($0) }
        try await eventually { !results.values.isEmpty }
        #expect(results.values == [true])
        #expect(try await events.nextFrame() == .chunk(transferId: id, offset: 0, data: Data([9])))
    }

    @Test func sendChunkWithoutSessionFails() async throws {
        let (server, _) = try await makeServer()
        defer { server.stop() }
        let results = Recorded<Bool>()
        server.sendChunk(transferId: "0f8e2a4c-1b3d-4e5f-8a9b-0c1d2e3f4a5b", offset: 0, data: Data([9]),
                         to: phone.fingerprint) { results.append($0) }
        try await eventually { !results.values.isEmpty }
        #expect(results.values == [false])
    }

    @Test func peerStoreKeepsCorruptFileAndThrows() throws {
        let url = temporaryDirectory().appending(path: "peers.json")
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let corrupt = Data("{not json".utf8)
        try corrupt.write(to: url)

        #expect(throws: (any Error).self) { try PeerStore(url: url) }
        #expect(try Data(contentsOf: url) == corrupt)
    }

    @Test func peerStoreStartsEmptyWithoutFile() throws {
        #expect(try PeerStore(url: temporaryDirectory().appending(path: "peers.json")).all.isEmpty)
    }

    @Test func localAddressesAreRoutableIPv4() {
        for address in LocalAddresses.ipv4() {
            #expect(PairURI.isIPv4(address))
            #expect(!address.hasPrefix("127."))
            #expect(!address.hasPrefix("169.254."))
        }
    }
}
