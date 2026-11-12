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
    }

    /// A started server with FileTransfers wired in, and a paired phone.
    func start() async throws -> Harness {
        let outcomes = Recorded<(Data, TransferOutcome)>()
        let snapshots = Recorded<FileTransfers.Snapshot>()
        var transfers: FileTransfers!
        let (server, port) = try await base.makeServer { [directory, destination] server in
            transfers = FileTransfers(server: server, partials: PartialStore(directory: directory.appending(path: "partials")),
                                      destination: destination)
            transfers.onOutcome = { outcomes.append(($0, $1)) }
            transfers.onSnapshot = { snapshots.append($0) }
            server.onMessage = { [transfers] in transfers!.receive($1, from: $0.fingerprint) }
        }
        let (connection, events) = try await base.pair(server, port: port)
        return Harness(server: server, port: port, transfers: transfers, connection: connection, events: events,
                       outcomes: outcomes, snapshots: snapshots)
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

    @Test func phoneFileLandsInDestination() async throws {
        let h = try await start()
        defer { h.server.stop() }
        let (connection, events) = (h.connection, h.events)

        let id = "0f8e2a4c-1b3d-4e5f-8a9b-0c1d2e3f4a5b"
        connection.send(.fileOffer(transferId: id, name: "hello.txt", size: 5, sha256: hex(Data("hello".utf8)), mime: "text/plain"))
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
        h.connection.send(.fileOffer(transferId: id, name: "hello.txt", size: 5, sha256: hex(Data("hello".utf8)), mime: "text/plain"))
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
        h.connection.send(.fileOffer(transferId: id1, name: "big.bin", size: content.count, sha256: sha, mime: "application/octet-stream"))
        #expect(try await h.events.nextMessage() == .fileAccept(transferId: id1, offset: 0))
        h.connection.send(.chunk(transferId: id1, offset: 0, data: content.prefix(half)))
        h.connection.send(.ping(id: 5))
        #expect(try await h.events.nextMessage() == .pong(id: 5))
        h.transfers.queue.sync {}
        h.connection.cancel()

        let (connection, events, early) = try await reconnect(h)
        #expect(early.isEmpty)
        let id2 = "1a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8d"
        connection.send(.fileOffer(transferId: id2, name: "big.bin", size: content.count, sha256: sha, mime: "application/octet-stream"))
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
        guard case let .fileOffer(id, name, size, sha256, _) = try await h.events.nextMessage() else {
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
        guard case let .fileOffer(firstId, _, _, sha256, _) = try await h.events.nextMessage() else {
            throw UnexpectedEvent(description: "expected file_offer")
        }
        h.connection.cancel()
        try await eventually { h.snapshots.values.last?.waitingToSend == 1 }
        #expect(h.snapshots.values.last?.outgoing == nil)

        let (_, events, early) = try await reconnect(h)
        var offer = early.first
        if offer == nil { offer = try await events.nextMessage() }
        guard case let .fileOffer(secondId, name, _, secondSha, _) = offer else {
            throw UnexpectedEvent(description: "expected file_offer, got \(String(describing: offer))")
        }
        #expect(secondId != firstId)
        #expect(name == "note.txt")
        #expect(secondSha == sha256)
    }
}
