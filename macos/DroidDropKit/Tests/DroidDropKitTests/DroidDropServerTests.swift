import Foundation
import Testing
@testable import DroidDropKit

@Suite struct DroidDropServerTests {
    let phone = try! Identity.generate(commonName: "Phone")

    func makeServer() async throws -> (DroidDropServer, UInt16) {
        let server = DroidDropServer(
            identity: try Identity.generate(commonName: "Mac"),
            peers: PeerStore(url: temporaryDirectory().appending(path: "peers.json")),
            configuration: .init(name: "Test Mac", port: 0, advertise: false)
        )
        return (server, try await server.start())
    }

    func pair(_ server: DroidDropServer, port: UInt16) async throws -> (FramedConnection, EventRecorder) {
        let uri = server.openPairingWindow(hosts: ["127.0.0.1"])
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

        server.unpair(fingerprint: phone.fingerprint)
        try await events.expectClosed()
        #expect(server.pairedPeers().isEmpty)

        let (_, retryEvents) = try TestClient.connect(port: port, identity: phone, pinnedServer: server.identity.fingerprint)
        try await retryEvents.expectClosedWithoutFrames()
    }

    @Test func deliversAppMessages() async throws {
        let (server, port) = try await makeServer()
        defer { server.stop() }
        let received = EventRecorder()
        server.onMessage = { _, message in received.record(.frame(.control(try! MessageCodec.encode(message)))) }
        let (connection, _) = try await pair(server, port: port)

        let clip = Message.clip(id: "5b2c8f9e-1d3a-4e7b-9c6d-0a1b2c3d4e5f", mime: "text/plain", text: "hi")
        connection.send(clip)
        #expect(try await received.nextMessage() == clip)
    }

    @Test func localAddressesAreRoutableIPv4() {
        for address in LocalAddresses.ipv4() {
            #expect(PairURI.isIPv4(address))
            #expect(!address.hasPrefix("127."))
            #expect(!address.hasPrefix("169.254."))
        }
    }
}
