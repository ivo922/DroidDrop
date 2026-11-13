import AppKit

/// Polls a pasteboard on the main run loop and hands each new clip to `send`.
/// Run it only while a phone is connected: reading the pasteboard may raise a macOS access alert.
@MainActor
public final class ClipboardMonitor {
    public static let pollInterval: TimeInterval = 0.5

    private let pasteboard: NSPasteboard
    private var outbox: ClipboardOutbox
    private let send: (Message) -> Void
    private(set) var timer: Timer?

    public init(pasteboard: NSPasteboard = .general, outbox: ClipboardOutbox = ClipboardOutbox(),
                send: @escaping (Message) -> Void) {
        self.pasteboard = pasteboard
        self.outbox = outbox
        self.send = send
    }

    /// Idempotent. Each start takes a new baseline, so copies made while stopped are never sent.
    public func start() {
        guard timer == nil else { return }
        outbox.resetBaseline()
        tick()
        let timer = Timer.scheduledTimer(withTimeInterval: Self.pollInterval, repeats: true) { [weak self] timer in
            MainActor.assumeIsolated {
                guard let self else { return timer.invalidate() }
                self.tick()
            }
        }
        timer.tolerance = 0.1
        self.timer = timer
    }

    public func stop() {
        timer?.invalidate()
        timer = nil
    }

    /// Writes a clip from the phone and records it so it is not sent back. False when the pasteboard refused it.
    @discardableResult
    public func apply(_ text: String) -> Bool {
        pasteboard.clearContents()
        guard pasteboard.setString(text, forType: .string) else { return false }
        outbox.noteApplied(text: text, changeCount: pasteboard.changeCount)
        return true
    }

    func tick() {
        let pasteboard = pasteboard
        if let clip = outbox.poll(changeCount: pasteboard.changeCount, contents: { .read(from: pasteboard) }) {
            send(clip)
        }
    }
}
