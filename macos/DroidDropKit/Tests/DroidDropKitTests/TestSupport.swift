import Foundation
import Network
@testable import DroidDropKit

struct TimeoutError: Error {}
struct UnexpectedEvent: Error { let description: String }

func temporaryDirectory() -> URL {
    FileManager.default.temporaryDirectory.appending(path: "droiddrop-tests-\(UUID().uuidString)")
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

enum TestClient {
    /// `react` runs on the connection's queue after each event is recorded.
    static func connect(port: UInt16, identity: Identity, pinnedServer: Data,
                        react: ((FramedConnection, FramedConnection.Event) -> Void)? = nil) throws -> (FramedConnection, EventRecorder) {
        let queue = DispatchQueue(label: "test.client.\(UUID().uuidString)")
        let parameters = TLSParameters.make(identity: try identity.secIdentity(), queue: queue) { $0 == pinnedServer }
        let connection = NWConnection(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: port)!, using: parameters)
        let framed = FramedConnection(connection: connection, queue: queue)
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
