import AppKit
import SwiftUI

@MainActor
final class UpdateWindowController: NSObject {
    private let updater: UpdateController
    private var window: NSWindow?

    init(updater: UpdateController) {
        self.updater = updater
    }

    func show() {
        if window == nil {
            let window = NSWindow(
                contentRect: NSRect(x: 0, y: 0, width: 380, height: 300),
                styleMask: [.titled, .closable],
                backing: .buffered,
                defer: false
            )
            window.title = "Software Update"
            window.isReleasedWhenClosed = false
            window.isRestorable = false
            window.contentViewController = NSHostingController(
                rootView: UpdateView(updater: updater) { [weak window] in window?.close() }
            )
            window.center()
            self.window = window
        }
        NSApp.activate()
        window?.makeKeyAndOrderFront(nil)
    }
}
