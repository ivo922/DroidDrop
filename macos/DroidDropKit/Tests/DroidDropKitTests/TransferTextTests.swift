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

    @Test func batchMenuLine() {
        let batch = BatchProgress(index: 2, count: 20, bytesDone: 450, size: 1_000)
        let incoming = TransferStatus(direction: .incoming, name: "IMG_2041.jpg", bytesDone: 5, size: 10, batch: batch)
        let outgoing = TransferStatus(direction: .outgoing, name: "IMG_2041.jpg", bytesDone: 5, size: 10, queued: 3, batch: batch)
        #expect(FileTransfers.Snapshot(incoming: incoming, outgoing: outgoing).menuLines
                == ["Receiving 3 of 20 · IMG_2041.jpg — 45%", "Sending 3 of 20 · IMG_2041.jpg — 45% (+3 queued)"])
        let empty = TransferStatus(direction: .incoming, name: "e", bytesDone: 0, size: 0,
                                   batch: BatchProgress(index: 0, count: 2, bytesDone: 0, size: 0))
        #expect(FileTransfers.Snapshot(incoming: empty).menuLines == ["Receiving 1 of 2 · e — 0%"])
    }

    private func summary(_ direction: TransferDirection, count: Int = 20, done: Int, failures: [BatchFailure] = [],
                         cancelled: Bool = false) -> BatchSummary {
        BatchSummary(direction: direction, batch: "B", count: count, done: done, failures: failures, cancelled: cancelled)
    }

    @Test func senderSummaries() {
        let failures = [BatchFailure(name: "a.jpg", reason: "disk full"), BatchFailure(name: "b.jpg", reason: "cancelled by receiver")]
        func text(_ summary: BatchSummary) -> (String, String) { summary.notificationText(peerName: "Pixel") }
        #expect(text(summary(.outgoing, done: 20)) == ("Sent 20 files", "To Pixel"))
        #expect(text(summary(.outgoing, done: 18, failures: failures))
                == ("Sent 18 of 20 files", "2 failed:\na.jpg (disk full)\nb.jpg (cancelled by receiver)"))
        #expect(text(summary(.outgoing, done: 0, failures: failures)) == ("Could not send 20 files",
                "2 failed:\na.jpg (disk full)\nb.jpg (cancelled by receiver)"))
        #expect(text(summary(.outgoing, done: 5, cancelled: true)) == ("Sent 5 of 20 files", "Cancelled"))
        #expect(text(summary(.outgoing, done: 0, cancelled: true)) == ("Could not send 20 files", "Cancelled"))
        #expect(text(summary(.outgoing, count: 1, done: 1)) == ("Sent 1 file", "To Pixel"))
    }

    @Test func receiverSummaries() {
        func text(_ summary: BatchSummary) -> (String, String) { summary.notificationText(peerName: "Pixel") }
        #expect(text(summary(.incoming, done: 20)) == ("Received 20 files", "From Pixel · Downloads › DroidDrop"))
        #expect(text(summary(.incoming, done: 18)) == ("Received 18 of 20 files", "2 not received"))
        #expect(text(summary(.incoming, done: 5, cancelled: true)) == ("Received 5 of 20 files", "Cancelled on Pixel"))
        #expect(text(summary(.incoming, done: 0)) == ("Could not receive 20 files", "20 not received"))
        #expect(text(summary(.incoming, done: 0, cancelled: true)) == ("Could not receive 20 files", "Cancelled on Pixel"))
    }

    @Test func senderSummaryListsAtMostFive() {
        let failures = (1...8).map { BatchFailure(name: "f\($0)", reason: "r") }
        let body = summary(.outgoing, done: 12, failures: failures).notificationText(peerName: "Pixel").body
        #expect(body == "8 failed:\nf1 (r)\nf2 (r)\nf3 (r)\nf4 (r)\nf5 (r)\nand 3 more")
    }

    @Test func exactlyFiveFailuresHaveNoMore() {
        let failures = (1...5).map { BatchFailure(name: "f\($0)", reason: "r") }
        let body = summary(.outgoing, done: 15, failures: failures).notificationText(peerName: "Pixel").body
        #expect(body == "5 failed:\nf1 (r)\nf2 (r)\nf3 (r)\nf4 (r)\nf5 (r)")
    }
}
