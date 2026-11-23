import Foundation

/// Wires FileReceivers (one per session) and FileSenders (one per peer) to a server. All work on `queue` (R13).
///
/// Server callbacks hop to `queue` in the order the server raised them, so each message and chunk reaches the
/// receiver of the session it arrived on, and a session's end is handled before the next one's start.
/// Replies and outgoing frames go back through the server to the session they belong to, never to a successor.
public final class FileTransfers: @unchecked Sendable {
    public struct Snapshot: Equatable, Sendable {
        public var incoming: TransferStatus?
        public var outgoing: TransferStatus?
        /// Queued outgoing files with no active transfer (phone disconnected).
        public var waitingToSend: Int

        public init(incoming: TransferStatus? = nil, outgoing: TransferStatus? = nil, waitingToSend: Int = 0) {
            self.incoming = incoming
            self.outgoing = outgoing
            self.waitingToSend = waitingToSend
        }
    }

    public static let statusInterval: TimeInterval = 0.25

    public let queue = DispatchQueue(label: "dev.ivo.droiddrop.transfers")
    /// On `queue`; at most every `statusInterval`, except that starts and ends are always reported.
    public var onSnapshot: ((Snapshot) -> Void)?
    /// On `queue`; peer fingerprint and outcome.
    /// Unbatched files only: a batch file's outcome feeds its batch summary instead (R3).
    public var onOutcome: ((Data, TransferOutcome) -> Void)?
    /// On `queue`; peer fingerprint and the one summary that ends a batch, in either direction.
    public var onBatchSummary: ((Data, BatchSummary) -> Void)?

    private weak var server: DroidDropServer?
    private let partials: PartialStore
    private let destination: URL
    private var receivers: [Data: FileReceiver] = [:]
    private var senders: [Data: FileSender] = [:]
    /// Each peer's open incoming batch; kept across session replacement.
    private var incomingBatches: [Data: IncomingBatches] = [:]
    /// The link of each peer's established session; none while the peer is disconnected.
    private var links: [Data: Link] = [:]
    private var outgoing: [Data: TransferStatus] = [:]
    private var reported = Snapshot()
    private var reportedAt = DispatchTime.now()
    private var flushScheduled = false

    /// Sets server.onSessionStarted/onSessionEnded/onChunk (not onMessage) and prunes partials (now and at each session start).
    /// Holds the server weakly.
    /// Call before the server starts.
    public init(server: DroidDropServer, partials: PartialStore, destination: URL) {
        self.server = server
        self.partials = partials
        self.destination = destination
        server.onSessionStarted = { [weak self] fingerprint, session in
            self?.queue.async { self?.sessionStarted(fingerprint, session) }
        }
        server.onSessionEnded = { [weak self] fingerprint in
            self?.queue.async { self?.sessionEnded(fingerprint) }
        }
        server.onChunk = { [weak self] fingerprint, transferId, offset, data in
            self?.queue.async { self?.chunk(transferId: transferId, offset: offset, data: data, from: fingerprint) }
        }
        queue.async { partials.prune() }
    }

    /// Route every server.onMessage here. Any thread.
    public func receive(_ message: Message, from fingerprint: Data) {
        queue.async { [weak self] in self?.route(message, from: fingerprint) }
    }

    /// Queues files for the peer; they wait while it is disconnected (R4, R6). Any thread.
    public func send(_ urls: [URL], to fingerprint: Data) {
        queue.async { [weak self] in
            guard let self else { return }
            self.sender(for: fingerprint).enqueue(urls)
            self.publish()
        }
    }

    /// Cancels the active outgoing file and drops the rest of the peer's queue (R10). Any thread.
    /// `silent` (unpair) posts no summaries and also drops the peer's open incoming batch.
    public func cancelSending(to fingerprint: Data, silent: Bool = false) {
        queue.async { [weak self] in
            guard let self else { return }
            self.senders[fingerprint]?.cancelAll(silent: silent)
            if silent { self.incomingBatches[fingerprint]?.clear() }
            self.publish()
        }
    }

    /// `cancelSending(to:)` for every peer with a sender, including fingerprints that are no longer paired. Any thread.
    public func cancelAllSending() {
        queue.async { [weak self] in
            guard let self else { return }
            for sender in self.senders.values { sender.cancelAll() }
            self.publish()
        }
    }

    // MARK: - On queue

    private func sessionStarted(_ fingerprint: Data, _ session: DroidDropServer.SessionID) {
        // Also here, not only at launch: a Launch-at-Login app runs for weeks. Active partials have a fresh mtime.
        partials.prune()
        receivers[fingerprint]?.close()
        receivers[fingerprint] = FileReceiver(partials: partials, destination: destination)
        let link = Link(fingerprint: fingerprint, session: session, server: server, owner: self)
        links[fingerprint] = link
        senders[fingerprint]?.connected(link)
        publish()
    }

    private func sessionEnded(_ fingerprint: Data) {
        receivers.removeValue(forKey: fingerprint)?.close()  // keeps the partial for resume (R4)
        disconnectSender(fingerprint)
    }

    /// The sender keeps its head file and re-offers it on the next session (R4).
    private func disconnectSender(_ fingerprint: Data) {
        links[fingerprint] = nil
        senders[fingerprint]?.disconnected()
        publish()
    }

    /// A chunk send failed, so the link's session is gone: disconnect its sender unless a newer session took over.
    private func linkFailed(_ link: Link) {
        guard links[link.fingerprint] === link else { return }
        disconnectSender(link.fingerprint)
    }

    private func route(_ message: Message, from fingerprint: Data) {
        switch message {
        case let .fileOffer(transferId, name, size, sha256, _, batch):
            guard let receiver = receivers[fingerprint] else { return }
            if let summary = incomingBatches[fingerprint, default: IncomingBatches()].offer(batch: batch) {
                onBatchSummary?(fingerprint, summary)
            }
            let reply = receiver.offer(transferId: transferId, name: name, size: size, sha256: sha256, batch: batch)
            links[fingerprint]?.send(reply)
        case let .batchEnd(batch, count, sent, cancelled):
            if let summary = incomingBatches[fingerprint]?.end(batch: batch, count: count, sent: sent, cancelled: cancelled) {
                onBatchSummary?(fingerprint, summary)
            }
        case let .fileDone(transferId):
            guard let result = receivers[fingerprint]?.done(transferId: transferId) else { return }
            finish(result, from: fingerprint)
        case let .fileCancel(transferId):
            receivers[fingerprint]?.cancel(transferId: transferId)
            senders[fingerprint]?.receive(message)
        case .fileAccept, .fileReject, .fileResult:
            senders[fingerprint]?.receive(message)
        default:
            return
        }
        publish()
    }

    private func chunk(transferId: String, offset: UInt64, data: Data, from fingerprint: Data) {
        guard let receiver = receivers[fingerprint] else { return }
        if let result = receiver.chunk(transferId: transferId, offset: offset, data: data) {
            finish(result, from: fingerprint)
        }
        publish()
    }

    /// Reports an incoming transfer's outcome, then sends the receiver's reply.
    private func finish(_ result: (reply: Message, outcome: TransferOutcome, batch: BatchInfo?), from fingerprint: Data) {
        if let batch = result.batch {
            incomingBatches[fingerprint, default: IncomingBatches()].outcome(result.outcome, batch: batch)
        } else {
            onOutcome?(fingerprint, result.outcome)
        }
        links[fingerprint]?.send(result.reply)
    }

    private func sender(for fingerprint: Data) -> FileSender {
        if let sender = senders[fingerprint] { return sender }
        let sender = FileSender(queue: queue)
        sender.onStatus = { [weak self] status in
            self?.outgoing[fingerprint] = status
            self?.publish()
        }
        sender.onOutcome = { [weak self] outcome in self?.onOutcome?(fingerprint, outcome) }
        sender.onBatchSummary = { [weak self] summary in self?.onBatchSummary?(fingerprint, summary) }
        senders[fingerprint] = sender
        if let link = links[fingerprint] { sender.connected(link) }
        return sender
    }

    // MARK: - Snapshots

    private var snapshot: Snapshot {
        Snapshot(incoming: receivers.values.lazy.compactMap(\.status).first,
                 outgoing: outgoing.values.first,
                 waitingToSend: senders.reduce(0) { links[$1.key] == nil ? $0 + $1.value.queuedCount : $0 })
    }

    /// Reports a changed snapshot. Progress-only changes are held until `statusInterval` after the last report.
    private func publish() {
        let current = snapshot
        guard current != reported else { return }
        let due = reportedAt + Self.statusInterval
        if Self.progressOnly(from: reported, to: current), DispatchTime.now() < due {
            guard !flushScheduled else { return }
            flushScheduled = true
            queue.asyncAfter(deadline: due) { [weak self] in
                self?.flushScheduled = false
                self?.publish()
            }
            return
        }
        reported = current
        reportedAt = .now()
        onSnapshot?(current)
    }

    /// Same transfers, files and queue lengths; only `bytesDone` (file or batch) moved.
    static func progressOnly(from old: Snapshot, to new: Snapshot) -> Bool {
        func shape(_ snapshot: Snapshot) -> Snapshot {
            var shaped = snapshot
            shaped.incoming?.bytesDone = 0
            shaped.outgoing?.bytesDone = 0
            shaped.incoming?.batch?.bytesDone = 0
            shaped.outgoing?.batch?.bytesDone = 0
            return shaped
        }
        return shape(old) == shape(new)
    }

    // MARK: - Link

    /// One session's path to the phone, over `server.send` / `server.sendChunk`. Both hop to the server queue,
    /// so frames leave in call order (TransferLink contract 1). Holds the sender's owner and the server weakly,
    /// so FileSender → Link makes no cycle.
    private final class Link: TransferLink, @unchecked Sendable {
        let fingerprint: Data
        /// Frames go to this session only: a replaced session's sender must not write into its successor.
        let session: DroidDropServer.SessionID
        private weak var server: DroidDropServer?
        private weak var owner: FileTransfers?

        init(fingerprint: Data, session: DroidDropServer.SessionID, server: DroidDropServer?, owner: FileTransfers) {
            self.fingerprint = fingerprint
            self.session = session
            self.server = server
            self.owner = owner
        }

        func send(_ message: Message) {
            server?.send(message, to: fingerprint, session: session)
        }

        func sendChunk(transferId: String, offset: UInt64, data: Data, completion: @escaping @Sendable (Bool) -> Void) {
            guard let server else {
                failed()
                return completion(false)
            }
            server.sendChunk(transferId: transferId, offset: offset, data: data, to: fingerprint, session: session) { [weak self] handed in
                if !handed { self?.failed() }
                completion(handed)
            }
        }

        /// TransferLink contract 2: a false completion must leave the sender disconnected. Always async, so the
        /// sender is never re-entered from inside its own `sendChunk` call.
        private func failed() {
            owner?.queue.async { [weak self] in
                guard let self else { return }
                self.owner?.linkFailed(self)
            }
        }
    }
}
