import Foundation
import Network

/// NWConnection carrying DroidDrop frames. Handlers capture `self` strongly; NWConnection releases them once cancelled.
/// There is no deinit cleanup: callers must `cancel()` or `close()` every started connection.
public final class FramedConnection: @unchecked Sendable {
    public enum Event {
        case ready
        case frame(Frame)
        case closed(Error?)
    }

    public let connection: NWConnection
    /// Called on the connection's queue. Cleared after `.closed`.
    public var onEvent: ((Event) -> Void)?

    private let queue: DispatchQueue
    private var decoder = FrameDecoder()
    private var finished = false
    private let lock = NSLock()
    /// Set by `close()`/`cancel()` from any thread; a local close reports `.closed(nil)`.
    private var locallyClosed = false

    public init(connection: NWConnection, queue: DispatchQueue) {
        self.connection = connection
        self.queue = queue
    }

    public func start() {
        connection.stateUpdateHandler = { state in
            switch state {
            case .ready:
                self.onEvent?(.ready)
                self.receive()
            case .waiting(let error), .failed(let error):
                self.fail(error)
            case .cancelled:
                self.finish(nil)
            default:
                break
            }
        }
        connection.start(queue: queue)
    }

    public func send(_ message: Message) {
        do {
            send(.control(try MessageCodec.encode(message)))
        } catch {
            queue.async { self.fail(error) }
        }
    }

    /// Dropped silently once `close()` or `cancel()` has been called.
    public func send(_ frame: Frame) {
        guard !isLocallyClosed else { return }
        connection.send(content: FrameEncoder.encode(frame), completion: .contentProcessed { error in
            if let error { self.fail(error) }
        })
    }

    /// Sends everything queued so far, then closes. Later calls are no-ops.
    public func close() {
        guard markLocallyClosed() else { return }
        connection.send(content: nil, contentContext: .finalMessage, isComplete: true, completion: .contentProcessed { _ in
            self.connection.cancel()
        })
    }

    public func cancel() {
        markLocallyClosed()
        connection.cancel()
    }

    private var isLocallyClosed: Bool { lock.withLock { locallyClosed } }

    /// Returns false if the connection was already locally closed.
    @discardableResult
    private func markLocallyClosed() -> Bool {
        lock.withLock {
            defer { locallyClosed = true }
            return !locallyClosed
        }
    }

    private func receive() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 256 * 1024) { data, _, isComplete, error in
            guard !self.finished, !self.isLocallyClosed else { return }
            if let data, !data.isEmpty {
                do {
                    for frame in try self.decoder.append(data) {
                        guard !self.finished, !self.isLocallyClosed else { return }
                        self.onEvent?(.frame(frame))
                    }
                } catch {
                    self.fail(error)
                    return
                }
            }
            if let error {
                self.fail(error)
            } else if isComplete {
                self.finish(nil)
                self.connection.cancel()
            } else {
                self.receive()
            }
        }
    }

    private func fail(_ error: Error) {
        finish(error)
        connection.cancel()
    }

    private func finish(_ error: Error?) {
        guard !finished else { return }
        finished = true
        let handler = onEvent
        onEvent = nil
        handler?(.closed(isLocallyClosed ? nil : error))
    }
}
