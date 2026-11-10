import Testing
@testable import DroidDropKit

@Suite struct ClipboardOutboxTests {
    func makeOutbox() -> ClipboardOutbox {
        var next = 0
        return ClipboardOutbox(makeID: {
            next += 1
            return "id-\(next)"
        })
    }

    func plain(_ text: String?, extra: [String] = []) -> PasteboardContents {
        PasteboardContents(types: ["public.utf8-plain-text"] + extra, text: text)
    }

    @Test func firstPollOnlyRecordsBaseline() {
        var outbox = makeOutbox()
        #expect(outbox.poll(changeCount: 1) { plain("at launch") } == nil)
        #expect(outbox.poll(changeCount: 1) { plain("at launch") } == nil)
        #expect(outbox.poll(changeCount: 2) { plain("copied") } == .clip(id: "id-1", mime: "text/plain", text: "copied"))
    }

    @Test func unchangedCountDoesNotReadContents() {
        var outbox = makeOutbox()
        var reads = 0
        _ = outbox.poll(changeCount: 5) { reads += 1; return plain("x") }
        _ = outbox.poll(changeCount: 5) { reads += 1; return plain("x") }
        #expect(reads <= 1)
    }

    @Test func skipsConcealedAndTransient() {
        var outbox = makeOutbox()
        _ = outbox.poll(changeCount: 0) { plain(nil) }
        #expect(outbox.poll(changeCount: 1) { plain("hunter2", extra: ["org.nspasteboard.ConcealedType"]) } == nil)
        #expect(outbox.poll(changeCount: 2) { plain("otp", extra: ["org.nspasteboard.TransientType"]) } == nil)
        #expect(outbox.poll(changeCount: 3) { plain("fine") } == .clip(id: "id-1", mime: "text/plain", text: "fine"))
    }

    @Test func skipsFinderFileCopies() {
        var outbox = makeOutbox()
        _ = outbox.poll(changeCount: 0) { plain(nil) }
        #expect(outbox.poll(changeCount: 1) { plain("report.pdf", extra: ["public.file-url"]) } == nil)
    }

    @Test func skipsEmptyAndNonText() {
        var outbox = makeOutbox()
        _ = outbox.poll(changeCount: 0) { plain(nil) }
        #expect(outbox.poll(changeCount: 1) { PasteboardContents(types: ["public.png"], text: nil) } == nil)
        #expect(outbox.poll(changeCount: 2) { plain("") } == nil)
    }

    @Test func limitCountsUTF8Bytes() {
        var outbox = makeOutbox()
        _ = outbox.poll(changeCount: 0) { plain(nil) }
        #expect(outbox.poll(changeCount: 1) { plain(String(repeating: "a", count: 262_144)) } != nil)
        #expect(outbox.poll(changeCount: 2) { plain(String(repeating: "a", count: 262_145)) } == nil)
        #expect(outbox.poll(changeCount: 3) { plain(String(repeating: "€", count: 87_382)) } == nil) // 262 146 bytes
        #expect(outbox.poll(changeCount: 4) { plain(String(repeating: "€", count: 87_381)) } != nil) // 262 143 bytes
    }

    @Test func skipsClipsWhoseFrameWouldExceedTheLimit() {
        var outbox = makeOutbox()
        _ = outbox.poll(changeCount: 0) { plain(nil) }
        // 100 000 bytes of text, but each U+0001 is escaped to six bytes of JSON: 600 000 > 524 288.
        #expect(outbox.poll(changeCount: 1) { plain(String(repeating: "\u{1}", count: 100_000)) } == nil)
        #expect(outbox.poll(changeCount: 2) { plain(String(repeating: "\n", count: 200_000)) } != nil)
    }

    @Test func skipsTextJustReceivedFromPhone() {
        var outbox = makeOutbox()
        _ = outbox.poll(changeCount: 0) { plain(nil) }
        outbox.noteRemote(text: "from phone")
        #expect(outbox.poll(changeCount: 1) { plain("from phone") } == nil)
        #expect(outbox.poll(changeCount: 2) { plain("other") } == .clip(id: "id-1", mime: "text/plain", text: "other"))
    }
}
