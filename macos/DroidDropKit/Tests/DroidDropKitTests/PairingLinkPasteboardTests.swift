import AppKit
import Testing
@testable import DroidDropKit

@Suite struct PairingLinkPasteboardTests {
    let pasteboard = NSPasteboard(name: .init("dev.ivo.droiddrop.tests.\(UUID().uuidString)"))

    @Test func clearsItsOwnCopy() {
        defer { pasteboard.releaseGlobally() }
        let copied = PairingLinkPasteboard.copy("droiddrop://pair?secret=x", to: pasteboard)
        PairingLinkPasteboard.clear(ifUnchangedSince: copied, on: pasteboard)
        #expect(pasteboard.string(forType: .string) == nil)
    }

    @Test func keepsWhatTheUserCopiedAfterwards() {
        defer { pasteboard.releaseGlobally() }
        let copied = PairingLinkPasteboard.copy("droiddrop://pair?secret=x", to: pasteboard)
        pasteboard.clearContents(); pasteboard.setString("user copy", forType: .string)
        PairingLinkPasteboard.clear(ifUnchangedSince: copied, on: pasteboard)
        #expect(pasteboard.string(forType: .string) == "user copy")
    }
}
