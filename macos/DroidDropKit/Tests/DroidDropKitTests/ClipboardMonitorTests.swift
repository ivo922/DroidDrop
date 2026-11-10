import AppKit
import Testing
@testable import DroidDropKit

final class Sent {
    var messages: [Message] = []
}

@MainActor @Suite struct ClipboardMonitorTests {
    func makeMonitor(isActive: @escaping @MainActor () -> Bool = { true }) -> (NSPasteboard, ClipboardMonitor, Sent) {
        let pasteboard = NSPasteboard(name: .init("dev.ivo.droiddrop.tests.\(UUID().uuidString)"))
        let sent = Sent()
        let monitor = ClipboardMonitor(pasteboard: pasteboard, isActive: isActive) { sent.messages.append($0) }
        return (pasteboard, monitor, sent)
    }

    @Test func firstTickOnlyRecordsBaseline() {
        let (pasteboard, monitor, sent) = makeMonitor()
        defer { pasteboard.releaseGlobally() }
        pasteboard.clearContents(); pasteboard.setString("before launch", forType: .string)
        monitor.tick()
        #expect(sent.messages.isEmpty)
    }

    @Test func sendsTextCopiedAfterStart() {
        let (pasteboard, monitor, sent) = makeMonitor()
        defer { pasteboard.releaseGlobally() }
        monitor.tick()
        pasteboard.clearContents(); pasteboard.setString("hello mac", forType: .string)
        monitor.tick()
        guard case .clip(let id, "text/plain", "hello mac")? = sent.messages.first else {
            Issue.record("no clip: \(sent.messages)"); return
        }
        #expect(WireFormat.isUUID(id))
        monitor.tick()
        #expect(sent.messages.count == 1)
    }

    @Test func skipsConcealedItemOnRealPasteboard() {
        let (pasteboard, monitor, sent) = makeMonitor()
        defer { pasteboard.releaseGlobally() }
        monitor.tick()
        let concealed = NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType")
        pasteboard.declareTypes([.string, concealed], owner: nil)
        pasteboard.setString("hunter2", forType: .string)
        pasteboard.setData(Data(), forType: concealed)
        monitor.tick()
        #expect(sent.messages.isEmpty)
    }

    @Test func copiesWhileInactiveAreNeitherSentNorReplayed() {
        var active = false
        let (pasteboard, monitor, sent) = makeMonitor(isActive: { active })
        defer { pasteboard.releaseGlobally() }
        monitor.tick()
        pasteboard.clearContents(); pasteboard.setString("no phone yet", forType: .string)
        monitor.tick()
        active = true
        monitor.tick()
        #expect(sent.messages.isEmpty)
        pasteboard.clearContents(); pasteboard.setString("phone here", forType: .string)
        monitor.tick()
        #expect(sent.messages.count == 1)
    }

    @Test func pairingLinkCopyIsNotSent() {
        let (pasteboard, monitor, sent) = makeMonitor()
        defer { pasteboard.releaseGlobally() }
        monitor.tick()
        PairingLinkPasteboard.copy("droiddrop://pair?secret=x", to: pasteboard)
        monitor.tick()
        #expect(sent.messages.isEmpty)
        #expect(pasteboard.string(forType: .string) == "droiddrop://pair?secret=x")
    }
}
