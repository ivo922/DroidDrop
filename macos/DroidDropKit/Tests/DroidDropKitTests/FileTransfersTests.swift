import Foundation
import Testing
@testable import DroidDropKit

@Suite struct FileTransfersTests {
    /// Server helpers (`makeServer`, `pair`, `reconnect`) and the phone identity.
    let base = DroidDropServerTests()
    let directory = temporaryDirectory()
    var destination: URL { directory.appending(path: "Downloads") }

    struct Harness {
        let server: DroidDropServer
        let port: UInt16
        let transfers: FileTransfers
        let connection: FramedConnection
        let events: EventRecorder
        let outcomes: Recorded<(Data, TransferOutcome)>
        let snapshots: Recorded<FileTransfers.Snapshot>
        let summaries: Recorded<(Data, BatchSummary)>
    }

    /// A started server with FileTransfers wired in, and a paired phone.
    func start() async throws -> Harness {
        let outcomes = Recorded<(Data, TransferOutcome)>()
        let snapshots = Recorded<FileTransfers.Snapshot>()
        let summaries = Recorded<(Data, BatchSummary)>()
        var transfers: FileTransfers!
        let (server, port) = try await base.makeServer { [directory, destination] server in
            transfers = FileTransfers(server: server, partials: PartialStore(directory: directory.appending(path: "partials")),
                                      destination: destination)
            transfers.onOutcome = { outcomes.append(($0, $1)) }
            transfers.onSnapshot = { snapshots.append($0) }
            transfers.onBatchSummary = { summaries.append(($0, $1)) }
            server.onMessage = { [transfers] in transfers!.receive($1, from: $0.fingerprint) }
        }
        let (connection, events) = try await base.pair(server, port: port)
        return Harness(server: server, port: port, transfers: transfers, connection: connection, events: events,
                       outcomes: outcomes, snapshots: snapshots, summaries: summaries)
    }

    /// Reconnects and waits for the pong, skipping (and returning) messages that arrive before it.
    func reconnect(_ h: Harness) async throws -> (FramedConnection, EventRecorder, [Message]) {
        let (connection, events) = try await base.reconnect(h.server, port: h.port)
        connection.send(.ping(id: 1))
        var before: [Message] = []
        while true {
            let message = try await events.nextMessage()
            if message == .pong(id: 1) { return (connection, events, before) }
            before.append(message)
        }
    }

    @Test func stalePartialsArePrunedWhenASessionStarts() async throws {
        // The app runs for weeks at login: pruning only at launch would let abandoned partials pile up.
        let h = try await start()
        defer { h.server.stop() }
        let partials = directory.appending(path: "partials")
        try FileManager.default.createDirectory(at: partials, withIntermediateDirectories: true)
        let stale = partials.appending(path: String(repeating: "a", count: 64) + "-10.part")
        try Data(count: 10).write(to: stale)
        try FileManager.default.setAttributes([.modificationDate: Date().addingTimeInterval(-8 * 86_400)], ofItemAtPath: stale.path)

        _ = try await reconnect(h)
        h.transfers.queue.sync {}
        #expect(!FileManager.default.fileExists(atPath: stale.path))
    }

    @Test func phoneFileLandsInDestination() async throws {
        let h = try await start()
        defer { h.server.stop() }
        let (connection, events) = (h.connection, h.events)

        let id = "0f8e2a4c-1b3d-4e5f-8a9b-0c1d2e3f4a5b"
        connection.send(.fileOffer(transferId: id, name: "hello.txt", size: 5, sha256: hex(Data("hello".utf8)), mime: "text/plain", batch: nil))
        #expect(try await events.nextMessage() == .fileAccept(transferId: id, offset: 0))
        connection.send(.chunk(transferId: id, offset: 0, data: Data("hello".utf8)))
        connection.send(.fileDone(transferId: id))
        #expect(try await events.nextMessage() == .fileResult(transferId: id, ok: true, reason: nil))
        #expect(try Data(contentsOf: destination.appending(path: "hello.txt")) == Data("hello".utf8))
        #expect(h.outcomes.values.first?.1 == .received(name: "hello.txt", url: destination.appending(path: "hello.txt")))
        #expect(h.outcomes.values.first?.0 == base.phone.fingerprint)
    }

    @Test func snapshotsReportIncomingStartAndEnd() async throws {
        let h = try await start()
        defer { h.server.stop() }

        let id = "0f8e2a4c-1b3d-4e5f-8a9b-0c1d2e3f4a5b"
        h.connection.send(.fileOffer(transferId: id, name: "hello.txt", size: 5, sha256: hex(Data("hello".utf8)), mime: "text/plain", batch: nil))
        #expect(try await h.events.nextMessage() == .fileAccept(transferId: id, offset: 0))
        h.connection.send(.chunk(transferId: id, offset: 0, data: Data("hello".utf8)))
        h.connection.send(.fileDone(transferId: id))
        _ = try await h.events.nextMessage()
        h.transfers.queue.sync {}

        let started = TransferStatus(direction: .incoming, name: "hello.txt", bytesDone: 0, size: 5)
        #expect(h.snapshots.values.contains(FileTransfers.Snapshot(incoming: started, outgoing: nil, waitingToSend: 0)))
        #expect(h.snapshots.values.last == FileTransfers.Snapshot(incoming: nil, outgoing: nil, waitingToSend: 0))
    }

    @Test func incomingTransferResumesAfterReconnect() async throws {
        let h = try await start()
        defer { h.server.stop() }
        let half = FrameLimits.maxChunkData
        let content = Data((0..<(2 * half)).map { UInt8(truncatingIfNeeded: $0 &* 31 &+ $0 / 997) })
        let sha = hex(content)

        let id1 = "0f8e2a4c-1b3d-4e5f-8a9b-0c1d2e3f4a5b"
        h.connection.send(.fileOffer(transferId: id1, name: "big.bin", size: content.count, sha256: sha, mime: "application/octet-stream", batch: nil))
        #expect(try await h.events.nextMessage() == .fileAccept(transferId: id1, offset: 0))
        h.connection.send(.chunk(transferId: id1, offset: 0, data: content.prefix(half)))
        h.connection.send(.ping(id: 5))
        #expect(try await h.events.nextMessage() == .pong(id: 5))
        h.transfers.queue.sync {}
        h.connection.cancel()

        let (connection, events, early) = try await reconnect(h)
        #expect(early.isEmpty)
        let id2 = "1a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8d"
        connection.send(.fileOffer(transferId: id2, name: "big.bin", size: content.count, sha256: sha, mime: "application/octet-stream", batch: nil))
        #expect(try await events.nextMessage() == .fileAccept(transferId: id2, offset: half))
        connection.send(.chunk(transferId: id2, offset: UInt64(half), data: content.suffix(from: half)))
        connection.send(.fileDone(transferId: id2))
        #expect(try await events.nextMessage() == .fileResult(transferId: id2, ok: true, reason: nil))
        #expect(try Data(contentsOf: destination.appending(path: "big.bin")) == content)
    }

    @Test func macFileReachesPhone() async throws {
        let h = try await start()
        defer { h.server.stop() }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appending(path: "note.txt")
        try Data("abc".utf8).write(to: url)

        h.transfers.send([url], to: base.phone.fingerprint)
        guard case let .fileOffer(id, name, size, sha256, _, _) = try await h.events.nextMessage() else {
            throw UnexpectedEvent(description: "expected file_offer")
        }
        #expect(name == "note.txt")
        #expect(size == 3)
        #expect(sha256 == hex(Data("abc".utf8)))

        h.connection.send(.fileAccept(transferId: id, offset: 0))
        #expect(try await h.events.nextFrame() == .chunk(transferId: id, offset: 0, data: Data("abc".utf8)))
        #expect(try await h.events.nextMessage() == .fileDone(transferId: id))
        h.connection.send(.fileResult(transferId: id, ok: true, reason: nil))
        try await eventually { !h.outcomes.values.isEmpty }
        #expect(h.outcomes.values.first?.1 == .sent(name: "note.txt"))
        #expect(h.outcomes.values.first?.0 == base.phone.fingerprint)
    }

    @Test func outgoingFileWaitsForReconnect() async throws {
        let h = try await start()
        defer { h.server.stop() }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appending(path: "note.txt")
        try Data("abc".utf8).write(to: url)

        h.transfers.send([url], to: base.phone.fingerprint)
        guard case let .fileOffer(firstId, _, _, sha256, _, _) = try await h.events.nextMessage() else {
            throw UnexpectedEvent(description: "expected file_offer")
        }
        h.connection.cancel()
        try await eventually { h.snapshots.values.last?.waitingToSend == 1 }
        #expect(h.snapshots.values.last?.outgoing == nil)

        let (_, events, early) = try await reconnect(h)
        var offer = early.first
        if offer == nil { offer = try await events.nextMessage() }
        guard case let .fileOffer(secondId, name, _, secondSha, _, _) = offer else {
            throw UnexpectedEvent(description: "expected file_offer, got \(String(describing: offer))")
        }
        #expect(secondId != firstId)
        #expect(name == "note.txt")
        #expect(secondSha == sha256)
    }

    /// SPEC §5 rule 6: a new session replaces the old one. Frames the old session's sender still produces must not
    /// reach the new connection (Android drops a session that gets a chunk before its pong).
    @Test func replacedSessionsFramesNeverReachTheNewSession() async throws {
        let h = try await start()
        defer { h.server.stop() }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appending(path: "big.bin")
        try Data((0..<(8 * FrameLimits.maxChunkData)).map { UInt8(truncatingIfNeeded: $0 &* 7) }).write(to: url)

        h.transfers.send([url], to: base.phone.fingerprint)
        guard case let .fileOffer(oldId, _, _, _, _, _) = try await h.events.nextMessage() else {
            throw UnexpectedEvent(description: "expected file_offer")
        }
        // Hold the transfers queue so the old session's accept is handled (and its chunks pumped) only after the new
        // session has registered: the order a slow queue or a large file produces on a real network.
        let gate = DispatchSemaphore(value: 0)
        h.transfers.queue.async { gate.wait() }
        h.connection.send(.fileAccept(transferId: oldId, offset: 0))
        try await Task.sleep(for: .milliseconds(100))
        let (connection, events) = try await base.reconnect(h.server, port: h.port)
        connection.send(.ping(id: 1))
        #expect(try await events.nextMessage() == .pong(id: 1))  // the server has registered the new session
        gate.signal()

        var sawOldFrame = false
        while let event = try? await events.next(timeout: 1) {
            guard case .frame(let frame) = event else { continue }
            switch frame {
            case .chunk(let id, _, _): if id == oldId { sawOldFrame = true }
            case .control(let data):
                switch try MessageCodec.decode(data) {
                case .fileDone(let id), .fileCancel(let id): if id == oldId { sawOldFrame = true }
                default: break
                }
            }
        }
        #expect(!sawOldFrame)
    }

    /// "Pair Again" leaves a queue for a fingerprint that is no longer paired; cancelling must still clear it.
    @Test func cancelAllSendingDropsQueuesOfUnpairedPeers() async throws {
        let h = try await start()
        defer { h.server.stop() }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appending(path: "note.txt")
        try Data("abc".utf8).write(to: url)

        h.transfers.send([url], to: Data(repeating: 7, count: 32))
        try await eventually { h.snapshots.values.last?.waitingToSend == 1 }
        h.transfers.cancelAllSending()
        try await eventually { h.snapshots.values.last?.waitingToSend == 0 }
    }

    @Test func batchProgressAloneIsThrottled() {
        func snapshot(_ done: Int, batchDone: Int) -> FileTransfers.Snapshot {
            let batch = BatchProgress(index: 1, count: 3, bytesDone: batchDone, size: 300)
            return FileTransfers.Snapshot(
                incoming: TransferStatus(direction: .incoming, name: "a", bytesDone: done, size: 100, batch: batch),
                outgoing: TransferStatus(direction: .outgoing, name: "b", bytesDone: done, size: 100, batch: batch))
        }
        #expect(FileTransfers.progressOnly(from: snapshot(10, batchDone: 110), to: snapshot(50, batchDone: 150)))
        let next = FileTransfers.Snapshot(incoming: TransferStatus(
            direction: .incoming, name: "a", bytesDone: 0, size: 100,
            batch: BatchProgress(index: 2, count: 3, bytesDone: 200, size: 300)))
        #expect(!FileTransfers.progressOnly(from: snapshot(10, batchDone: 110), to: next))
    }

    // MARK: - Batches

    private func batchInfo(_ id: String, _ index: Int, count: Int = 2) -> BatchInfo {
        BatchInfo(id: id, index: index, count: count, size: 10, before: index * 5)
    }

    /// Sends one 5-byte batch file over `connection` and waits for its `file_result`.
    private func sendBatchFile(_ connection: FramedConnection, _ events: EventRecorder, text: String, batch: BatchInfo?,
                               transferId: String = "0f8e2a4c-1b3d-4e5f-8a9b-0c1d2e3f4a5b") async throws {
        let data = Data(text.utf8)
        connection.send(.fileOffer(transferId: transferId, name: "\(text).txt", size: data.count, sha256: hex(data),
                                   mime: "text/plain", batch: batch))
        #expect(try await events.nextMessage() == .fileAccept(transferId: transferId, offset: 0))
        connection.send(.chunk(transferId: transferId, offset: 0, data: data))
        connection.send(.fileDone(transferId: transferId))
        #expect(try await events.nextMessage() == .fileResult(transferId: transferId, ok: true, reason: nil))
    }

    @Test func batchOutcomesNotForwardedPerFile() async throws {
        let h = try await start()
        defer { h.server.stop() }
        let id = "1a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8d"
        try await sendBatchFile(h.connection, h.events, text: "one11", batch: batchInfo(id, 0))
        h.transfers.queue.sync {}
        #expect(h.outcomes.values.isEmpty)
        try await sendBatchFile(h.connection, h.events, text: "plain", batch: nil, transferId: "2a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8d")
        h.transfers.queue.sync {}
        #expect(h.outcomes.values.map(\.1) == [.received(name: "plain.txt", url: destination.appending(path: "plain.txt"))])
    }

    @Test func batchEndProducesSummaryForPeer() async throws {
        let h = try await start()
        defer { h.server.stop() }
        let id = "1a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8d"
        try await sendBatchFile(h.connection, h.events, text: "one11", batch: batchInfo(id, 0),
                                transferId: "3a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8d")
        try await sendBatchFile(h.connection, h.events, text: "two22", batch: batchInfo(id, 1),
                                transferId: "4a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8d")
        h.connection.send(.batchEnd(batch: id, count: 2, sent: 2, cancelled: false))
        try await eventually { !h.summaries.values.isEmpty }
        #expect(h.summaries.values.count == 1)
        #expect(h.summaries.values.first?.0 == base.phone.fingerprint)
        #expect(h.summaries.values.first?.1 == BatchSummary(
            direction: .incoming, batch: id, count: 2, done: 2,
            files: [destination.appending(path: "one11.txt"), destination.appending(path: "two22.txt")]))
    }

    @Test func summaryEndsAfterReconnectMidBatch() async throws {
        let h = try await start()
        defer { h.server.stop() }
        let id = "1a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8d"
        try await sendBatchFile(h.connection, h.events, text: "one11", batch: batchInfo(id, 0),
                                transferId: "3a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8d")
        h.connection.cancel()
        let (connection, events, _) = try await reconnect(h)
        try await sendBatchFile(connection, events, text: "two22", batch: batchInfo(id, 1),
                                transferId: "4a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8d")
        connection.send(.batchEnd(batch: id, count: 2, sent: 2, cancelled: false))
        try await eventually { !h.summaries.values.isEmpty }
        #expect(h.summaries.values.count == 1)
        #expect(h.summaries.values.first?.1.done == 2)
        #expect(h.summaries.values.first?.1.files.count == 2)
    }

    @Test func batchEndInNewSessionEndsBatchOfOldSession() async throws {
        let h = try await start()
        defer { h.server.stop() }
        let id = "1a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8d"
        try await sendBatchFile(h.connection, h.events, text: "one11", batch: batchInfo(id, 0),
                                transferId: "3a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8d")
        try await sendBatchFile(h.connection, h.events, text: "two22", batch: batchInfo(id, 1),
                                transferId: "4a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8d")
        h.connection.cancel()
        let (connection, _, _) = try await reconnect(h)
        connection.send(.batchEnd(batch: id, count: 2, sent: 2, cancelled: false))
        try await eventually { !h.summaries.values.isEmpty }
        #expect(h.summaries.values.count == 1)
        #expect(h.summaries.values.first?.1.done == 2)
        #expect(h.summaries.values.first?.1.files
                == [destination.appending(path: "one11.txt"), destination.appending(path: "two22.txt")])
    }

    @Test func reofferOfSavedFileCountsOnceInSummary() async throws {
        let h = try await start()
        defer { h.server.stop() }
        let id = "1a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8d"
        try await sendBatchFile(h.connection, h.events, text: "one11", batch: batchInfo(id, 0),
                                transferId: "3a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8d")
        let again = "5a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8d"
        let data = Data("one11".utf8)
        h.connection.send(.fileOffer(transferId: again, name: "one11.txt", size: data.count, sha256: hex(data),
                                     mime: "text/plain", batch: batchInfo(id, 0)))
        #expect(try await h.events.nextMessage() == .fileAccept(transferId: again, offset: data.count))
        h.connection.send(.fileDone(transferId: again))
        #expect(try await h.events.nextMessage() == .fileResult(transferId: again, ok: true, reason: nil))
        h.connection.send(.batchEnd(batch: id, count: 2, sent: 1, cancelled: false))
        try await eventually { !h.summaries.values.isEmpty }
        #expect(h.summaries.values.first?.1.files == [destination.appending(path: "one11.txt")])
        // The completed path's outcome is tagged with the re-offer's batch, so it is not forwarded per file.
        #expect(h.outcomes.values.isEmpty)
    }

    @Test func incomingFallbackSummaryForwarded() async throws {
        let h = try await start()
        defer { h.server.stop() }
        let id = "1a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8d"
        try await sendBatchFile(h.connection, h.events, text: "one11", batch: batchInfo(id, 0),
                                transferId: "3a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8d")
        try await sendBatchFile(h.connection, h.events, text: "plain", batch: nil,
                                transferId: "4a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8d")
        try await eventually { !h.outcomes.values.isEmpty }
        // The summary is raised by the offer, before the plain file's outcome.
        #expect(h.summaries.values.count == 1)
        #expect(h.summaries.values.first?.0 == base.phone.fingerprint)
        #expect(h.summaries.values.first?.1 == BatchSummary(
            direction: .incoming, batch: id, count: 2, done: 1, silent: true,
            files: [destination.appending(path: "one11.txt")]))
        #expect(h.outcomes.values.map(\.1) == [.received(name: "plain.txt", url: destination.appending(path: "plain.txt"))])
    }

    @Test func sentBatchSummaryForwardedForPeer() async throws {
        let h = try await start()
        defer { h.server.stop() }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var urls: [URL] = []
        for name in ["s1.txt", "s2.txt"] {
            let url = directory.appending(path: name)
            try Data(name.utf8).write(to: url)
            urls.append(url)
        }
        h.transfers.send(urls, to: base.phone.fingerprint)
        for _ in urls {
            guard case let .fileOffer(id, _, _, _, _, batch) = try await h.events.nextMessage() else {
                throw UnexpectedEvent(description: "expected file_offer")
            }
            #expect(batch?.count == 2)
            h.connection.send(.fileAccept(transferId: id, offset: 0))
            #expect(try await h.events.nextMessage() == .fileDone(transferId: id))
            h.connection.send(.fileResult(transferId: id, ok: true, reason: nil))
        }
        guard case let .batchEnd(batch, count, sent, cancelled) = try await h.events.nextMessage() else {
            throw UnexpectedEvent(description: "expected batch_end")
        }
        #expect((count, sent, cancelled) == (2, 2, false))
        try await eventually { !h.summaries.values.isEmpty }
        #expect(h.summaries.values.count == 1)
        #expect(h.summaries.values.first?.0 == base.phone.fingerprint)
        let summary = try #require(h.summaries.values.first?.1)
        #expect(summary.direction == .outgoing)
        #expect(summary.batch == batch)
        #expect(summary.count == 2)
        #expect(summary.done == 2)
        h.transfers.queue.sync {}
        #expect(h.outcomes.values.isEmpty)
    }

    @Test func silentCancelDropsOpenIncomingBatch() async throws {
        let h = try await start()
        defer { h.server.stop() }
        let id = "1a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8d"
        try await sendBatchFile(h.connection, h.events, text: "one11", batch: batchInfo(id, 0))
        h.transfers.cancelSending(to: base.phone.fingerprint, silent: true)
        h.transfers.queue.sync {}
        h.connection.send(.batchEnd(batch: id, count: 2, sent: 1, cancelled: false))
        h.connection.send(.ping(id: 9))
        #expect(try await h.events.nextMessage() == .pong(id: 9))
        h.transfers.queue.sync {}
        #expect(h.summaries.values.isEmpty)
    }
}
