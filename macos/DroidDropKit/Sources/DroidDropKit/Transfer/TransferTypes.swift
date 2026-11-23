import Foundation

public enum TransferDirection: Equatable, Sendable { case incoming, outgoing }

/// Where the active file sits in its batch. `index` is 0-based; `bytesDone` never exceeds `size`.
public struct BatchProgress: Equatable, Sendable {
    public var index, count, bytesDone, size: Int

    public init(index: Int, count: Int, bytesDone: Int, size: Int) {
        self.index = index
        self.count = count
        self.bytesDone = bytesDone
        self.size = size
    }
}

public struct BatchFailure: Equatable, Sendable {
    public var name: String
    public var reason: String

    public init(name: String, reason: String) {
        self.name = name
        self.reason = reason
    }
}

/// How a batch ended, for its one summary notification.
public struct BatchSummary: Equatable, Sendable {
    public var direction: TransferDirection
    public var batch: String
    public var count: Int
    public var done: Int
    public var failures: [BatchFailure]
    public var cancelled: Bool
    public var silent: Bool
    /// Receiver: the saved files. Sender: the failed files to retry (never user-cancelled ones).
    public var files: [URL]

    public init(direction: TransferDirection, batch: String, count: Int, done: Int, failures: [BatchFailure] = [],
                cancelled: Bool = false, silent: Bool = false, files: [URL] = []) {
        self.direction = direction
        self.batch = batch
        self.count = count
        self.done = done
        self.failures = failures
        self.cancelled = cancelled
        self.silent = silent
        self.files = files
    }
}

/// Progress of the transfer in one direction, for the status menu.
public struct TransferStatus: Equatable, Sendable {
    public var direction: TransferDirection
    public var name: String
    public var bytesDone: Int
    public var size: Int
    /// Files queued after this file's batch, or after this file when unbatched (outgoing only).
    public var queued: Int
    /// Set while the file belongs to a batch.
    public var batch: BatchProgress?

    public init(direction: TransferDirection, name: String, bytesDone: Int, size: Int, queued: Int = 0,
                batch: BatchProgress? = nil) {
        self.direction = direction
        self.name = name
        self.bytesDone = bytesDone
        self.size = size
        self.queued = queued
        self.batch = batch
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
