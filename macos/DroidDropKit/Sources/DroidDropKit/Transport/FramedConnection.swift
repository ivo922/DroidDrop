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

    public static let defaultLingerTimeout: TimeInterval = 2

    public let connection: NWConnection
    /// Called on the connection's queue. Cleared after `.closed`.
    public var onEvent: ((Event) -> Void)?

    private let queue: DispatchQueue
    private let lingerTimeout: TimeInterval
    private var linger: DispatchWorkItem?
    private var decoder = FrameDecoder()
    private var finished = false
    private let lock = NSLock()
    /// Set by `close()`/`cancel()` from any thread; a local close reports `.closed(nil)`.
    private var locallyClosed = false

    /// `lingerTimeout` bounds how long `close()` waits for the peer's EOF before cancelling.
    public init(connection: NWConnection, queue: DispatchQueue, lingerTimeout: TimeInterval = defaultLingerTimeout) {
        self.connection = connection
        self.queue = queue
        self.lingerTimeout = lingerTimeout
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
        write(frame)
    }

    /// Sends everything queued so far plus `final`, then marks the stream complete. Inbound data is drained
    /// (not delivered) until the peer's EOF or `lingerTimeout`, then the connection is cancelled; cancelling
    /// earlier can reset the socket and lose the last frames. Network's TLS does not forward the half-close,
    /// so a peer that never closes on its own costs the full linger. Later calls are no-ops.
    public func close(final messages: [Message] = []) {
        guard markLocallyClosed() else { return }
        for message in messages {
            if let data = try? MessageCodec.encode(message) { write(.control(data)) }
        }
        connection.send(content: nil, contentContext: .finalMessage, isComplete: true, completion: .contentProcessed { error in
            if let error { self.fail(error) }
        })
        queue.async { self.startLinger() }
    }

    public func cancel() {
        markLocallyClosed()
        connection.cancel()
    }

    private func write(_ frame: Frame) {
        connection.send(content: FrameEncoder.encode(frame), completion: .contentProcessed { error in
            if let error { self.fail(error) }
        })
    }

    private func startLinger() {
        guard !finished else { return }
        let item = DispatchWorkItem { [weak self] in self?.connection.cancel() }
        linger = item
        queue.asyncAfter(deadline: .now() + lingerTimeout, execute: item)
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
            guard !self.finished else { return }
            if let data, !data.isEmpty, !self.isLocallyClosed {
                do {
                    for frame in try self.decoder.append(data) {
                        guard !self.finished else { return }
                        guard !self.isLocallyClosed else { break }
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
        linger?.cancel()
        linger = nil
        let handler = onEvent
        onEvent = nil
        handler?(.closed(isLocallyClosed ? nil : error))
    }
}
