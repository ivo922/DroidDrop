import Foundation
import Testing
@testable import DroidDropKit

@Suite struct FileReceiverTests {
    let id1 = "0f8e1c52-7a0b-4a43-9d1e-111111111111"
    let id2 = "0f8e1c52-7a0b-4a43-9d1e-222222222222"
    let mebibyte = 1_048_576

    private func makeReceiver(in root: URL = temporaryDirectory()) -> (FileReceiver, root: URL) {
        let receiver = FileReceiver(partials: PartialStore(directory: root.appending(path: "partials")),
                                    destination: root.appending(path: "Downloads/DroidDrop"))
        return (receiver, root)
    }

    private func sample(_ count: Int = 3_000_000) -> Data {
        Data((0..<count).map { UInt8($0 % 251) })
    }

    private func partialFiles(_ root: URL) -> [String] {
        (try? FileManager.default.contentsOfDirectory(atPath: root.appending(path: "partials").path)) ?? []
    }

    private func partialCount(_ root: URL) -> Int {
        partialFiles(root).filter { $0.hasSuffix(".part") }.count
    }

    private func completionMarkers(_ root: URL) -> [URL] {
        partialFiles(root).filter { $0.hasSuffix(".done") }.map { root.appending(path: "partials/\($0)") }
    }

    private func destinationCount(_ root: URL) -> Int {
        (try? FileManager.default.contentsOfDirectory(atPath: root.appending(path: "Downloads/DroidDrop").path).count) ?? 0
    }

    /// Offers, sends everything in one chunk and finishes. Returns the outcome.
    private func receive(_ name: String, _ data: Data, with receiver: FileReceiver) throws -> TransferOutcome {
        _ = receiver.offer(transferId: id1, name: name, size: data.count, sha256: hex(data))
        #expect(receiver.chunk(transferId: id1, offset: 0, data: data) == nil)
        return try #require(receiver.done(transferId: id1)).outcome
    }

    @Test func receivedFileIsQuarantined() throws {
        // Not sandboxed, so macOS adds nothing itself: without this a received .app or .command skips Gatekeeper.
        let (receiver, root) = makeReceiver()
        let outcome = try receive("tool.command", Data("echo hi".utf8), with: receiver)
        guard case .received(_, let url) = outcome else { Issue.record("not received: \(outcome)"); return }
        let quarantine = try url.resourceValues(forKeys: [.quarantinePropertiesKey]).quarantineProperties
        #expect(quarantine?[kLSQuarantineAgentNameKey as String] as? String == "DroidDrop")
        #expect(destinationCount(root) == 1)
    }

    @Test func receivesFileIntoDestination() throws {
        let (receiver, root) = makeReceiver()
        let data = sample()
        #expect(receiver.offer(transferId: id1, name: "photo.jpg", size: data.count, sha256: hex(data)) == .fileAccept(transferId: id1, offset: 0))
        #expect(receiver.chunk(transferId: id1, offset: 0, data: Data(data[..<1_048_576])) == nil)
        #expect(receiver.status == TransferStatus(direction: .incoming, name: "photo.jpg", bytesDone: 1_048_576, size: data.count))
        #expect(receiver.chunk(transferId: id1, offset: 1_048_576, data: Data(data[1_048_576...])) == nil)
        let (reply, outcome, _) = try #require(receiver.done(transferId: id1))
        let saved = root.appending(path: "Downloads/DroidDrop/photo.jpg")
        #expect(reply == .fileResult(transferId: id1, ok: true, reason: nil))
        #expect(outcome == .received(name: "photo.jpg", url: saved))
        #expect(try Data(contentsOf: saved) == data)
        #expect(receiver.status == nil)
        #expect(partialFiles(root) == ["\(hex(data))-3000000.done"])
    }

    @Test func emptyFile() throws {
        let (receiver, root) = makeReceiver()
        #expect(receiver.offer(transferId: id1, name: "empty.txt", size: 0, sha256: hex(Data())) == .fileAccept(transferId: id1, offset: 0))
        let (reply, _, _) = try #require(receiver.done(transferId: id1))
        #expect(reply == .fileResult(transferId: id1, ok: true, reason: nil))
        let saved = root.appending(path: "Downloads/DroidDrop/empty.txt")
        #expect(try Data(contentsOf: saved).isEmpty)
    }

    @Test func resumesFromRetainedPartial() throws {
        let (first, root) = makeReceiver()
        let data = sample()
        _ = first.offer(transferId: id1, name: "photo.jpg", size: data.count, sha256: hex(data))
        #expect(first.chunk(transferId: id1, offset: 0, data: Data(data[..<mebibyte])) == nil)
        first.close()

        let (second, _) = makeReceiver(in: root)
        #expect(second.offer(transferId: id2, name: "photo.jpg", size: data.count, sha256: hex(data)) == .fileAccept(transferId: id2, offset: mebibyte))
        #expect(second.status?.bytesDone == mebibyte)
        #expect(second.chunk(transferId: id2, offset: UInt64(mebibyte), data: Data(data[mebibyte...])) == nil)
        let (reply, outcome, _) = try #require(second.done(transferId: id2))
        #expect(reply == .fileResult(transferId: id2, ok: true, reason: nil))
        let saved = root.appending(path: "Downloads/DroidDrop/photo.jpg")
        #expect(outcome == .received(name: "photo.jpg", url: saved))
        #expect(try Data(contentsOf: saved) == data)
    }

    @Test func peerCancelKeepsPartial() throws {
        let (receiver, root) = makeReceiver()
        let data = sample()
        let store = PartialStore(directory: root.appending(path: "partials"))
        _ = receiver.offer(transferId: id1, name: "photo.jpg", size: data.count, sha256: hex(data))
        #expect(receiver.chunk(transferId: id1, offset: 0, data: Data(data[..<mebibyte])) == nil)
        receiver.cancel(transferId: id1)
        #expect(receiver.status == nil)
        #expect(receiver.chunk(transferId: id1, offset: UInt64(mebibyte), data: Data(data[mebibyte..<(mebibyte + 10)])) == nil)
        #expect(store.resumeOffset(sha256: hex(data), size: data.count) == mebibyte)
        #expect(receiver.offer(transferId: id2, name: "photo.jpg", size: data.count, sha256: hex(data)) == .fileAccept(transferId: id2, offset: mebibyte))
    }

    @Test func outOfOrderChunkCancelsAndDiscards() throws {
        let (receiver, root) = makeReceiver()
        let data = sample(100)
        _ = receiver.offer(transferId: id1, name: "photo.jpg", size: data.count, sha256: hex(data))
        let result = try #require(receiver.chunk(transferId: id1, offset: 10, data: Data(data[10..<20])))
        #expect(result.reply == .fileCancel(transferId: id1))
        #expect(result.outcome == .failed(.incoming, name: "photo.jpg", reason: "out_of_order"))
        #expect(receiver.status == nil)
        #expect(partialCount(root) == 0)
        #expect(receiver.offer(transferId: id2, name: "photo.jpg", size: data.count, sha256: hex(data)) == .fileAccept(transferId: id2, offset: 0))
    }

    @Test func chunkPastSizeCancels() throws {
        let (receiver, root) = makeReceiver()
        let data = sample(4)
        _ = receiver.offer(transferId: id1, name: "a.bin", size: 4, sha256: hex(data))
        let result = try #require(receiver.chunk(transferId: id1, offset: 0, data: Data(count: 5)))
        #expect(result.reply == .fileCancel(transferId: id1))
        #expect(result.outcome == .failed(.incoming, name: "a.bin", reason: "too_long"))
        #expect(partialCount(root) == 0)
    }

    @Test func unknownTransferChunksAreIgnored() throws {
        let (receiver, _) = makeReceiver()
        #expect(receiver.chunk(transferId: id2, offset: 0, data: Data(count: 3)) == nil)
        let data = sample(10)
        _ = receiver.offer(transferId: id1, name: "a.bin", size: 10, sha256: hex(data))
        #expect(receiver.chunk(transferId: id2, offset: 0, data: Data(count: 3)) == nil)
        #expect(receiver.status?.bytesDone == 0)
        #expect(receiver.done(transferId: id2) == nil)
    }

    @Test func earlyDoneFailsWithSizeMismatch() throws {
        let (receiver, root) = makeReceiver()
        let data = sample(10)
        _ = receiver.offer(transferId: id1, name: "a.bin", size: 10, sha256: hex(data))
        #expect(receiver.chunk(transferId: id1, offset: 0, data: Data(data[..<5])) == nil)
        let (reply, outcome, _) = try #require(receiver.done(transferId: id1))
        #expect(reply == .fileResult(transferId: id1, ok: false, reason: "size_mismatch"))
        #expect(outcome == .failed(.incoming, name: "a.bin", reason: "size_mismatch"))
        #expect(partialCount(root) == 0)
        #expect(destinationCount(root) == 0)
        #expect(receiver.status == nil)
    }

    @Test func hashMismatchDiscards() throws {
        let (receiver, root) = makeReceiver()
        let data = sample(1000)
        _ = receiver.offer(transferId: id1, name: "a.bin", size: data.count, sha256: hex(Data("other".utf8)))
        #expect(receiver.chunk(transferId: id1, offset: 0, data: data) == nil)
        let (reply, outcome, _) = try #require(receiver.done(transferId: id1))
        #expect(reply == .fileResult(transferId: id1, ok: false, reason: "hash_mismatch"))
        #expect(outcome == .failed(.incoming, name: "a.bin", reason: "hash_mismatch"))
        #expect(partialCount(root) == 0)
        #expect(destinationCount(root) == 0)
    }

    @Test func existingFileIsNotOverwritten() throws {
        let (receiver, root) = makeReceiver()
        let dest = root.appending(path: "Downloads/DroidDrop")
        try FileManager.default.createDirectory(at: dest, withIntermediateDirectories: true)
        try Data("old".utf8).write(to: dest.appending(path: "photo.jpg"))
        let data = sample(100)
        let outcome = try receive("photo.jpg", data, with: receiver)
        #expect(outcome == .received(name: "photo (1).jpg", url: dest.appending(path: "photo (1).jpg")))
        #expect(try Data(contentsOf: dest.appending(path: "photo.jpg")) == Data("old".utf8))
        #expect(try Data(contentsOf: dest.appending(path: "photo (1).jpg")) == data)
    }

    @Test func traversalNameStaysInDestination() throws {
        let (receiver, root) = makeReceiver()
        let outcome = try receive("../../evil.sh", sample(10), with: receiver)
        #expect(outcome == .received(name: "_.._evil.sh", url: root.appending(path: "Downloads/DroidDrop/_.._evil.sh")))
    }

    @Test func newOfferSupersedesActiveTransfer() throws {
        let (receiver, root) = makeReceiver()
        let a = sample(3_000_000)
        let b = sample(500)
        _ = receiver.offer(transferId: id1, name: "a.bin", size: a.count, sha256: hex(a))
        #expect(receiver.chunk(transferId: id1, offset: 0, data: Data(a[..<mebibyte])) == nil)
        #expect(receiver.offer(transferId: id2, name: "b.bin", size: b.count, sha256: hex(b)) == .fileAccept(transferId: id2, offset: 0))
        #expect(receiver.chunk(transferId: id1, offset: UInt64(mebibyte), data: Data(a[mebibyte..<(mebibyte + 10)])) == nil)
        #expect(receiver.status == TransferStatus(direction: .incoming, name: "b.bin", bytesDone: 0, size: b.count))
        let store = PartialStore(directory: root.appending(path: "partials"))
        #expect(store.resumeOffset(sha256: hex(a), size: a.count) == mebibyte)
    }

    @Test func unwritablePartialsDirectoryRejects() throws {
        let root = temporaryDirectory()
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        try Data().write(to: root.appending(path: "partials"))
        let (receiver, _) = makeReceiver(in: root)
        let data = sample(10)
        #expect(receiver.offer(transferId: id1, name: "a.bin", size: 10, sha256: hex(data)) == .fileReject(transferId: id1, reason: "cannot_write"))
        #expect(receiver.status == nil)
    }

    @Test func completedTransferIsNotSavedTwice() throws {
        let (first, root) = makeReceiver()
        let data = sample(100)
        let firstOutcome = try receive("photo.jpg", data, with: first)
        let (second, _) = makeReceiver(in: root)
        #expect(second.offer(transferId: id2, name: "photo.jpg", size: data.count, sha256: hex(data)) == .fileAccept(transferId: id2, offset: data.count))
        #expect(second.status == nil)
        #expect(second.chunk(transferId: id2, offset: 0, data: data) == nil)
        let (reply, outcome, _) = try #require(second.done(transferId: id2))
        #expect(reply == .fileResult(transferId: id2, ok: true, reason: nil))
        #expect(outcome == firstOutcome)
        #expect(destinationCount(root) == 1)
        #expect(partialCount(root) == 0)
    }

    @Test func staleCompletionMarkerIsIgnored() throws {
        let (first, root) = makeReceiver()
        let data = sample(100)
        _ = try receive("photo.jpg", data, with: first)
        let marker = try #require(completionMarkers(root).first)
        try FileManager.default.setAttributes([.modificationDate: Date().addingTimeInterval(-11 * 60)], ofItemAtPath: marker.path)
        let (second, _) = makeReceiver(in: root)
        #expect(second.offer(transferId: id2, name: "photo.jpg", size: data.count, sha256: hex(data)) == .fileAccept(transferId: id2, offset: 0))
        #expect(second.chunk(transferId: id2, offset: 0, data: data) == nil)
        let (reply, outcome, _) = try #require(second.done(transferId: id2))
        #expect(reply == .fileResult(transferId: id2, ok: true, reason: nil))
        let saved = root.appending(path: "Downloads/DroidDrop/photo (1).jpg")
        #expect(outcome == .received(name: "photo (1).jpg", url: saved))
        #expect(try Data(contentsOf: saved) == data)
    }

    @Test func completionMarkerIgnoredWhenSavedFileDeleted() throws {
        let (first, root) = makeReceiver()
        let data = sample(100)
        _ = try receive("photo.jpg", data, with: first)
        #expect(completionMarkers(root).count == 1)
        let saved = root.appending(path: "Downloads/DroidDrop/photo.jpg")
        try FileManager.default.removeItem(at: saved)
        let (second, _) = makeReceiver(in: root)
        #expect(second.offer(transferId: id2, name: "photo.jpg", size: data.count, sha256: hex(data)) == .fileAccept(transferId: id2, offset: 0))
        #expect(second.chunk(transferId: id2, offset: 0, data: data) == nil)
        let (reply, outcome, _) = try #require(second.done(transferId: id2))
        #expect(reply == .fileResult(transferId: id2, ok: true, reason: nil))
        #expect(outcome == .received(name: "photo.jpg", url: saved))
        #expect(try Data(contentsOf: saved) == data)
        #expect(destinationCount(root) == 1)
    }

    // MARK: - Batches

    @Test func statusCarriesBatchProgress() throws {
        let (receiver, _) = makeReceiver()
        let data = sample(3_000)
        let batch = BatchInfo(id: id2, index: 2, count: 5, size: 10_000, before: 4_000)
        _ = receiver.offer(transferId: id1, name: "a.bin", size: data.count, sha256: hex(data), batch: batch)
        #expect(receiver.status?.batch == BatchProgress(index: 2, count: 5, bytesDone: 4_000, size: 10_000))
        #expect(receiver.chunk(transferId: id1, offset: 0, data: Data(data[..<1_000])) == nil)
        #expect(receiver.status?.batch == BatchProgress(index: 2, count: 5, bytesDone: 5_000, size: 10_000))
    }

    @Test func statusBatchProgressClamped() throws {
        let (receiver, _) = makeReceiver()
        let data = sample(3_000)
        let batch = BatchInfo(id: id2, index: 1, count: 2, size: 4_000, before: 3_500)
        _ = receiver.offer(transferId: id1, name: "a.bin", size: data.count, sha256: hex(data), batch: batch)
        #expect(receiver.chunk(transferId: id1, offset: 0, data: Data(data[..<1_000])) == nil)
        #expect(receiver.status?.batch?.bytesDone == 4_000)
    }

    @Test func outcomeCarriesBatch() throws {
        let (receiver, _) = makeReceiver()
        let data = sample(3_000)
        let batch = BatchInfo(id: id2, index: 0, count: 2, size: 6_000, before: 0)
        _ = receiver.offer(transferId: id1, name: "a.bin", size: data.count, sha256: hex(data), batch: batch)
        #expect(receiver.chunk(transferId: id1, offset: 0, data: data) == nil)
        let first = try #require(receiver.done(transferId: id1))
        #expect(first.batch == batch)

        // The "already completed" path carries the re-offer's batch, not the first offer's.
        let reoffer = BatchInfo(id: id2, index: 0, count: 3, size: 9_000, before: 100)
        _ = receiver.offer(transferId: id2, name: "a.bin", size: data.count, sha256: hex(data), batch: reoffer)
        let again = try #require(receiver.done(transferId: id2))
        #expect(again.batch == reoffer)
        #expect(again.outcome == first.outcome)
        guard case .received = again.outcome else { Issue.record("not received: \(again.outcome)"); return }
        #expect(again.reply == .fileResult(transferId: id2, ok: true, reason: nil))

        // The abort path carries it too.
        let stray = BatchInfo(id: id2, index: 1, count: 2, size: 6_000, before: 3_000)
        _ = receiver.offer(transferId: id1, name: "s.bin", size: 10, sha256: hex(Data(count: 10)), batch: stray)
        let aborted = try #require(receiver.chunk(transferId: id1, offset: 5, data: Data(count: 5)))
        #expect(aborted.batch == stray)
        #expect(aborted.reply == .fileCancel(transferId: id1))
        #expect(aborted.outcome == .failed(.incoming, name: "s.bin", reason: "out_of_order"))

        // A failure carries it, and an unbatched file does not.
        let bad = BatchInfo(id: id2, index: 1, count: 2, size: 6_000, before: 3_000)
        _ = receiver.offer(transferId: id1, name: "b.bin", size: 4, sha256: hex(Data("good".utf8)), batch: bad)
        #expect(receiver.chunk(transferId: id1, offset: 0, data: Data("bad!".utf8)) == nil)
        let failed = try #require(receiver.done(transferId: id1))
        #expect(failed.batch == bad)
        #expect(failed.outcome == .failed(.incoming, name: "b.bin", reason: "hash_mismatch"))
        let plain = Data("plain".utf8)
        _ = receiver.offer(transferId: id1, name: "c.txt", size: plain.count, sha256: hex(plain))
        #expect(receiver.chunk(transferId: id1, offset: 0, data: plain) == nil)
        #expect(try #require(receiver.done(transferId: id1)).batch == nil)
    }
}
