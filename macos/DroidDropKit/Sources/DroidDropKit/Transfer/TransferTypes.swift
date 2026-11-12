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

extension TransferStatus {
    /// Whole percent done, rounded down; 0 for an empty file.
    var percent: Int { size == 0 ? 0 : bytesDone * 100 / size }
}

extension FileTransfers.Snapshot {
    /// Menu lines, incoming first. Empty when idle.
    public var menuLines: [String] {
        var lines: [String] = []
        if let incoming { lines.append("Receiving \(incoming.name) — \(incoming.percent)%") }
        if let outgoing {
            let queued = outgoing.queued > 0 ? " (+\(outgoing.queued) queued)" : ""
            lines.append("Sending \(outgoing.name) — \(outgoing.percent)%\(queued)")
        } else if waitingToSend > 0 {
            lines.append("\(waitingToSend) \(waitingToSend == 1 ? "file" : "files") waiting for the phone")
        }
        return lines
    }
}

extension TransferOutcome {
    /// Title and body for a completion notification; `peerName` names the phone.
    public func notificationText(peerName: String) -> (title: String, body: String) {
        switch self {
        case let .received(name, _):
            return ("Received \(name)", "From \(peerName) · Downloads › DroidDrop")
        case let .sent(name):
            return ("Sent \(name)", "To \(peerName)")
        case let .failed(direction, name, reason):
            return ("Could not \(direction == .incoming ? "receive" : "send") \(name)", reason)
        }
    }
}
