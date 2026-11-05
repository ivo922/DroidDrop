import Foundation
import Network

/// NWConnection carrying DroidDrop frames. Handlers capture `self` strongly; NWConnection releases them once cancelled.
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
                self.finish(error)
                self.connection.cancel()
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
            cancel()
        }
    }

    public func send(_ frame: Frame) {
        connection.send(content: FrameEncoder.encode(frame), completion: .contentProcessed { error in
            if let error {
                self.finish(error)
                self.connection.cancel()
            }
        })
    }

    /// Sends everything queued so far, then closes.
    public func close() {
        connection.send(content: nil, contentContext: .finalMessage, isComplete: true, completion: .contentProcessed { _ in
            self.connection.cancel()
        })
    }

    public func cancel() {
        connection.cancel()
    }

    private func receive() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 256 * 1024) { data, _, isComplete, error in
            guard !self.finished else { return }
            if let data, !data.isEmpty {
                do {
                    for frame in try self.decoder.append(data) {
                        self.onEvent?(.frame(frame))
                    }
                } catch {
                    self.finish(error)
                    self.connection.cancel()
                    return
                }
            }
            if let error {
                self.finish(error)
                self.connection.cancel()
            } else if isComplete {
                self.finish(nil)
                self.connection.cancel()
            } else {
                self.receive()
            }
        }
    }

    private func finish(_ error: Error?) {
        guard !finished else { return }
        finished = true
        let handler = onEvent
        onEvent = nil
        handler?(.closed(error))
    }
}
