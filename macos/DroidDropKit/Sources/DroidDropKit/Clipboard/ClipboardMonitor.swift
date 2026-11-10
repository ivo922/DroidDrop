import AppKit

/// Polls a pasteboard on the main run loop and hands each new clip to `send`.
@MainActor
public final class ClipboardMonitor {
    public static let pollInterval: TimeInterval = 0.5

    private let pasteboard: NSPasteboard
    private var outbox: ClipboardOutbox
    private let isActive: @MainActor () -> Bool
    private let send: (Message) -> Void
    private var timer: Timer?

    public init(pasteboard: NSPasteboard = .general, outbox: ClipboardOutbox = ClipboardOutbox(),
                isActive: @escaping @MainActor () -> Bool = { true }, send: @escaping (Message) -> Void) {
        self.pasteboard = pasteboard
        self.outbox = outbox
        self.isActive = isActive
        self.send = send
    }

    public func start() {
        guard timer == nil else { return }
        tick()
        let timer = Timer.scheduledTimer(withTimeInterval: Self.pollInterval, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.tick() }
        }
        timer.tolerance = 0.1
        self.timer = timer
    }

    public func stop() {
        timer?.invalidate()
        timer = nil
    }

    /// Contents are read only while `isActive`: macOS may alert on each programmatic read of the general pasteboard.
    func tick() {
        let pasteboard = pasteboard
        let active = isActive()
        let clip = outbox.poll(changeCount: pasteboard.changeCount) {
            active ? .read(from: pasteboard) : PasteboardContents(types: [], text: nil)
        }
        if let clip { send(clip) }
    }
}
