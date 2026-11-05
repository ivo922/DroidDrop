import Foundation

/// One-time pairing secret (SPEC §6.2). Not thread-safe; confine to one queue.
public final class PairingWindow {
    public static let lifetime: TimeInterval = 120

    private let now: () -> Date
    private var secret: Data?
    private var expiresAt = Date.distantPast

    public init(now: @escaping () -> Date = Date.init) {
        self.now = now
    }

    /// Opens a new window, invalidating any previous secret.
    public func open() -> Data {
        let secret = Pairing.newSecret()
        self.secret = secret
        expiresAt = now().addingTimeInterval(Self.lifetime)
        return secret
    }

    public var activeSecret: Data? {
        guard let secret, now() < expiresAt else { return nil }
        return secret
    }

    public func close() {
        secret = nil
    }
}
