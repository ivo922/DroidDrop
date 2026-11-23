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

extension TransferStatus {
    /// `Sending 3 of 20 · IMG_2041.jpg — 45%` for a batch file (the percent covers the batch), else `Sending a.pdf — 10%`.
    fileprivate func menuLine(verb: String) -> String {
        let queuedText = queued > 0 ? " (+\(queued) queued)" : ""
        if let batch {
            let batchPercent = batch.size == 0 ? 0 : batch.bytesDone * 100 / batch.size
            return "\(verb) \(batch.index + 1) of \(batch.count) · \(name) — \(batchPercent)%\(queuedText)"
        }
        return "\(verb) \(name) — \(percent)%\(queuedText)"
    }
}

extension FileTransfers.Snapshot {
    /// Menu lines, incoming first. Empty when idle.
    public var menuLines: [String] {
        var lines: [String] = []
        if let incoming { lines.append(incoming.menuLine(verb: "Receiving")) }
        if let outgoing {
            lines.append(outgoing.menuLine(verb: "Sending"))
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

extension BatchSummary {
    /// Title and body of the one notification that ends a batch; `peerName` names the phone.
    public func notificationText(peerName: String) -> (title: String, body: String) {
        let files = count == 1 ? "file" : "files"
        if direction == .outgoing {
            let title = done == count ? "Sent \(count) \(files)"
                : done == 0 ? "Could not send \(count) \(files)" : "Sent \(done) of \(count) \(files)"
            let body: String
            if cancelled {
                body = "Cancelled"
            } else if failures.isEmpty && done >= count {
                body = "To \(peerName)"
            } else {
                body = failureList
            }
            return (title, body)
        }
        let title = done == count ? "Received \(count) \(files)"
            : done == 0 ? "Could not receive \(count) \(files)" : "Received \(done) of \(count) \(files)"
        let body: String
        if cancelled && done < count {
            body = "Cancelled on \(peerName)"
        } else if done >= count {
            body = "From \(peerName) · Downloads › DroidDrop"
        } else {
            body = "\(count - done) not received"
        }
        return (title, body)
    }

    private static let maxListed = 5

    /// At most 5 failures, one `<name> (<reason>)` per line, then `and N more` (R7).
    private var failureList: String {
        let total = failures.isEmpty ? count - done : failures.count
        var lines = ["\(total) failed:"]
        lines += failures.prefix(Self.maxListed).map { "\($0.name) (\($0.reason))" }
        if failures.count > Self.maxListed { lines.append("and \(failures.count - Self.maxListed) more") }
        return lines.joined(separator: "\n")
    }
}
