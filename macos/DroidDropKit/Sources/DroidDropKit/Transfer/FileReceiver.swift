import Foundation

/// Inbound side of one session (SPEC §9). Not thread-safe: confine to one queue. Blocking file I/O.
public final class FileReceiver {
    private struct Active {
        let transferId: String
        let name: String
        let size: Int
        let sha256: String
        let partial: URL
        let handle: FileHandle
        var received: Int
    }

    private let partials: PartialStore
    private let destination: URL
    private var active: Active?

    public init(partials: PartialStore, destination: URL) {
        self.partials = partials
        self.destination = destination
    }

    /// The transfer in progress (sanitized name), or nil.
    public var status: TransferStatus? {
        active.map { TransferStatus(direction: .incoming, name: $0.name, bytesDone: $0.received, size: $0.size) }
    }

    /// `file_accept` at the resume offset, or `file_reject{reason: "cannot_write"}`. Supersedes an active transfer (R2).
    public func offer(transferId: String, name: String, size: Int, sha256: String) -> Message {
        close()
        let fileManager = FileManager.default
        let partial = partials.url(sha256: sha256, size: size)
        do {
            try fileManager.createDirectory(at: partials.directory, withIntermediateDirectories: true)
            _ = partials.resumeOffset(sha256: sha256, size: size)  // drops an oversize partial before it is reopened
            if !fileManager.fileExists(atPath: partial.path), !fileManager.createFile(atPath: partial.path, contents: nil) {
                return .fileReject(transferId: transferId, reason: "cannot_write")
            }
            let handle = try FileHandle(forWritingTo: partial)
            let offset = Int(try handle.seekToEnd())
            active = Active(transferId: transferId, name: FileName.sanitize(name), size: size, sha256: sha256,
                            partial: partial, handle: handle, received: offset)
            return .fileAccept(transferId: transferId, offset: offset)
        } catch {
            return .fileReject(transferId: transferId, reason: "cannot_write")
        }
    }

    /// Nil when written or ignored; else `file_cancel` plus the failure, with the partial discarded (R3).
    public func chunk(transferId: String, offset: UInt64, data: Data) -> (reply: Message, outcome: TransferOutcome)? {
        guard var current = active, current.transferId == transferId else { return nil }
        guard offset == UInt64(current.received) else { return abort(reason: "out_of_order") }
        guard data.count <= current.size - current.received else { return abort(reason: "too_long") }
        do {
            try current.handle.write(contentsOf: data)
        } catch {
            return abort(reason: "write_failed")
        }
        current.received += data.count
        active = current
        return nil
    }

    /// `file_result` plus the outcome; nil when `transferId` is not active.
    public func done(transferId: String) -> (reply: Message, outcome: TransferOutcome)? {
        guard let current = active, current.transferId == transferId else { return nil }
        active = nil
        try? current.handle.close()

        func failure(_ reason: String) -> (reply: Message, outcome: TransferOutcome) {
            try? FileManager.default.removeItem(at: current.partial)
            return (.fileResult(transferId: transferId, ok: false, reason: reason),
                    .failed(.incoming, name: current.name, reason: reason))
        }

        guard current.received == current.size else { return failure("size_mismatch") }
        guard let digest = try? FileDigest.sha256(of: current.partial) else { return failure("save_failed") }
        guard digest.size == current.size else { return failure("size_mismatch") }
        guard digest.hex == current.sha256 else { return failure("hash_mismatch") }

        let fileManager = FileManager.default
        do {
            try fileManager.createDirectory(at: destination, withIntermediateDirectories: true)
            let name = FileName.unique(current.name) { fileManager.fileExists(atPath: destination.appending(path: $0).path) }
            let saved = destination.appending(path: name)
            try fileManager.moveItem(at: current.partial, to: saved)
            return (.fileResult(transferId: transferId, ok: true, reason: nil), .received(name: name, url: saved))
        } catch {
            return failure("save_failed")
        }
    }

    /// The peer's `file_cancel`: stops the transfer, keeps the partial.
    public func cancel(transferId: String) {
        guard active?.transferId == transferId else { return }
        close()
    }

    /// Session ended: closes the partial file and keeps it (R4).
    public func close() {
        try? active?.handle.close()
        active = nil
    }

    /// Ends the active transfer with `file_cancel`, discarding the partial (R3).
    private func abort(reason: String) -> (reply: Message, outcome: TransferOutcome)? {
        guard let current = active else { return nil }
        close()
        try? FileManager.default.removeItem(at: current.partial)
        return (.fileCancel(transferId: current.transferId), .failed(.incoming, name: current.name, reason: reason))
    }
}
