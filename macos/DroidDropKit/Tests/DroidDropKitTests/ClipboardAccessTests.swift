import AppKit
import Testing
@testable import DroidDropKit

@Suite struct ClipboardAccessTests {
    @Test func explainsAskAndDeny() {
        #expect(ClipboardAccess.menuMessage(for: .ask)
            == "Clipboard: set DroidDrop to Always Allow in System Settings › Privacy & Security")
        #expect(ClipboardAccess.menuMessage(for: .alwaysDeny)
            == "Clipboard access denied: allow DroidDrop in System Settings › Privacy & Security")
    }

    @Test func staysQuietWhenAllowedOrNotYetAsked() {
        #expect(ClipboardAccess.menuMessage(for: .alwaysAllow) == nil)
        #expect(ClipboardAccess.menuMessage(for: .default) == nil)
    }
}
