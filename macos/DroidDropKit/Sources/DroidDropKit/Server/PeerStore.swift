import Foundation

public struct Peer: Codable, Equatable, Sendable {
    public var fingerprint: Data
    public var name: String
    public var pairedAt: Date

    public init(fingerprint: Data, name: String, pairedAt: Date) {
        self.fingerprint = fingerprint
        self.name = name
        self.pairedAt = pairedAt
    }
}

/// JSON-file-backed list of paired peers. Not thread-safe; confine to one queue.
public final class PeerStore {
    private let url: URL
    public private(set) var all: [Peer]

    public init(url: URL) {
        self.url = url
        all = (try? JSONDecoder().decode([Peer].self, from: Data(contentsOf: url))) ?? []
    }

    public func contains(_ fingerprint: Data) -> Bool {
        peer(for: fingerprint) != nil
    }

    public func peer(for fingerprint: Data) -> Peer? {
        all.first { $0.fingerprint == fingerprint }
    }

    public func add(_ peer: Peer) throws {
        var updated = all
        updated.removeAll { $0.fingerprint == peer.fingerprint }
        updated.append(peer)
        try save(updated)
    }

    public func remove(_ fingerprint: Data) throws {
        try save(all.filter { $0.fingerprint != fingerprint })
    }

    /// Updates memory only after the file write succeeds, so the two never disagree.
    private func save(_ peers: [Peer]) throws {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try JSONEncoder().encode(peers).write(to: url, options: .atomic)
        all = peers
    }
}
