import Foundation
import Testing
@testable import DroidDropKit

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
    }

    @Test func clientRejectsServerWithWrongFingerprint() async throws {
        let server = try LoopbackServer(identity: serverIdentity) { _ in true }
        let port = try await server.start()
        let (_, clientEvents) = try TestClient.connect(port: port, identity: clientIdentity, pinnedServer: Data(repeating: 0, count: 32))

        guard case .closed(let error) = try await clientEvents.next() else { Issue.record("handshake should fail"); return }
        #expect(error != nil)
    }

    @Test func serverRejectsClientItDoesNotAccept() async throws {
        let server = try LoopbackServer(identity: serverIdentity) { _ in false }
        let port = try await server.start()
        let (client, clientEvents) = try TestClient.connect(port: port, identity: clientIdentity, pinnedServer: serverIdentity.fingerprint)
        client.send(.ping(id: 1))

        try await clientEvents.expectClosedWithoutFrames()
    }
}
