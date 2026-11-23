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

    func send(_ message: Message) {
        lock.withLock { messages.append(message) }
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
        queue.sync { sender.enqueue([a, b]); sender.connected(link) }
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
        queue.sync { sender.enqueue([a, b]); sender.connected(link) }
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
        queue.sync { sender.enqueue(urls); sender.connected(link) }
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
        queue.sync { sender.enqueue([missing, file]); sender.connected(link) }
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
}
