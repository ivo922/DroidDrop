import Foundation
import UniformTypeIdentifiers

/// The session a FileSender writes to. Called on the sender's queue. Implementations must:
/// 1. Keep `send` and `sendChunk` in one FIFO order: `file_done` follows the last chunk while chunk completions
///    are still outstanding, so it must never overtake an earlier chunk.
/// 2. Make sure the sender's `disconnected()` is called (or already was) whenever a completion reports false;
///    the sender ignores false completions and relies on that call.
/// Implementations should not hold the sender strongly (the sender holds its link).
public protocol TransferLink: AnyObject {
    func send(_ message: Message)
    /// `completion(true)` once the frame is handed to the network, false if the session is gone. Any thread.
    func sendChunk(transferId: String, offset: UInt64, data: Data, completion: @escaping @Sendable (Bool) -> Void)
}

/// Outbound queue to one peer (SPEC §9). Every method must be called on `queue`; callbacks run on it.
/// All state is confined to `queue`. Blocking file reads happen on it; hashing runs on a global utility queue.
public final class FileSender: @unchecked Sendable {
    public static let chunkSize = FrameLimits.maxChunkData
    /// Chunks handed to the link whose completion has not arrived.
    public static let window = 4

    public var onStatus: ((TransferStatus?) -> Void)?
    public var onOutcome: ((TransferOutcome) -> Void)?

    private struct Item {
        let url: URL
        /// Computed once, kept across re-offers.
        var digest: (hex: String, size: Int)?
        var name: String { url.lastPathComponent }
    }

    private enum Phase {
        case hashing
        case offered(String)
        case sending(String)
        case awaitingResult(String)
    }

    /// The head of the queue while a session is up.
    private struct Active {
        var phase: Phase
        var size = 0
        var handle: FileHandle?
        var nextOffset = 0
        var acked = 0
        var inFlight = 0

        var transferId: String? {
            switch phase {
            case .hashing: nil
            case .offered(let id), .sending(let id), .awaitingResult(let id): id
            }
        }
    }

    private let queue: DispatchQueue
    private let makeID: () -> String
    private var items: [Item] = []
    private var link: TransferLink?
    private var active: Active?
    /// Bumped whenever the session or the active file changes; stale digests and completions carry an older value.
    private var generation = 0

    public init(queue: DispatchQueue, makeID: @escaping () -> String = { UUID().uuidString.lowercased() }) {
        self.queue = queue
        self.makeID = makeID
    }

    /// Files queued, including the active one.
    public var queuedCount: Int { items.count }

    /// Queues regular files in order; a directory is reported failed right away (R11).
    public func enqueue(_ urls: [URL]) {
        for url in urls {
            if (try? url.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory == true {
                onOutcome?(.failed(.outgoing, name: url.lastPathComponent, reason: "folders are not supported"))
            } else {
                items.append(Item(url: url))
            }
        }
        startHeadIfIdle()
    }

    /// Session established: offers the head of the queue (again, after a reconnect).
    public func connected(_ link: TransferLink) {
        endSession()
        self.link = link
        startHeadIfIdle()
    }

    /// Session gone: stops sending; the head stays queued (R4).
    public func disconnected() {
        endSession()
    }

    /// Handles file_accept / file_reject / file_result / file_cancel for the active id; ignores everything else.
    public func receive(_ message: Message) {
        guard let current = active else { return }
        switch (message, current.phase) {
        case let (.fileAccept(id, offset), .offered(offered)) where id == offered:
            accepted(id: id, offset: offset)
        case let (.fileReject(id, reason), .offered(offered)) where id == offered:
            finishHead(.failed(.outgoing, name: items[0].name, reason: reason))
        case let (.fileCancel(id), _) where current.transferId == id:
            finishHead(.failed(.outgoing, name: items[0].name, reason: "cancelled by receiver"))
        case let (.fileResult(id, ok, reason), .awaitingResult(waiting)) where id == waiting:
            finishHead(ok ? .sent(name: items[0].name)
                          : .failed(.outgoing, name: items[0].name, reason: reason ?? "failed"))
        case let (.fileResult(id, false, reason), .sending(sending)) where id == sending:
            finishHead(.failed(.outgoing, name: items[0].name, reason: reason ?? "failed"))
        default:
            break
        }
    }

    /// `file_cancel` for the active file (reported failed, "cancelled"), then empties the queue (R10).
    public func cancelAll() {
        let name = items.first?.name
        if let id = active?.transferId { link?.send(.fileCancel(transferId: id)) }
        let wasActive = active != nil
        closeActive()
        generation += 1
        items.removeAll()
        if wasActive, let name {
            onOutcome?(.failed(.outgoing, name: name, reason: "cancelled"))
            onStatus?(nil)
        }
    }

    // MARK: - Steps

    private func startHeadIfIdle() {
        guard link != nil, active == nil, let head = items.first else { return }
        generation += 1
        active = Active(phase: .hashing)
        let size = head.digest?.size ?? (try? head.url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        active?.size = size
        onStatus?(status(name: head.name, bytesDone: 0, size: size))
        if head.digest != nil {
            offerHead()
            return
        }
        let url = head.url
        let queue = queue
        let token = generation
        DispatchQueue.global(qos: .utility).async { [weak self] in
            let result = Result { try FileDigest.sha256(of: url) }
            queue.async { self?.digested(result, token: token) }
        }
    }

    private func digested(_ result: Result<(hex: String, size: Int), Error>, token: Int) {
        guard token == generation, !items.isEmpty else { return }
        switch result {
        case .success(let digest):
            items[0].digest = digest
            active?.size = digest.size
            offerHead()
        case .failure(let error):
            finishHead(.failed(.outgoing, name: items[0].name, reason: error.localizedDescription))
        }
    }

    private func offerHead() {
        guard let link, let digest = items[0].digest else { return }
        let id = makeID()
        active?.phase = .offered(id)
        active?.size = digest.size
        let mime = UTType(filenameExtension: items[0].url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
        link.send(.fileOffer(transferId: id, name: items[0].name, size: digest.size, sha256: digest.hex, mime: mime))
        onStatus?(status(name: items[0].name, bytesDone: 0, size: digest.size))
    }

    private func accepted(id: String, offset: Int) {
        guard let size = active?.size, offset >= 0, offset <= size else {
            abortHead(id: id)
            return
        }
        do {
            let handle = try FileHandle(forReadingFrom: items[0].url)
            try handle.seek(toOffset: UInt64(offset))
            active?.handle = handle
        } catch {
            abortHead(id: id)
            return
        }
        active?.phase = .sending(id)
        active?.nextOffset = offset
        active?.acked = offset
        onStatus?(status(name: items[0].name, bytesDone: offset, size: size))
        pump()
    }

    /// Hands chunks to the link while the window has room, then `file_done` after the last one.
    private func pump() {
        guard let link, var current = active, case .sending(let id) = current.phase, let handle = current.handle else { return }
        let generation = generation
        let queue = queue
        while current.inFlight < Self.window && current.nextOffset < current.size {
            let expected = min(Self.chunkSize, current.size - current.nextOffset)
            guard let data = try? handle.read(upToCount: expected), data.count == expected else {
                active = current
                abortHead(id: id)
                return
            }
            let offset = current.nextOffset
            current.nextOffset += expected
            current.inFlight += 1
            link.sendChunk(transferId: id, offset: UInt64(offset), data: data) { [weak self] handed in
                queue.async { self?.chunkHandedOver(count: expected, ok: handed, token: generation) }
            }
        }
        if current.nextOffset == current.size {
            current.phase = .awaitingResult(id)
            active = current
            link.send(.fileDone(transferId: id))
        } else {
            active = current
        }
    }

    private func chunkHandedOver(count: Int, ok: Bool, token: Int) {
        // A false completion means the session is gone; `disconnected()` follows.
        guard token == generation, ok, var current = active else { return }
        current.inFlight -= 1
        current.acked += count
        active = current
        onStatus?(status(name: items[0].name, bytesDone: current.acked, size: current.size))
        pump()
    }

    /// Read failure or a short read: tell the peer and report the file failed.
    private func abortHead(id: String) {
        link?.send(.fileCancel(transferId: id))
        finishHead(.failed(.outgoing, name: items[0].name, reason: "file changed or unreadable"))
    }

    /// Ends the head file with `outcome`, then starts the next one if a session is up.
    private func finishHead(_ outcome: TransferOutcome) {
        closeActive()
        generation += 1
        items.removeFirst()
        onOutcome?(outcome)
        onStatus?(nil)
        startHeadIfIdle()
    }

    private func endSession() {
        let wasActive = active != nil
        closeActive()
        generation += 1
        link = nil
        if wasActive { onStatus?(nil) }
    }

    private func closeActive() {
        try? active?.handle?.close()
        active = nil
    }

    private func status(name: String, bytesDone: Int, size: Int) -> TransferStatus {
        TransferStatus(direction: .outgoing, name: name, bytesDone: bytesDone, size: size, queued: items.count - 1)
    }
}
