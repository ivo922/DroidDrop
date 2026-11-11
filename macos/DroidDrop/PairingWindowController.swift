import AppKit
import DroidDropKit
import SwiftUI

@MainActor
final class PairingWindowController: NSObject, NSWindowDelegate {
    private let model: AppModel
    private var window: NSWindow?

    init(model: AppModel) {
        self.model = model
    }

    func show(replacing peer: Peer? = nil) {
        model.startPairing(replacing: peer)
        if window == nil {
            let window = NSWindow(
                contentRect: NSRect(x: 0, y: 0, width: 340, height: 480),
                styleMask: [.titled, .closable],
                backing: .buffered,
                defer: false
            )
            window.title = "Pair New Phone"
            window.isReleasedWhenClosed = false
            window.isRestorable = false
            window.delegate = self
            window.contentViewController = NSHostingController(
                rootView: PairingView(model: model) { [weak window] in window?.close() }
            )
            window.setContentSize(NSSize(width: 340, height: 480))
            window.center()
            self.window = window
        }
        NSApp.activate()
        window?.makeKeyAndOrderFront(nil)
    }

    func windowWillClose(_ notification: Notification) {
        model.cancelPairing()
    }
}
