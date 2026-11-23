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
    /// Failure reason when the file cannot be hashed or read, or changed while sending. Same text as the Android sender.
    public static let unreadable = "file changed or unreadable"

    public var onStatus: ((TransferStatus?) -> Void)?
    /// Outcomes of unbatched files only.
    public var onOutcome: ((TransferOutcome) -> Void)?
    /// Outcomes of batch files, with the file's batch, INSTEAD of `onOutcome` (never both).
    public var onBatchFileOutcome: ((TransferOutcome, BatchInfo) -> Void)?
    /// One summary per batch that had an outcome (a cancelled batch: one that had a file offered). Never for a silent cancel.
    public var onBatchSummary: ((BatchSummary) -> Void)?

    /// Per-batch tally, shared by the batch's items.
    private final class Tracker {
        let id: String
        let count: Int
        var offered = false  // a file_offer of the batch was written
        var resolved = 0     // files that reached an outcome or were dropped
        var reported = 0     // files that reached an outcome
        var sent = 0
        var cancelled = false
        var silent = false   // dropped by an unpair: no summary, no batch_end
        var failures: [BatchFailure] = []
        var failedFiles: [URL] = []
        init(id: String, count: Int) { self.id = id; self.count = count }
    }

    /// A finished batch: its summary, if it earns one.
    private struct Completion { let summary: BatchSummary? }

    private struct Item {
        let url: URL
        /// Tells a finished hash which item it belongs to, across sessions and requeues.
        let serial: Int
        /// Computed once, kept across re-offers.
        var digest: (hex: String, size: Int)?
        /// Set for files enqueued together with others; kept across re-offers.
        var batch: BatchInfo?
        var tracker: Tracker?
        /// The size at enqueue time: this file's share of the batch progress is clamped to it.
        var enqueuedSize = 0
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
    private let hash: @Sendable (URL) throws -> (hex: String, size: Int)
    private var nextSerial = 0
    /// The item whose hash is running. One per item, whatever the sessions do meanwhile.
    private var hashing: Int?
    private var items: [Item] = []
    private var link: TransferLink?
    private var active: Active?
    /// Bumped whenever the session or the active file changes; stale chunk completions carry an older value.
    private var generation = 0
    /// `batch_end`s waiting for a session. Written before any `file_offer`.
    private var pendingEnds: [Message] = []

    public init(queue: DispatchQueue, makeID: @escaping () -> String = { UUID().uuidString.lowercased() },
                hash: @escaping @Sendable (URL) throws -> (hex: String, size: Int) = { try FileDigest.sha256(of: $0) }) {
        self.queue = queue
        self.makeID = makeID
        self.hash = hash
    }

    /// Files queued, including the active one.
    public var queuedCount: Int { items.count }

    /// Queues regular files in order; a directory is reported failed right away (R11). Two or more regular files
    /// form a batch (SPEC 9.2); one is sent unbatched.
    public func enqueue(_ urls: [URL]) {
        var regular: [URL] = []
        for url in urls {
            if (try? url.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory == true {
                onOutcome?(.failed(.outgoing, name: url.lastPathComponent, reason: "folders are not supported"))
            } else {
                regular.append(url)
            }
        }
        if regular.count >= 2 {
            let sizes = regular.map { (try? $0.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0 }
            let tracker = Tracker(id: makeID(), count: regular.count)
            let total = sizes.reduce(0, +)
            var before = 0
            for (index, url) in regular.enumerated() {
                nextSerial += 1
                var item = Item(url: url, serial: nextSerial)
                item.batch = BatchInfo(id: tracker.id, index: index, count: regular.count, size: total, before: before)
                item.tracker = tracker
                item.enqueuedSize = sizes[index]
                before += sizes[index]
                items.append(item)
            }
        } else {
            for url in regular {
                nextSerial += 1
                items.append(Item(url: url, serial: nextSerial))
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
    /// A batch that had a file offered ends with a cancelled `batch_end` (queued if no session is up) and summary.
    /// With `silent` (unpair) nothing is reported for batches and pending `batch_end`s are dropped.
    public func cancelAll(silent: Bool = false) {
        let current = active != nil ? items.first : nil
        if let id = active?.transferId { link?.send(.fileCancel(transferId: id)) }
        closeActive()
        generation += 1
        let dropped = items
        items.removeAll()
        if silent { pendingEnds.removeAll() }
        for item in dropped {
            item.tracker?.cancelled = true
            if silent { item.tracker?.silent = true }
        }
        var completions: [BatchSummary?] = []
        var currentOutcome: TransferOutcome?
        for item in dropped {
            if let current, item.serial == current.serial {
                currentOutcome = .failed(.outgoing, name: item.name, reason: "cancelled")
                if let done = resolve(item, currentOutcome, userCancelled: true) { completions.append(done.summary) }
            } else if let done = resolve(item, nil, userCancelled: true) {
                completions.append(done.summary)
            }
        }
        flushEnds()
        if let current, let currentOutcome {
            onStatus?(nil)
            report(currentOutcome, for: current)
        }
        for case let summary? in completions { onBatchSummary?(summary) }
    }

    // MARK: - Steps

    private func startHeadIfIdle() {
        guard link != nil, active == nil else { return }
        flushEnds()  // a batch_end always precedes the next file_offer, even of another batch
        guard let head = items.first else { return }
        generation += 1
        active = Active(phase: .hashing)
        let size = head.digest?.size ?? (try? head.url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        active?.size = size
        onStatus?(status(head, bytesDone: 0, size: size))
        if head.digest != nil {
            offerHead()
            return
        }
        // Still hashing from an earlier session: digested() offers it on this one.
        guard hashing != head.serial else { return }
        hashing = head.serial
        let url = head.url
        let serial = head.serial
        let queue = queue
        let hash = hash
        DispatchQueue.global(qos: .utility).async { [weak self] in
            let result = Result { try hash(url) }
            queue.async { self?.digested(result, serial: serial) }
        }
    }

    /// A digest outlives the session it started in, so a reconnect never re-reads the file.
    private func digested(_ result: Result<(hex: String, size: Int), Error>, serial: Int) {
        if hashing == serial { hashing = nil }
        guard items.first?.serial == serial else { return }  // cancelled or finished meanwhile
        let waiting: Bool
        if case .hashing? = active?.phase { waiting = link != nil } else { waiting = false }
        switch result {
        case .success(let digest):
            items[0].digest = digest
            guard waiting else { return }  // kept for the next session
            active?.size = digest.size
            offerHead()
        case .failure:
            guard waiting else { return }  // the next session hashes again
            finishHead(.failed(.outgoing, name: items[0].name, reason: Self.unreadable))
        }
    }

    private func offerHead() {
        guard let link, let digest = items[0].digest else { return }
        let id = makeID()
        active?.phase = .offered(id)
        active?.size = digest.size
        let mime = UTType(filenameExtension: items[0].url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
        items[0].tracker?.offered = true  // before the write: an unknown batch_end is harmless to the receiver
        link.send(.fileOffer(transferId: id, name: items[0].name, size: digest.size, sha256: digest.hex, mime: mime,
                             batch: items[0].batch))
        onStatus?(status(items[0], bytesDone: 0, size: digest.size))
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
        onStatus?(status(items[0], bytesDone: offset, size: size))
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
        onStatus?(status(items[0], bytesDone: current.acked, size: current.size))
        pump()
    }

    /// Read failure or a short read: tell the peer and report the file failed.
    private func abortHead(id: String) {
        link?.send(.fileCancel(transferId: id))
        finishHead(.failed(.outgoing, name: items[0].name, reason: Self.unreadable))
    }

    /// Ends the head file with `outcome`, then starts the next one if a session is up.
    private func finishHead(_ outcome: TransferOutcome) {
        closeActive()
        generation += 1
        let item = items.removeFirst()
        let done = resolve(item, outcome, userCancelled: false)
        flushEnds()  // before the last outcome, so a caller that stops after it has the batch_end on the wire
        onStatus?(nil)  // before the outcome: an outcome handler may enqueue, and its status must win
        report(outcome, for: item)
        if let summary = done?.summary { onBatchSummary?(summary) }
        startHeadIfIdle()
    }

    private func report(_ outcome: TransferOutcome, for item: Item) {
        if let batch = item.batch { onBatchFileOutcome?(outcome, batch) } else { onOutcome?(outcome) }
    }

    /// Writes the waiting `batch_end`s, in order, if a session is up.
    /// Known difference from Kotlin: `TransferLink.send` cannot report failure, so a `batch_end` written into a link
    /// that is dying is lost (Kotlin keeps the entry for the next session). The receiver then falls back to its
    /// silent summary.
    private func flushEnds() {
        guard let link, !pendingEnds.isEmpty else { return }
        let ends = pendingEnds
        pendingEnds.removeAll()
        for end in ends { link.send(end) }
    }

    /// Counts `item` as done in its batch (`outcome` nil: dropped without one). The batch's last file completes it:
    /// queues the `batch_end` and builds the summary.
    private func resolve(_ item: Item, _ outcome: TransferOutcome?, userCancelled: Bool) -> Completion? {
        guard let tracker = item.tracker else { return nil }
        tracker.resolved += 1
        if outcome != nil { tracker.reported += 1 }
        switch outcome {
        case .sent?:
            tracker.sent += 1
        case let .failed(_, name, reason)?where !userCancelled:
            tracker.failures.append(BatchFailure(name: name, reason: reason))
            tracker.failedFiles.append(item.url)
        default:
            break
        }
        guard tracker.resolved >= tracker.count else { return nil }
        if tracker.silent { return Completion(summary: nil) }
        if tracker.offered {
            pendingEnds.append(.batchEnd(batch: tracker.id, count: tracker.count, sent: tracker.sent, cancelled: tracker.cancelled))
        }
        let hasSummary = tracker.cancelled ? tracker.offered : tracker.reported > 0
        let summary = hasSummary
            ? BatchSummary(direction: .outgoing, batch: tracker.id, count: tracker.count, done: tracker.sent,
                           failures: tracker.failures, cancelled: tracker.cancelled,
                           files: tracker.cancelled ? [] : tracker.failedFiles)
            : nil
        return Completion(summary: summary)
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

    private func status(_ item: Item, bytesDone: Int, size: Int) -> TransferStatus {
        // Files queued after the item's batch (after the item itself when unbatched).
        let queued = items.dropFirst().filter { item.tracker == nil || $0.tracker !== item.tracker }.count
        var batch: BatchProgress?
        if let info = item.batch {
            let own = min(max(bytesDone, 0), item.enqueuedSize)
            batch = BatchProgress(index: info.index, count: info.count, bytesDone: min(info.before + own, info.size), size: info.size)
        }
        return TransferStatus(direction: .outgoing, name: item.name, bytesDone: bytesDone, size: size, queued: queued, batch: batch)
    }
}
