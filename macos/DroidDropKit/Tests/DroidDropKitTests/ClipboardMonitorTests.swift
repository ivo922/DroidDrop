import AppKit
import Testing
@testable import DroidDropKit

final class Sent {
    var messages: [Message] = []
}

@MainActor @Suite struct ClipboardMonitorTests {
    func makeMonitor() -> (NSPasteboard, ClipboardMonitor, Sent) {
        let pasteboard = NSPasteboard(name: .init("dev.ivo.droiddrop.tests.\(UUID().uuidString)"))
        let sent = Sent()
        let monitor = ClipboardMonitor(pasteboard: pasteboard) { sent.messages.append($0) }
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

    @Test func copiesWhileStoppedAreNotSentAfterRestart() {
        let (pasteboard, monitor, sent) = makeMonitor()
        defer { pasteboard.releaseGlobally(); monitor.stop() }
        monitor.start()
        pasteboard.clearContents(); pasteboard.setString("phone here", forType: .string)
        monitor.tick()
        monitor.stop()
        pasteboard.clearContents(); pasteboard.setString("no phone", forType: .string)
        monitor.start()
        monitor.tick()
        #expect(sent.messages.count == 1)
        pasteboard.clearContents(); pasteboard.setString("phone back", forType: .string)
        monitor.tick()
        #expect(sent.messages.count == 2)
    }

    @Test func timerStopsAfterMonitorIsReleased() {
        let pasteboard = NSPasteboard(name: .init("dev.ivo.droiddrop.tests.\(UUID().uuidString)"))
        defer { pasteboard.releaseGlobally() }
        weak var timer: Timer?
        do {
            let monitor = ClipboardMonitor(pasteboard: pasteboard) { _ in }
            monitor.start()
            timer = monitor.timer
        }
        RunLoop.main.run(until: Date().addingTimeInterval(ClipboardMonitor.pollInterval + 0.3))
        #expect(timer?.isValid != true)
    }

    @Test func readLeavesTextOfMarkedItemsUnread() {
        let pasteboard = NSPasteboard(name: .init("dev.ivo.droiddrop.tests.\(UUID().uuidString)"))
        defer { pasteboard.releaseGlobally() }
        pasteboard.declareTypes([.string, PasteboardMarkers.concealed], owner: nil)
        pasteboard.setString("hunter2", forType: .string)
        pasteboard.setData(Data(), forType: PasteboardMarkers.concealed)
        let contents = PasteboardContents.read(from: pasteboard)
        #expect(contents.text == nil)
        #expect(contents.types.contains(PasteboardMarkers.concealed.rawValue))
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

    @Test func applyWritesPasteboard() {
        let (pasteboard, monitor, _) = makeMonitor()
        defer { pasteboard.releaseGlobally() }
        #expect(monitor.apply("from phone"))
        #expect(pasteboard.string(forType: .string) == "from phone")
    }

    @Test func appliedClipIsNotSentBack() {
        let (pasteboard, monitor, sent) = makeMonitor()
        defer { pasteboard.releaseGlobally() }
        monitor.tick()
        monitor.apply("from phone")
        monitor.tick()
        #expect(sent.messages.isEmpty)
        pasteboard.clearContents(); pasteboard.setString("typed on mac", forType: .string)
        monitor.tick()
        #expect(sent.messages.count == 1)
    }
}
