import Foundation
import Network
import Testing
@testable import DroidDropKit

private final class ConnectionBag: @unchecked Sendable {
    var connections: [NWConnection] = []
}

private func isTLSError(_ error: Error?) -> Bool {
    if case .tls = error as? NWError { return true }
    return false
}

@Suite struct TLSTests {
    let serverIdentity = try! Identity.generate(commonName: "Server")
    let clientIdentity = try! Identity.generate(commonName: "Client")

    @Test func pinnedPeersExchangeFrames() async throws {
        let server = try LoopbackServer(identity: serverIdentity) { [clientIdentity] in $0 == clientIdentity.fingerprint }
        let port = try await server.start()
        let (client, clientEvents) = try TestClient.connect(port: port, identity: clientIdentity, pinnedServer: serverIdentity.fingerprint)

        guard case .ready = try await clientEvents.next() else { Issue.record("client never became ready"); return }
        client.send(.ping(id: 1))
        #expect(try await server.events.nextMessage() == .ping(id: 1))
        #expect(server.peerFingerprints == [clientIdentity.fingerprint])
        client.cancel()
        withExtendedLifetime(server) {}
    }

    @Test func clientRejectsServerWithWrongFingerprint() async throws {
        let server = try LoopbackServer(identity: serverIdentity) { _ in true }
        let port = try await server.start()
        let (_, clientEvents) = try TestClient.connect(port: port, identity: clientIdentity, pinnedServer: Data(repeating: 0, count: 32))

        guard case .closed(let error) = try await clientEvents.next() else { Issue.record("handshake should fail"); return }
        #expect(isTLSError(error), "expected TLS error, got \(String(describing: error))")
        withExtendedLifetime(server) {}
    }

    @Test func serverRejectsClientItDoesNotAccept() async throws {
        let server = try LoopbackServer(identity: serverIdentity) { _ in false }
        let port = try await server.start()
        let (client, clientEvents) = try TestClient.connect(port: port, identity: clientIdentity, pinnedServer: serverIdentity.fingerprint)
        client.send(.ping(id: 1))

        guard case .closed(let serverError) = try await server.events.next() else { Issue.record("server must never become ready"); return }
        #expect(isTLSError(serverError), "expected TLS error, got \(String(describing: serverError))")
        #expect(server.peerFingerprints.isEmpty)
        try await clientEvents.expectClosedWithoutFrames()
        withExtendedLifetime(server) {}
    }

    @Test func localCancelReportsClosedWithoutError() async throws {
        let server = try LoopbackServer(identity: serverIdentity) { _ in true }
        let port = try await server.start()
        let (client, clientEvents) = try TestClient.connect(port: port, identity: clientIdentity, pinnedServer: serverIdentity.fingerprint)

        guard case .ready = try await clientEvents.next() else { Issue.record("client never became ready"); return }
        client.cancel()
        guard case .closed(let error) = try await clientEvents.next() else { Issue.record("expected closed"); return }
        #expect(error == nil, "local cancel reported \(String(describing: error))")
        withExtendedLifetime(server) {}
    }

    @Test func closeFlushesIsIdempotentAndDropsLaterSends() async throws {
        let server = try LoopbackServer(identity: serverIdentity) { _ in true }
        let port = try await server.start()
        let (client, clientEvents) = try TestClient.connect(port: port, identity: clientIdentity, pinnedServer: serverIdentity.fingerprint)

        guard case .ready = try await clientEvents.next() else { Issue.record("client never became ready"); return }
        client.send(.ping(id: 1))
        client.close()
        client.close()
        client.send(.ping(id: 2))

        guard case .closed(let error) = try await clientEvents.next() else { Issue.record("expected closed"); return }
        #expect(error == nil, "local close reported \(String(describing: error))")
        #expect(try await server.events.nextMessage() == .ping(id: 1))
        try await server.events.expectClosedWithoutFrames()
        withExtendedLifetime(server) {}
    }

    @Test func closeCancelsAfterLingerWhenPeerNeverCloses() async throws {
        let queue = DispatchQueue(label: "test.silent.server")
        let listener = try NWListener(using: TLSParameters.make(identity: try serverIdentity.secIdentity(), queue: queue) { _ in true }, on: .any)
        let accepted = ConnectionBag()
        listener.newConnectionHandler = { connection in
            accepted.connections.append(connection)
            connection.start(queue: queue)
        }
        let port = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<UInt16, Error>) in
            listener.stateUpdateHandler = { state in
                if case .ready = state { continuation.resume(returning: listener.port!.rawValue) }
            }
            listener.start(queue: queue)
        }
        defer {
            listener.cancel()
            queue.sync { accepted.connections.forEach { $0.cancel() } }
        }
        let (client, clientEvents) = try TestClient.connect(port: port, identity: clientIdentity, pinnedServer: serverIdentity.fingerprint,
                                                            lingerTimeout: 0.2)

        guard case .ready = try await clientEvents.next() else { Issue.record("client never became ready"); return }
        client.close()
        guard case .closed(let error) = try await clientEvents.next(timeout: 2) else { Issue.record("expected closed"); return }
        #expect(error == nil)
    }

    @Test func noFramesDeliveredAfterHandlerCancels() async throws {
        let server = try LoopbackServer(identity: serverIdentity) { _ in true }
        let port = try await server.start()
        let (_, clientEvents) = try TestClient.connect(port: port, identity: clientIdentity, pinnedServer: serverIdentity.fingerprint) { framed, event in
            if case .frame = event { framed.cancel() }
        }

        guard case .ready = try await server.events.next() else { Issue.record("server never became ready"); return }
        let first = FrameEncoder.encode(.control(try MessageCodec.encode(.ping(id: 1))))
        let second = FrameEncoder.encode(.control(try MessageCodec.encode(.ping(id: 2))))
        server.accepted[0].connection.send(content: first + second, completion: .idempotent)

        guard case .ready = try await clientEvents.next() else { Issue.record("client never became ready"); return }
        guard case .frame = try await clientEvents.next() else { Issue.record("expected first frame"); return }
        guard case .closed(let error) = try await clientEvents.next() else { Issue.record("frame delivered after cancel"); return }
        #expect(error == nil)
        withExtendedLifetime(server) {}
    }
}
