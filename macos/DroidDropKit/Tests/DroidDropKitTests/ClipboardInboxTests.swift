import Testing
@testable import DroidDropKit

@Suite struct ClipboardInboxTests {
    @Test func acceptsPlainText() {
        #expect(ClipboardInbox.accept(mime: "text/plain", text: "hi") == "hi")
    }

    @Test func ignoresOtherMime() {
        #expect(ClipboardInbox.accept(mime: "text/html", text: "<b>hi</b>") == nil)
        #expect(ClipboardInbox.accept(mime: "text/plain; charset=utf-8", text: "hi") == nil)
    }

    @Test func ignoresEmpty() {
        #expect(ClipboardInbox.accept(mime: "text/plain", text: "") == nil)
    }

    @Test func limitCountsUTF8Bytes() {
        #expect(ClipboardInbox.accept(mime: "text/plain", text: String(repeating: "a", count: 262_144)) != nil)
        #expect(ClipboardInbox.accept(mime: "text/plain", text: String(repeating: "a", count: 262_145)) == nil)
        #expect(ClipboardInbox.accept(mime: "text/plain", text: String(repeating: "€", count: 87_382)) == nil) // 262 146 bytes
    }
}
