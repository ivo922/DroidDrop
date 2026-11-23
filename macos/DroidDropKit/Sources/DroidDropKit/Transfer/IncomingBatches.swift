import Foundation

/// The one open incoming batch of a peer, kept above the per-session `FileReceiver` so it survives reconnects.
/// A plain struct for `FileTransfers` to use on its queue (R12).
public struct IncomingBatches {
    private struct Open {
        var id: String
        var count: Int
        /// Received file index to where it was saved; a repeated index (a re-offer) counts once.
        var received: [Int: URL] = [:]
    }

    private var open: Open?

    public init() {}

    /// Call for every offer. Returns the fallback summary (silent) of a different open batch that this offer ends, else nil.
    public mutating func offer(batch: BatchInfo?) -> BatchSummary? {
        let current = open
        if var current, let batch, current.id == batch.id {
            current.count = batch.count
            current.received = current.received.filter { $0.key < batch.count }
            open = current
            return nil
        }
        open = batch.map { Open(id: $0.id, count: $0.count) }
        guard let current else { return nil }
        return summary(current, count: current.count, done: min(current.received.count, current.count),
                       cancelled: false, silent: true)
    }

    /// Records a `received` outcome tagged with the open batch (index + location). Anything else is ignored.
    public mutating func outcome(_ outcome: TransferOutcome, batch: BatchInfo) {
        guard open?.id == batch.id, case let .received(_, url) = outcome else { return }
        open?.received[batch.index] = url
    }

    /// Summary from `batch_end`'s count/sent/cancelled plus the locations seen, or nil for an unknown batch.
    public mutating func end(batch: String, count: Int, sent: Int, cancelled: Bool) -> BatchSummary? {
        guard let current = open, current.id == batch else { return nil }
        open = nil
        return summary(current, count: count, done: sent, cancelled: cancelled, silent: false)
    }

    /// Drops the open batch without a summary (unpair).
    public mutating func clear() {
        open = nil
    }

    private func summary(_ current: Open, count: Int, done: Int, cancelled: Bool, silent: Bool) -> BatchSummary {
        BatchSummary(direction: .incoming, batch: current.id, count: count, done: done, cancelled: cancelled,
                     silent: silent, files: current.received.sorted { $0.key < $1.key }.map(\.value))
    }
}
