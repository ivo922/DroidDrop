import Foundation

public enum TransferDirection: Equatable, Sendable { case incoming, outgoing }

/// Progress of the transfer in one direction, for the status menu.
public struct TransferStatus: Equatable, Sendable {
    public var direction: TransferDirection
    public var name: String
    public var bytesDone: Int
    public var size: Int
    /// Files waiting behind this one (outgoing only).
    public var queued: Int

    public init(direction: TransferDirection, name: String, bytesDone: Int, size: Int, queued: Int = 0) {
        self.direction = direction
        self.name = name
        self.bytesDone = bytesDone
        self.size = size
        self.queued = queued
    }
}

/// How a transfer ended, for completion notifications.
public enum TransferOutcome: Equatable, Sendable {
    case received(name: String, url: URL)
    case sent(name: String)
    case failed(TransferDirection, name: String, reason: String)
}
