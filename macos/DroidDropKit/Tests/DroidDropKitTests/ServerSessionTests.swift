import Foundation
import Testing
@testable import DroidDropKit

@Suite struct ServerSessionTests {
    let serverFP = Data(repeating: 1, count: 32)
    let clientFP = Data(repeating: 2, count: 32)
    let secret = Data(repeating: 3, count: 32)
    let hello = Message.hello(v: 1, name: "Pixel", platform: .android)

    var validProof: String {
        Base64URL.encode(Pairing.clientProof(secret: secret, serverFingerprint: serverFP, clientFingerprint: clientFP))
    }

    @Test func pairsWithValidProof() {
        var session = ServerSession(serverFingerprint: serverFP, clientFingerprint: clientFP, alreadyPaired: false)
        #expect(session.receive(hello, pairingSecret: secret).isEmpty)
        #expect(!session.isPaired)

        let serverProof = Base64URL.encode(Pairing.serverProof(secret: secret, serverFingerprint: serverFP, clientFingerprint: clientFP))
        #expect(session.receive(.pairRequest(proof: validProof), pairingSecret: secret) == [
            .pairPeer(name: "Pixel"),
            .send(.pairResult(ok: true, proof: serverProof, reason: nil)),
        ])
        #expect(session.isPaired)
        #expect(session.receive(.ping(id: 4), pairingSecret: nil) == [.send(.pong(id: 4))])
    }

    @Test func rejectsBadProof() {
        var session = ServerSession(serverFingerprint: serverFP, clientFingerprint: clientFP, alreadyPaired: false)
        _ = session.receive(hello, pairingSecret: secret)
        let wrong = Base64URL.encode(Data(repeating: 9, count: 32))
        #expect(session.receive(.pairRequest(proof: wrong), pairingSecret: secret) == [
            .send(.pairResult(ok: false, proof: nil, reason: "bad_proof")),
            .close(reason: "bad_proof"),
        ])
        #expect(session.receive(.ping(id: 1), pairingSecret: secret).isEmpty)
    }

    @Test func rejectsPairingWhenWindowClosed() {
        var session = ServerSession(serverFingerprint: serverFP, clientFingerprint: clientFP, alreadyPaired: false)
        _ = session.receive(hello, pairingSecret: nil)
        #expect(session.receive(.pairRequest(proof: validProof), pairingSecret: nil) == [
            .send(.pairResult(ok: false, proof: nil, reason: "window_closed")),
            .close(reason: "window_closed"),
        ])
    }

    @Test func unpairedClientMayOnlyPair() {
        var session = ServerSession(serverFingerprint: serverFP, clientFingerprint: clientFP, alreadyPaired: false)
        _ = session.receive(hello, pairingSecret: secret)
        #expect(session.receive(.ping(id: 1), pairingSecret: secret) == [.close(reason: "not paired")])
    }

    @Test func unknownClientPingAfterHelloClosesWithoutPong() {
        var session = ServerSession(serverFingerprint: serverFP, clientFingerprint: clientFP, alreadyPaired: false)
        #expect(session.receive(hello, pairingSecret: nil).isEmpty)
        #expect(session.receive(.ping(id: 0), pairingSecret: nil) == [.close(reason: "not paired")])
        #expect(!session.isPaired)
        #expect(session.receive(.ping(id: 1), pairingSecret: nil).isEmpty)
        #expect(session.receive(.pairRequest(proof: validProof), pairingSecret: secret).isEmpty)
    }

    @Test func unpairedClientUnknownTypeIsProtocolError() {
        var session = ServerSession(serverFingerprint: serverFP, clientFingerprint: clientFP, alreadyPaired: false)
        _ = session.receive(hello, pairingSecret: secret)
        #expect(session.receive(.unknown(type: "future"), pairingSecret: secret) == [.close(reason: "not paired")])
    }

    @Test func pairedClientPingRightAfterHelloGetsPong() {
        var session = ServerSession(serverFingerprint: serverFP, clientFingerprint: clientFP, alreadyPaired: true)
        #expect(session.receive(hello, pairingSecret: nil).isEmpty)
        #expect(session.receive(.ping(id: 0), pairingSecret: nil) == [.send(.pong(id: 0))])
    }

    @Test func requiresHelloFirstAndVersionOne() {
        var early = ServerSession(serverFingerprint: serverFP, clientFingerprint: clientFP, alreadyPaired: true)
        #expect(early.receive(.ping(id: 1), pairingSecret: nil) == [.close(reason: "expected hello")])

        var unknownFirst = ServerSession(serverFingerprint: serverFP, clientFingerprint: clientFP, alreadyPaired: true)
        #expect(unknownFirst.receive(.unknown(type: "future"), pairingSecret: nil) == [.close(reason: "expected hello")])

        var future = ServerSession(serverFingerprint: serverFP, clientFingerprint: clientFP, alreadyPaired: true)
        #expect(future.receive(.hello(v: 2, name: "Pixel", platform: .android), pairingSecret: nil)
            == [.close(reason: "unsupported protocol version 2")])
    }

    @Test func pairedSessionDeliversAppMessagesAndIgnoresUnknown() {
        var session = ServerSession(serverFingerprint: serverFP, clientFingerprint: clientFP, alreadyPaired: true)
        _ = session.receive(hello, pairingSecret: nil)
        #expect(session.isPaired)
        #expect(session.peerName == "Pixel")
        let clip = Message.clip(id: "5b2c8f9e-1d3a-4e7b-9c6d-0a1b2c3d4e5f", mime: "text/plain", text: "hi")
        #expect(session.receive(clip, pairingSecret: nil) == [.deliver(clip)])
        #expect(session.receive(.unknown(type: "future"), pairingSecret: nil).isEmpty)
        #expect(session.receive(.pong(id: 1), pairingSecret: nil).isEmpty)
        #expect(session.receive(hello, pairingSecret: nil) == [.close(reason: "unexpected message")])
    }

    @Test func pairedSessionRejectsRepeatedPairRequest() {
        var session = ServerSession(serverFingerprint: serverFP, clientFingerprint: clientFP, alreadyPaired: true)
        _ = session.receive(hello, pairingSecret: secret)
        #expect(session.receive(.pairRequest(proof: validProof), pairingSecret: secret)
            == [.close(reason: "unexpected message")])
    }

    @Test func pairingWindowExpiresAndIsSingleSecret() {
        final class Clock { var now = Date(timeIntervalSince1970: 1_000) }
        let clock = Clock()
        let window = PairingWindow(now: { clock.now })
        #expect(window.activeSecret == nil)

        let first = window.open()
        #expect(first.count == 32)
        #expect(window.activeSecret == first)

        let second = window.open()
        #expect(second != first)
        #expect(window.activeSecret == second)

        clock.now = clock.now.addingTimeInterval(PairingWindow.lifetime + 1)
        #expect(window.activeSecret == nil)

        _ = window.open()
        window.close()
        #expect(window.activeSecret == nil)
    }
}
