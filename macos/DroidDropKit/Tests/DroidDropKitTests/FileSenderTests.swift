import Foundation
import Testing
@testable import DroidDropKit

/// Records what a FileSender writes. Completions can be held back to test the window.
final class FakeLink: TransferLink, @unchecked Sendable {
    struct Chunk { let transferId: String; let offset: UInt64; let data: Data }

    private let lock = NSLock()
    private var messages: [Message] = []
    private var sentChunks: [Chunk] = []
    private var consumed = 0
    private var pending: [@Sendable (Bool) -> Void] = []
    private var auto = true

    var autoComplete: Bool {
        get { lock.withLock { auto } }
        set { lock.withLock { auto = newValue } }
    }
    var chunkCount: Int { lock.withLock { sentChunks.count } }
    var messageCount: Int { lock.withLock { messages.count } }
    /// How many batch_end messages were ever sent.
    var batchEndCount: Int { lock.withLock { endsSent } }
    private var endsSent = 0

    func send(_ message: Message) {
        lock.withLock {
            messages.append(message)
            if case .batchEnd = message { endsSent += 1 }
        }
    }

    func sendChunk(transferId: String, offset: UInt64, data: Data, completion: @escaping @Sendable (Bool) -> Void) {
        let completeNow = lock.withLock { () -> Bool in
            sentChunks.append(Chunk(transferId: transferId, offset: offset, data: data))
            if auto { return true }
            pending.append(completion)
            return false
        }
        if completeNow { completion(true) }
    }

    /// Completes the oldest held chunk.
    func completePending() {
        let completion = lock.withLock { pending.isEmpty ? nil : pending.removeFirst() }
        completion?(true)
    }

    func next(timeout: TimeInterval = 5) async throws -> Message {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if let message = lock.withLock({ messages.isEmpty ? nil : messages.removeFirst() }) { return message }
            try await Task.sleep(for: .milliseconds(10))
        }
        throw TimeoutError()
    }

    func chunks(count: Int, timeout: TimeInterval = 5) async throws -> [Chunk] {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            let taken = lock.withLock { () -> [Chunk]? in
                guard sentChunks.count - consumed >= count else { return nil }
                defer { consumed += count }
                return Array(sentChunks[consumed..<consumed + count])
            }
            if let taken { return taken }
            try await Task.sleep(for: .milliseconds(10))
        }
        throw TimeoutError()
    }
}

/** A FileSender hash that blocks until released, and counts how often it ran. */
final class HashGate: @unchecked Sendable {
    private let lock = NSLock()
    private let gate = DispatchSemaphore(value: 0)
    private var count = 0
    var started: Int { lock.withLock { count } }

    func run(_ url: URL) throws -> (hex: String, size: Int) {
        lock.withLock { count += 1 }
        gate.wait()
        return try FileDigest.sha256(of: url)
    }

    func release() { gate.signal() }

    func waitForStart(timeout: TimeInterval = 5) async throws {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if started > 0 { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        throw TimeoutError()
    }
}

final class OutcomeRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private var recordedOutcomes: [TransferOutcome] = []
    private var recordedStatuses: [TransferStatus?] = []
    private var recordedSummaries: [BatchSummary] = []
    private var recordedBatchOutcomes: [TransferOutcome] = []
    private var recordedEvents: [String] = []
    var summaries: [BatchSummary] { lock.withLock { recordedSummaries } }
    var batchOutcomes: [TransferOutcome] { lock.withLock { recordedBatchOutcomes } }
    /// "outcome" / "summary" in the order they were reported.
    var events: [String] { lock.withLock { recordedEvents } }
    func record(summary: BatchSummary) { lock.withLock { recordedSummaries.append(summary); recordedEvents.append("summary") } }
    func record(batchOutcome: TransferOutcome) {
        lock.withLock { recordedBatchOutcomes.append(batchOutcome); recordedEvents.append("outcome") }
    }
    var outcomes: [TransferOutcome] { lock.withLock { recordedOutcomes } }
    var statuses: [TransferStatus?] { lock.withLock { recordedStatuses } }
    func record(_ outcome: TransferOutcome) { lock.withLock { recordedOutcomes.append(outcome) } }
    func record(status: TransferStatus?) { lock.withLock { recordedStatuses.append(status) } }
}

@Suite struct FileSenderTests {
    let queue = DispatchQueue(label: "test.filesender")
    let sender: FileSender
    let link = FakeLink()
    let recorder = OutcomeRecorder()
    let root = temporaryDirectory()
    let mebibyte = 1_048_576
    var outcomes: [TransferOutcome] { recorder.outcomes }

    init() throws {
        sender = FileSender(queue: queue)
        sender.onOutcome = { [recorder] in recorder.record($0) }
        sender.onStatus = { [recorder] in recorder.record(status: $0) }
        sender.onBatchSummary = { [recorder] in recorder.record(summary: $0) }
        sender.onBatchFileOutcome = { [recorder] outcome, _ in recorder.record(batchOutcome: outcome) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    private func write(_ data: Data, name: String) throws -> URL {
        let url = root.appending(path: name)
        try data.write(to: url)
        return url
    }

    private func sample(_ count: Int) -> Data {
        Data((0..<count).map { UInt8($0 % 253) })
    }

    /// Waits for the next message, which must be an offer, and returns its id.
    private func offerID(_ link: FakeLink, name: String? = nil) async throws -> String {
        guard case .fileOffer(let id, let offered, _, _, _, _) = try await link.next() else {
            throw UnexpectedEvent(description: "no offer")
        }
        if let name { #expect(offered == name) }
        return id
    }

    @Test func sendsFileInChunksThenDone() async throws {
        let data = Data((0..<2_621_440).map { UInt8($0 % 253) })
        let url = try write(data, name: "report.pdf")
        queue.sync { sender.enqueue([url]); sender.connected(link) }
        guard case .fileOffer(let id, "report.pdf", 2_621_440, let sha, "application/pdf", _) = try await link.next() else {
            Issue.record("no offer"); return
        }
        #expect(sha == hex(data))
        queue.sync { sender.receive(.fileAccept(transferId: id, offset: 0)) }
        let chunks = try await link.chunks(count: 3)
        #expect(chunks.map(\.offset) == [0, 1_048_576, 2_097_152])
        #expect(chunks.map(\.data.count) == [1_048_576, 1_048_576, 524_288])
        #expect(chunks.map(\.transferId) == [id, id, id])
        #expect(Data(chunks.flatMap(\.data)) == data)
        #expect(try await link.next() == .fileDone(transferId: id))
        queue.sync { sender.receive(.fileResult(transferId: id, ok: true, reason: nil)) }
        #expect(outcomes == [.sent(name: "report.pdf")])
        #expect(queue.sync { sender.queuedCount } == 0)
        #expect(recorder.statuses.last == .some(nil))
    }

    @Test func reportsProgressFromZero() async throws {
        let url = try write(sample(1_500_000), name: "a.bin")
        queue.sync { sender.enqueue([url]); sender.connected(link) }
        let id = try await offerID(link)
        #expect(recorder.statuses.first == TransferStatus(direction: .outgoing, name: "a.bin", bytesDone: 0, size: 1_500_000))
        queue.sync { sender.receive(.fileAccept(transferId: id, offset: 0)) }
        _ = try await link.chunks(count: 2)
        _ = try await link.next()
        queue.sync {}  // chunk completions hop onto the queue
        #expect(recorder.statuses.last == TransferStatus(direction: .outgoing, name: "a.bin", bytesDone: 1_500_000, size: 1_500_000))
    }

    @Test func resumesFromAcceptedOffset() async throws {
        let data = sample(2_621_440)
        let url = try write(data, name: "big.bin")
        queue.sync { sender.enqueue([url]); sender.connected(link) }
        let id = try await offerID(link)
        queue.sync { sender.receive(.fileAccept(transferId: id, offset: mebibyte)) }
        let chunks = try await link.chunks(count: 2)
        #expect(chunks.map(\.offset) == [1_048_576, 2_097_152])
        #expect(Data(chunks.flatMap(\.data)) == data.suffix(from: mebibyte))
        #expect(try await link.next() == .fileDone(transferId: id))
    }

    @Test func zeroLengthFileSendsDoneWithoutChunks() async throws {
        let url = try write(Data(), name: "a.txt")
        queue.sync { sender.enqueue([url]); sender.connected(link) }
        let id = try await offerID(link, name: "a.txt")
        queue.sync { sender.receive(.fileAccept(transferId: id, offset: 0)) }
        #expect(try await link.next() == .fileDone(transferId: id))
        #expect(link.chunkCount == 0)
    }

    @Test func keepsAtMostWindowChunksInFlight() async throws {
        let url = try write(sample(6 * mebibyte), name: "six.bin")
        link.autoComplete = false
        queue.sync { sender.enqueue([url]); sender.connected(link) }
        let id = try await offerID(link)
        queue.sync { sender.receive(.fileAccept(transferId: id, offset: 0)) }
        _ = try await link.chunks(count: 4)
        try await Task.sleep(for: .milliseconds(200))
        #expect(link.chunkCount == 4)
        link.completePending()
        _ = try await link.chunks(count: 1)
        #expect(link.chunkCount == 5)
    }

    @Test func reoffersHeadAfterReconnect() async throws {
        let data = sample(2_621_440)
        let url = try write(data, name: "big.bin")
        queue.sync { sender.enqueue([url]); sender.connected(link) }
        guard case .fileOffer(let id1, _, let size1, let sha1, _, _) = try await link.next() else {
            Issue.record("no offer"); return
        }
        queue.sync { sender.receive(.fileAccept(transferId: id1, offset: 0)) }
        _ = try await link.chunks(count: 1)
        queue.sync { sender.disconnected() }
        let link2 = FakeLink()
        queue.sync { sender.connected(link2) }
        guard case .fileOffer(let id2, "big.bin", let size2, let sha2, _, _) = try await link2.next() else {
            Issue.record("no second offer"); return
        }
        #expect(id2 != id1)
        #expect(sha2 == sha1)
        #expect(size2 == size1)
        #expect(link2.chunkCount == 0)
        #expect(outcomes.isEmpty)
        // A late accept for the first session's id must not start the new one.
        queue.sync { sender.receive(.fileAccept(transferId: id1, offset: 0)) }
        try await Task.sleep(for: .milliseconds(200))
        #expect(link2.chunkCount == 0)
        queue.sync { sender.receive(.fileAccept(transferId: id2, offset: 0)) }
        #expect(Data(try await link2.chunks(count: 3).flatMap(\.data)) == data)
    }

    @Test func reconnectWhileHashingKeepsTheOneHash() async throws {
        // A roaming phone reconnects several times while a big file hashes: each flap must not start another full read.
        let hashes = HashGate()
        let sender = FileSender(queue: queue, hash: { url in try hashes.run(url) })
        let url = try write(sample(1000), name: "big.bin")
        queue.sync { sender.enqueue([url]); sender.connected(link) }
        try await hashes.waitForStart()
        queue.sync { sender.disconnected() }
        let link2 = FakeLink()
        queue.sync { sender.connected(link2) }
        queue.sync { sender.disconnected() }
        let link3 = FakeLink()
        queue.sync { sender.connected(link3) }
        hashes.release()
        _ = try await offerID(link3, name: "big.bin")
        #expect(hashes.started == 1)
        #expect(link.messageCount == 0)
        #expect(link2.messageCount == 0)
    }

    @Test func rejectAndFailedResultMoveToNextFile() async throws {
        let a = try write(Data("a".utf8), name: "a.txt")
        let b = try write(Data("b".utf8), name: "b.txt")
        queue.sync { sender.enqueue([a]); sender.enqueue([b]); sender.connected(link) }
        let idA = try await offerID(link, name: "a.txt")
        queue.sync { sender.receive(.fileReject(transferId: idA, reason: "busy")) }
        let idB = try await offerID(link, name: "b.txt")
        #expect(outcomes == [.failed(.outgoing, name: "a.txt", reason: "busy")])
        queue.sync { sender.receive(.fileAccept(transferId: idB, offset: 0)) }
        _ = try await link.chunks(count: 1)
        #expect(try await link.next() == .fileDone(transferId: idB))
        queue.sync { sender.receive(.fileResult(transferId: idB, ok: false, reason: "hash_mismatch")) }
        #expect(outcomes == [.failed(.outgoing, name: "a.txt", reason: "busy"),
                             .failed(.outgoing, name: "b.txt", reason: "hash_mismatch")])
        #expect(queue.sync { sender.queuedCount } == 0)
    }

    @Test func peerCancelFailsFileAndContinues() async throws {
        let a = try write(Data("a".utf8), name: "a.txt")
        let b = try write(Data("b".utf8), name: "b.txt")
        queue.sync { sender.enqueue([a]); sender.enqueue([b]); sender.connected(link) }
        let idA = try await offerID(link, name: "a.txt")
        queue.sync { sender.receive(.fileAccept(transferId: idA, offset: 0)) }
        #expect(try await link.next() == .fileDone(transferId: idA))
        queue.sync { sender.receive(.fileCancel(transferId: idA)) }
        _ = try await offerID(link, name: "b.txt")
        #expect(outcomes == [.failed(.outgoing, name: "a.txt", reason: "cancelled by receiver")])
    }

    @Test func cancelAllSendsCancelAndEmptiesQueue() async throws {
        let urls = try ["a.txt", "b.txt", "c.txt"].map { try write(Data($0.utf8), name: $0) }
        link.autoComplete = false
        queue.sync { for url in urls { sender.enqueue([url]) }; sender.connected(link) }
        let id = try await offerID(link, name: "a.txt")
        queue.sync { sender.receive(.fileAccept(transferId: id, offset: 0)) }
        _ = try await link.chunks(count: 1)
        #expect(try await link.next() == .fileDone(transferId: id))
        queue.sync { sender.cancelAll() }
        #expect(try await link.next() == .fileCancel(transferId: id))
        #expect(outcomes == [.failed(.outgoing, name: "a.txt", reason: "cancelled")])
        #expect(queue.sync { sender.queuedCount } == 0)
        #expect(recorder.statuses.last == .some(nil))
        try await Task.sleep(for: .milliseconds(200))
        #expect(link.messageCount == 0)
    }

    @Test func skipsFolders() async throws {
        let folder = root.appending(path: "Stuff")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let file = try write(Data("a".utf8), name: "a.txt")
        queue.sync { sender.enqueue([folder, file]); sender.connected(link) }
        _ = try await offerID(link, name: "a.txt")
        #expect(outcomes == [.failed(.outgoing, name: "Stuff", reason: "folders are not supported")])
        #expect(queue.sync { sender.queuedCount } == 1)
    }

    @Test func missingFileFailsAndQueueContinues() async throws {
        let missing = root.appending(path: "gone.txt")
        let file = try write(Data("a".utf8), name: "a.txt")
        queue.sync { sender.enqueue([missing]); sender.enqueue([file]); sender.connected(link) }
        _ = try await offerID(link, name: "a.txt")
        #expect(outcomes.first == .failed(.outgoing, name: "gone.txt", reason: "file changed or unreadable"))
    }

    @Test func ignoresRepliesForOtherIds() async throws {
        let url = try write(sample(1000), name: "a.bin")
        queue.sync { sender.enqueue([url]); sender.connected(link) }
        _ = try await offerID(link)
        queue.sync { sender.receive(.fileAccept(transferId: "0f8e1c52-7a0b-4a43-9d1e-333333333333", offset: 0)) }
        try await Task.sleep(for: .milliseconds(200))
        #expect(link.chunkCount == 0)
        #expect(outcomes.isEmpty)
    }

    @Test func fileChangedWhileSendingCancelsAndFails() async throws {
        let url = try write(sample(2 * mebibyte), name: "shrinks.bin")
        queue.sync { sender.enqueue([url]); sender.connected(link) }
        let id = try await offerID(link)
        try Data(count: 10).write(to: url)  // truncated after hashing
        queue.sync { sender.receive(.fileAccept(transferId: id, offset: 0)) }
        #expect(try await link.next() == .fileCancel(transferId: id))
        #expect(outcomes == [.failed(.outgoing, name: "shrinks.bin", reason: "file changed or unreadable")])
        #expect(queue.sync { sender.queuedCount } == 0)
    }

    @Test func staleChunkCompletionAfterReconnectIsIgnored() async throws {
        let url = try write(sample(6 * mebibyte), name: "six.bin")
        link.autoComplete = false
        queue.sync { sender.enqueue([url]); sender.connected(link) }
        let id = try await offerID(link)
        queue.sync { sender.receive(.fileAccept(transferId: id, offset: 0)) }
        _ = try await link.chunks(count: 4)
        let link2 = FakeLink()
        link2.autoComplete = false
        queue.sync { sender.disconnected(); sender.connected(link2) }
        let id2 = try await offerID(link2)
        queue.sync { sender.receive(.fileAccept(transferId: id2, offset: 0)) }
        _ = try await link2.chunks(count: 4)
        for _ in 0..<4 { link.completePending() }  // these belong to the first session
        try await Task.sleep(for: .milliseconds(200))
        #expect(link2.chunkCount == 4)
    }

    // MARK: - Batches

    private func files(_ names: String...) throws -> [URL] {
        try names.map { try write(Data($0.utf8), name: $0) }
    }

    /// Waits for the offer of `name`, accepts it, streams its one chunk and answers with a file_result.
    @discardableResult
    private func complete(_ name: String, ok: Bool = true, reason: String? = nil, on link: FakeLink? = nil,
                          check: () -> Void = {}) async throws -> (id: String, batch: BatchInfo?) {
        let link = link ?? self.link
        guard case .fileOffer(let id, let offered, _, _, _, let batch) = try await link.next() else {
            throw UnexpectedEvent(description: "no offer")
        }
        #expect(offered == name)
        check()
        queue.sync { sender.receive(.fileAccept(transferId: id, offset: 0)) }
        _ = try await link.chunks(count: 1)
        #expect(try await link.next() == .fileDone(transferId: id))
        queue.sync { sender.receive(.fileResult(transferId: id, ok: ok, reason: reason)) }
        return (id, batch)
    }

    private func nextOffer(_ link: FakeLink, name: String) async throws -> (id: String, batch: BatchInfo?) {
        guard case .fileOffer(let id, let offered, _, _, _, let batch) = try await link.next() else {
            throw UnexpectedEvent(description: "no offer")
        }
        #expect(offered == name)
        return (id, batch)
    }

    @Test func twoFilesFormABatch() async throws {
        let urls = [try write(Data("hello world".utf8), name: "a.txt"), try write(Data("12345".utf8), name: "b.txt")]
        queue.sync { sender.enqueue(urls); sender.connected(link) }
        let first = try await complete("a.txt")
        let id = try #require(first.batch).id
        #expect(first.batch == BatchInfo(id: id, index: 0, count: 2, size: 16, before: 0))
        let second = try await complete("b.txt")
        #expect(second.batch == BatchInfo(id: id, index: 1, count: 2, size: 16, before: 11))
        #expect(try await link.next() == .batchEnd(batch: id, count: 2, sent: 2, cancelled: false))
        #expect(recorder.summaries == [BatchSummary(direction: .outgoing, batch: id, count: 2, done: 2)])
        #expect(queue.sync { sender.queuedCount } == 0)
    }

    @Test func batchIdComesFromMakeID() async throws {
        let sender = FileSender(queue: queue, makeID: { "11111111-1111-4111-8111-111111111111" })
        let urls = try files("a.txt", "b.txt")
        queue.sync { sender.enqueue(urls); sender.connected(link) }
        guard case .fileOffer(_, _, _, _, _, let batch) = try await link.next() else { Issue.record("no offer"); return }
        #expect(batch?.id == "11111111-1111-4111-8111-111111111111")
    }

    @Test func singleFileIsUnbatched() async throws {
        let urls = try files("a.txt")
        queue.sync { sender.enqueue(urls); sender.connected(link) }
        #expect(try await complete("a.txt").batch == nil)
        #expect(outcomes == [.sent(name: "a.txt")])
        let next = try files("next.txt")
        queue.sync { sender.enqueue(next) }
        _ = try await offerID(link, name: "next.txt")  // a batch_end in between would fail the cast
        #expect(recorder.summaries.isEmpty)
        #expect(recorder.batchOutcomes.isEmpty)
    }

    @Test func folderLeftOutOfBatch() async throws {
        let folder = root.appending(path: "Stuff")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let urls = try files("a.txt", "b.txt")
        queue.sync { sender.enqueue([folder] + urls); sender.connected(link) }
        #expect(outcomes == [.failed(.outgoing, name: "Stuff", reason: "folders are not supported")])
        #expect(recorder.batchOutcomes.isEmpty)
        let first = try await complete("a.txt")
        #expect(first.batch?.count == 2)
    }

    @Test func statusCountsThroughBatch() async throws {
        let urls = [try write(Data("hello world".utf8), name: "a.txt"), try write(Data("12345".utf8), name: "b.txt")]
        queue.sync { sender.enqueue(urls); sender.connected(link) }
        try await complete("a.txt")
        let offer = try await nextOffer(link, name: "b.txt")
        #expect(recorder.statuses.last??.batch == BatchProgress(index: 1, count: 2, bytesDone: 11, size: 16))
        queue.sync { sender.receive(.fileAccept(transferId: offer.id, offset: 0)) }
        _ = try await link.chunks(count: 1)
        #expect(try await link.next() == .fileDone(transferId: offer.id))
        queue.sync {}
        #expect(recorder.statuses.last??.batch == BatchProgress(index: 1, count: 2, bytesDone: 16, size: 16))
    }

    @Test func progressClampedWhenFileGrew() async throws {
        let a = try write(Data("hello world".utf8), name: "a.txt")
        let b = try write(Data("12345".utf8), name: "b.txt")
        queue.sync { sender.enqueue([a, b]) }
        try Data("hello world123456789".utf8).write(to: a)  // 20 bytes now, 11 at enqueue
        queue.sync { sender.connected(link) }
        guard case .fileOffer(let id, "a.txt", 20, _, _, let batch) = try await link.next() else {
            Issue.record("no offer"); return
        }
        #expect(batch == BatchInfo(id: try #require(batch).id, index: 0, count: 2, size: 16, before: 0))
        queue.sync { sender.receive(.fileAccept(transferId: id, offset: 0)) }
        _ = try await link.chunks(count: 1)
        #expect(try await link.next() == .fileDone(transferId: id))
        queue.sync {}
        let progress = try #require(recorder.statuses.last??.batch)
        #expect(progress == BatchProgress(index: 0, count: 2, bytesDone: 11, size: 16))
        #expect(recorder.statuses.compactMap { $0?.batch }.allSatisfy { $0.bytesDone <= $0.size })
    }

    @Test func failureListedInSummary() async throws {
        let urls = try files("a.txt", "b.txt")
        queue.sync { sender.enqueue(urls); sender.connected(link) }
        let id = try #require(try await complete("a.txt").batch).id
        try await complete("b.txt", ok: false, reason: "hash_mismatch")
        #expect(try await link.next() == .batchEnd(batch: id, count: 2, sent: 1, cancelled: false))
        #expect(recorder.summaries == [BatchSummary(direction: .outgoing, batch: id, count: 2, done: 1,
                                                    failures: [BatchFailure(name: "b.txt", reason: "hash_mismatch")],
                                                    files: [urls[1]])])
    }

    @Test func reofferKeepsBatch() async throws {
        let urls = try files("a.txt", "b.txt")
        queue.sync { sender.enqueue(urls); sender.connected(link) }
        try await complete("a.txt")
        let offer1 = try await nextOffer(link, name: "b.txt")
        queue.sync { sender.disconnected() }
        let link2 = FakeLink()
        queue.sync { sender.connected(link2) }
        let offer2 = try await nextOffer(link2, name: "b.txt")
        #expect(offer1.id != offer2.id)
        #expect(offer1.batch == offer2.batch)
        #expect(offer2.batch?.index == 1)
        #expect(offer2.batch?.before == 5)
    }

    @Test func batchEndWaitsForNextSession() async throws {
        // A batch ended while no session is up (cancelled offline); another batch is queued meanwhile.
        let urls = try files("a.txt", "b.txt")
        queue.sync { sender.enqueue(urls); sender.connected(link) }
        let id = try #require(try await nextOffer(link, name: "a.txt").batch).id
        queue.sync { sender.disconnected(); sender.cancelAll() }
        let more = try files("c.txt", "d.txt")
        queue.sync { sender.enqueue(more) }
        let link2 = FakeLink()
        queue.sync { sender.connected(link2) }
        #expect(try await link2.next() == .batchEnd(batch: id, count: 2, sent: 0, cancelled: true))
        _ = try await nextOffer(link2, name: "c.txt")
    }

    @Test func cancelOfflineQueuesBatchEnd() async throws {
        let urls = try files("a.txt", "b.txt")
        queue.sync { sender.enqueue(urls); sender.connected(link) }
        let id = try #require(try await complete("a.txt").batch).id
        _ = try await nextOffer(link, name: "b.txt")
        queue.sync { sender.disconnected() }
        queue.sync { sender.cancelAll() }
        #expect(recorder.summaries == [BatchSummary(direction: .outgoing, batch: id, count: 2, done: 1, cancelled: true)])
        let link2 = FakeLink()
        queue.sync { sender.connected(link2) }
        #expect(try await link2.next() == .batchEnd(batch: id, count: 2, sent: 1, cancelled: true))
    }

    @Test func cancelQueuedOnlyBatchIsSilent() async throws {
        let urls = try files("a.txt", "b.txt")
        queue.sync { sender.enqueue(urls); sender.cancelAll() }
        #expect(recorder.summaries.isEmpty)
        let next = try files("c.txt")
        queue.sync { sender.connected(link); sender.enqueue(next) }
        _ = try await offerID(link, name: "c.txt")  // no batch_end first
    }

    @Test func silentCancelDropsEverything() async throws {
        let urls = try files("a.txt", "b.txt")
        queue.sync { sender.enqueue(urls); sender.connected(link) }
        let id = try #require(try await complete("a.txt").batch).id
        let offerB = try await nextOffer(link, name: "b.txt")
        queue.sync { sender.cancelAll(silent: true) }
        #expect(try await link.next() == .fileCancel(transferId: offerB.id))
        #expect(outcomes.isEmpty)
        #expect(recorder.batchOutcomes == [.sent(name: "a.txt"), .failed(.outgoing, name: "b.txt", reason: "cancelled")])
        #expect(recorder.summaries.isEmpty)
        let next = try files("c.txt")
        queue.sync { sender.enqueue(next) }
        _ = try await offerID(link, name: "c.txt")  // no batch_end first
    }

    @Test func silentCancelDropsPendingEnd() async throws {
        let urls = try files("a.txt", "b.txt")
        queue.sync { sender.enqueue(urls); sender.connected(link) }
        try await complete("a.txt")
        let offerB = try await nextOffer(link, name: "b.txt")
        queue.sync { sender.disconnected(); sender.cancelAll() }  // pending cancelled end
        queue.sync { sender.cancelAll(silent: true) }
        let next = try files("c.txt")
        let link2 = FakeLink()
        queue.sync { sender.enqueue(next); sender.connected(link2) }
        _ = try await offerID(link2, name: "c.txt")  // no batch_end first
    }

    @Test func outcomesTaggedAsBatchFiles() async throws {
        let urls = try files("a.txt", "b.txt")
        queue.sync { sender.enqueue(urls); sender.connected(link) }
        try await complete("a.txt")
        try await complete("b.txt")
        #expect(outcomes.isEmpty)
        #expect(recorder.batchOutcomes == [.sent(name: "a.txt"), .sent(name: "b.txt")])
    }

    @Test func queuedCountsOnlyFilesAfterTheActiveBatch() async throws {
        let batch = try files("a.txt", "b.txt")
        let single = try files("c.txt")
        queue.sync { sender.enqueue(batch); sender.enqueue(single); sender.connected(link) }
        try await complete("a.txt") { #expect(recorder.statuses.last??.queued == 1) }
        try await complete("b.txt") { #expect(recorder.statuses.last??.queued == 1) }
        guard case .batchEnd = try await link.next() else { Issue.record("no batch_end"); return }
        try await complete("c.txt") { #expect(recorder.statuses.last??.queued == 0) }
    }

    @Test func offlineCancelAfterOfferedFileGivesSummaryAndEnd() async throws {
        let urls = try files("a.txt", "b.txt")
        queue.sync { sender.enqueue(urls); sender.connected(link) }
        let id = try #require(try await nextOffer(link, name: "a.txt").batch).id
        queue.sync { sender.disconnected() }  // dropped mid-file: offered, but no outcome
        queue.sync { sender.cancelAll() }
        #expect(recorder.summaries == [BatchSummary(direction: .outgoing, batch: id, count: 2, done: 0, cancelled: true)])
        let link2 = FakeLink()
        queue.sync { sender.connected(link2) }
        #expect(try await link2.next() == .batchEnd(batch: id, count: 2, sent: 0, cancelled: true))
    }

    @Test func cancelWhileHashingFirstFileGivesNoSummaryOrEnd() async throws {
        let hashes = HashGate()
        let sender = FileSender(queue: queue, hash: { url in try hashes.run(url) })
        sender.onOutcome = { [recorder] in recorder.record($0) }
        sender.onBatchFileOutcome = { [recorder] outcome, _ in recorder.record(batchOutcome: outcome) }
        sender.onBatchSummary = { [recorder] in recorder.record(summary: $0) }
        let urls = try files("a.txt", "b.txt")
        queue.sync { sender.enqueue(urls); sender.connected(link) }
        try await hashes.waitForStart()
        queue.sync { sender.cancelAll() }
        #expect(recorder.batchOutcomes == [.failed(.outgoing, name: "a.txt", reason: "cancelled")])
        #expect(outcomes.isEmpty)
        hashes.release()
        let next = try files("c.txt")
        queue.sync { sender.enqueue(next) }
        hashes.release()
        _ = try await offerID(link, name: "c.txt")  // no batch_end first
        #expect(recorder.summaries.isEmpty)
    }

    @Test func onlineCancelEndsBatchCancelled() async throws {
        let urls = try files("a.txt", "b.txt")
        queue.sync { sender.enqueue(urls); sender.connected(link) }
        let id = try #require(try await complete("a.txt").batch).id
        let offerB = try await nextOffer(link, name: "b.txt")
        queue.sync { sender.cancelAll() }
        #expect(try await link.next() == .fileCancel(transferId: offerB.id))
        #expect(try await link.next() == .batchEnd(batch: id, count: 2, sent: 1, cancelled: true))
        #expect(recorder.summaries == [BatchSummary(direction: .outgoing, batch: id, count: 2, done: 1, cancelled: true)])
    }

    @Test func userCancelledFileIsNotRetried() async throws {
        let urls = try files("a.txt", "b.txt", "c.txt")
        queue.sync { sender.enqueue(urls); sender.connected(link) }
        try await complete("a.txt", ok: false, reason: "hash_mismatch")
        _ = try await nextOffer(link, name: "b.txt")
        queue.sync { sender.cancelAll() }
        let summary = try #require(recorder.summaries.first)
        #expect(summary.cancelled)
        #expect(summary.failures == [BatchFailure(name: "a.txt", reason: "hash_mismatch")])
        #expect(summary.files.isEmpty)
    }

    @Test func batchOfUnreadableFilesGivesSummaryButNoBatchEnd() async throws {
        let missing = [root.appending(path: "gone1.txt"), root.appending(path: "gone2.txt")]
        queue.sync { sender.enqueue(missing); sender.connected(link) }
        let deadline = Date().addingTimeInterval(5)
        while recorder.summaries.isEmpty, Date() < deadline { try await Task.sleep(for: .milliseconds(10)) }
        let summary = try #require(recorder.summaries.first)
        #expect(recorder.summaries.count == 1)
        #expect(summary.count == 2 && summary.done == 0 && !summary.cancelled)
        #expect(summary.failures.map(\.name) == ["gone1.txt", "gone2.txt"])
        #expect(link.batchEndCount == 0)
        #expect(link.messageCount == 0)
    }

    @Test func batchEndIsWrittenBeforeLastOutcome() async throws {
        let urls = try files("a.txt", "b.txt")
        let ends = EndProbe()
        sender.onBatchFileOutcome = { [recorder] outcome, _ in
            recorder.record(batchOutcome: outcome)
            ends.sawOutcome(endsSent: self.link.batchEndCount)
        }
        queue.sync { sender.enqueue(urls); sender.connected(link) }
        try await complete("a.txt")
        try await complete("b.txt")
        #expect(ends.endsAtOutcome == [0, 1])
        #expect(recorder.events == ["outcome", "outcome", "summary"])
        #expect(outcomes.isEmpty)
    }
}

final class EndProbe: @unchecked Sendable {
    private let lock = NSLock()
    private var counts: [Int] = []
    var endsAtOutcome: [Int] { lock.withLock { counts } }
    func sawOutcome(endsSent: Int) { lock.withLock { counts.append(endsSent) } }
}
