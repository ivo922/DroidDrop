import CryptoKit
import Foundation
import Network
@testable import DroidDropKit

struct TimeoutError: Error {}
struct UnexpectedEvent: Error { let description: String }

func temporaryDirectory() -> URL {
    FileManager.default.temporaryDirectory.appending(path: "droiddrop-tests-\(UUID().uuidString)")
}

/// Lowercase hex SHA-256 of `data`.
func hex(_ data: Data) -> String {
    SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
}

final class EventRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private var events: [FramedConnection.Event] = []

    func record(_ event: FramedConnection.Event) {
        lock.withLock { events.append(event) }
    }

    func next(timeout: TimeInterval = 5) async throws -> FramedConnection.Event {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if let event = lock.withLock({ events.isEmpty ? nil : events.removeFirst() }) { return event }
            try await Task.sleep(for: .milliseconds(10))
        }
        throw TimeoutError()
    }

    func nextMessage() async throws -> Message {
        while true {
            switch try await next() {
            case .ready, .frame(.chunk):
                continue
            case .frame(.control(let data)):
                return try MessageCodec.decode(data)
            case .closed(let error):
                throw UnexpectedEvent(description: "closed: \(String(describing: error))")
            }
        }
    }

    /// The next frame of either kind; skips `.ready`, throws on `.closed`.
    func nextFrame() async throws -> Frame {
        while true {
            switch try await next() {
            case .ready:
                continue
            case .frame(let frame):
                return frame
            case .closed(let error):
                throw UnexpectedEvent(description: "closed: \(String(describing: error))")
            }
        }
    }

    func expectClosed() async throws {
        while true {
            if case .closed = try await next() { return }
        }
    }

    func expectClosedWithoutFrames() async throws {
        while true {
            switch try await next() {
            case .ready: continue
            case .frame(let frame): throw UnexpectedEvent(description: "unexpected frame \(frame)")
            case .closed: return
            }
        }
    }
}

/// Values recorded from callbacks on other queues.
final class Recorded<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var stored: [Value] = []
    var values: [Value] { lock.withLock { stored } }
    func append(_ value: Value) { lock.withLock { stored.append(value) } }
}

/// Polls `condition` every 10 ms until it holds; throws `TimeoutError` after `timeout`.
func eventually(timeout: TimeInterval = 5, _ condition: () -> Bool) async throws {
    let deadline = Date().addingTimeInterval(timeout)
    while !condition() {
        guard Date() < deadline else { throw TimeoutError() }
        try await Task.sleep(for: .milliseconds(10))
    }
}

/// A plain TCP connection that never starts TLS, like a silent device on the LAN. `closed` once the server drops it.
final class RawClient: @unchecked Sendable {
    private let connection: NWConnection
    private let queue = DispatchQueue(label: "test.raw.\(UUID().uuidString)")
    private let lock = NSLock()
    private var isClosed = false
    var closed: Bool { lock.withLock { isClosed } }

    init(port: UInt16) {
        connection = NWConnection(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: port)!, using: .tcp)
        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .failed, .cancelled: self?.markClosed()
            default: break
            }
        }
        connection.start(queue: queue)
        receive()
    }

    func cancel() { connection.cancel() }

    private func receive() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { [weak self] _, _, isComplete, error in
            if isComplete || error != nil { self?.markClosed() } else { self?.receive() }
        }
    }

    private func markClosed() { lock.withLock { isClosed = true } }
}

enum TestClient {
    /// `react` runs on the connection's queue after each event is recorded.
    static func connect(port: UInt16, identity: Identity, pinnedServer: Data,
                        lingerTimeout: TimeInterval = FramedConnection.defaultLingerTimeout,
                        react: ((FramedConnection, FramedConnection.Event) -> Void)? = nil) throws -> (FramedConnection, EventRecorder) {
        let queue = DispatchQueue(label: "test.client.\(UUID().uuidString)")
        let parameters = TLSParameters.make(identity: try identity.secIdentity(), queue: queue) { $0 == pinnedServer }
        let connection = NWConnection(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: port)!, using: parameters)
        let framed = FramedConnection(connection: connection, queue: queue, lingerTimeout: lingerTimeout)
        let recorder = EventRecorder()
        framed.onEvent = { [weak framed] event in
            recorder.record(event)
            if let framed { react?(framed, event) }
        }
        framed.start()
        return (framed, recorder)
    }
}

/// Bare TLS listener for transport tests (no session logic).
/// Handlers hold `self` weakly: Network may deliver events after a test has released the server.
final class LoopbackServer: @unchecked Sendable {
    let events = EventRecorder()
    private let queue = DispatchQueue(label: "test.loopback.server")
    private let listener: NWListener
    private var connections: [FramedConnection] = []
    private var fingerprints: [Data] = []
    private var startContinuation: CheckedContinuation<UInt16, Error>?

    var peerFingerprints: [Data] { queue.sync { fingerprints } }
    var accepted: [FramedConnection] { queue.sync { connections } }

    init(identity: Identity, acceptPeer: @escaping (Data) -> Bool) throws {
        listener = try NWListener(using: TLSParameters.make(identity: try identity.secIdentity(), queue: queue, acceptPeer: acceptPeer), on: .any)
        listener.newConnectionHandler = { [weak self] connection in
            guard let self else {
                connection.cancel()
                return
            }
            let framed = FramedConnection(connection: connection, queue: self.queue)
            framed.onEvent = { [weak self] event in
                guard let self else { return }
                if case .ready = event, let fingerprint = TLSParameters.peerFingerprint(of: connection) {
                    self.fingerprints.append(fingerprint)
                }
                self.events.record(event)
            }
            self.connections.append(framed)
            framed.start()
        }
    }

    func start() async throws -> UInt16 {
        try await withCheckedThrowingContinuation { continuation in
            startContinuation = continuation
            listener.stateUpdateHandler = { [weak self] state in
                guard let self, let continuation = self.startContinuation else { return }
                switch state {
                case .ready:
                    self.startContinuation = nil
                    continuation.resume(returning: self.listener.port!.rawValue)
                case .failed(let error):
                    self.startContinuation = nil
                    continuation.resume(throwing: error)
                case .cancelled:
                    self.startContinuation = nil
                    continuation.resume(throwing: CancellationError())
                default:
                    break
                }
            }
            listener.start(queue: queue)
        }
    }

    deinit {
        listener.cancel()
        connections.forEach { $0.cancel() }
    }
}
