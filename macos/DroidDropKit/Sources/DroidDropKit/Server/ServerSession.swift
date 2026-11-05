import Foundation

public enum ServerAction: Equatable {
    case send(Message)
    case pairPeer(name: String)
    case close(reason: String)
    case deliver(Message)
}

/// Protocol rules of one server-side session (SPEC §5, §6). No I/O.
///
/// An unpaired client may only send `pair_request` (§5 rule 3), so unknown message types close
/// an unpaired session; they are ignored only once paired (§4). After `.pairPeer` the caller must
/// store the peer and close the pairing window.
public struct ServerSession {
    private enum Phase { case awaitingHello, unpaired, paired, closed }

    public let serverFingerprint: Data
    public let clientFingerprint: Data
    public private(set) var peerName: String?
    private let alreadyPaired: Bool
    private var phase = Phase.awaitingHello

    public init(serverFingerprint: Data, clientFingerprint: Data, alreadyPaired: Bool) {
        self.serverFingerprint = serverFingerprint
        self.clientFingerprint = clientFingerprint
        self.alreadyPaired = alreadyPaired
    }

    public var isPaired: Bool { phase == .paired }

    public mutating func receive(_ message: Message, pairingSecret: Data?) -> [ServerAction] {
        switch (phase, message) {
        case (.closed, _):
            return []

        case let (.awaitingHello, .hello(v, name, _)):
            guard v == 1 else { return close("unsupported protocol version \(v)") }
            peerName = name
            phase = alreadyPaired ? .paired : .unpaired
            return []
        case (.awaitingHello, _):
            return close("expected hello")

        case let (.unpaired, .pairRequest(proof)):
            guard let secret = pairingSecret else { return reject("window_closed") }
            guard let proofData = Base64URL.decode32(proof),
                  Pairing.isValidClientProof(proofData, secret: secret, serverFingerprint: serverFingerprint,
                                             clientFingerprint: clientFingerprint) else { return reject("bad_proof") }
            phase = .paired
            let serverProof = Pairing.serverProof(secret: secret, serverFingerprint: serverFingerprint, clientFingerprint: clientFingerprint)
            return [
                .pairPeer(name: peerName ?? "Android"),
                .send(.pairResult(ok: true, proof: Base64URL.encode(serverProof), reason: nil)),
            ]
        case (.unpaired, _):
            return close("not paired")

        case let (.paired, .ping(id)):
            return [.send(.pong(id: id))]
        case (.paired, .pong), (.paired, .unknown):
            return []
        case (.paired, .hello), (.paired, .pairRequest), (.paired, .pairResult):
            return close("unexpected message")
        case (.paired, _):
            return [.deliver(message)]
        }
    }

    private mutating func close(_ reason: String) -> [ServerAction] {
        phase = .closed
        return [.close(reason: reason)]
    }

    private mutating func reject(_ reason: String) -> [ServerAction] {
        phase = .closed
        return [.send(.pairResult(ok: false, proof: nil, reason: reason)), .close(reason: reason)]
    }
}
