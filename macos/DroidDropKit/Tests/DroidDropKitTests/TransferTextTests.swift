import Foundation
import Testing
@testable import DroidDropKit

@Suite struct TransferTextTests {
    @Test func menuLines() {
        let incoming = TransferStatus(direction: .incoming, name: "photo.jpg", bytesDone: 42, size: 100)
        let outgoing = TransferStatus(direction: .outgoing, name: "a.pdf", bytesDone: 1, size: 10, queued: 2)
        #expect(FileTransfers.Snapshot(incoming: incoming, outgoing: outgoing, waitingToSend: 0).menuLines
                == ["Receiving photo.jpg — 42%", "Sending a.pdf — 10% (+2 queued)"])
        #expect(FileTransfers.Snapshot(incoming: nil, outgoing: nil, waitingToSend: 1).menuLines == ["1 file waiting for the phone"])
        #expect(FileTransfers.Snapshot(incoming: nil, outgoing: nil, waitingToSend: 3).menuLines == ["3 files waiting for the phone"])
        #expect(FileTransfers.Snapshot(incoming: TransferStatus(direction: .incoming, name: "e", bytesDone: 0, size: 0), outgoing: nil, waitingToSend: 0).menuLines
                == ["Receiving e — 0%"])
    }

    @Test func notificationText() {
        let url = URL(fileURLWithPath: "/tmp/photo.jpg")
        #expect(TransferOutcome.received(name: "photo.jpg", url: url).notificationText(peerName: "Pixel") == ("Received photo.jpg", "From Pixel · Downloads › DroidDrop"))
        #expect(TransferOutcome.sent(name: "a.pdf").notificationText(peerName: "Pixel") == ("Sent a.pdf", "To Pixel"))
        #expect(TransferOutcome.failed(.incoming, name: "b", reason: "hash_mismatch").notificationText(peerName: "Pixel") == ("Could not receive b", "hash_mismatch"))
        #expect(TransferOutcome.failed(.outgoing, name: "c", reason: "cancelled").notificationText(peerName: "Pixel") == ("Could not send c", "cancelled"))
    }
}
