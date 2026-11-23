import Foundation
import Testing
@testable import DroidDropKit

@Suite struct IncomingBatchesTests {
    private func info(_ id: String, _ index: Int, count: Int = 3) -> BatchInfo {
        BatchInfo(id: id, index: index, count: count, size: 3_000, before: index * 1_000)
    }

    private func url(_ index: Int) -> URL { URL(fileURLWithPath: "/x/\(index)") }

    private func received(_ batches: inout IncomingBatches, _ batch: BatchInfo) {
        batches.outcome(.received(name: "f\(batch.index)", url: url(batch.index)), batch: batch)
    }

    @Test func endSummarisesFromBatchEnd() {
        var batches = IncomingBatches()
        let a0 = info("A", 0), a1 = info("A", 1)
        #expect(batches.offer(batch: a0) == nil)
        received(&batches, a0)
        #expect(batches.offer(batch: a1) == nil)
        received(&batches, a1)
        #expect(batches.end(batch: "A", count: 3, sent: 2, cancelled: false)
                == BatchSummary(direction: .incoming, batch: "A", count: 3, done: 2, files: [url(0), url(1)]))
    }

    @Test func cancelledEnd() {
        var batches = IncomingBatches()
        let a0 = info("A", 0)
        _ = batches.offer(batch: a0)
        received(&batches, a0)
        let summary = batches.end(batch: "A", count: 3, sent: 1, cancelled: true)
        #expect(summary?.cancelled == true)
        #expect(summary?.done == 1)
    }

    @Test func unknownEndIgnored() {
        var batches = IncomingBatches()
        #expect(batches.end(batch: "nope", count: 3, sent: 1, cancelled: false) == nil)
        _ = batches.offer(batch: info("A", 0))
        #expect(batches.end(batch: "B", count: 3, sent: 1, cancelled: false) == nil)
        #expect(batches.end(batch: "A", count: 3, sent: 1, cancelled: false)?.count == 3)
    }

    @Test func duplicateIndexCountsOnce() {
        var batches = IncomingBatches()
        let a1 = info("A", 1)
        _ = batches.offer(batch: a1)
        received(&batches, a1)
        _ = batches.offer(batch: a1)
        received(&batches, a1)
        #expect(batches.end(batch: "A", count: 3, sent: 1, cancelled: false)?.files == [url(1)])
    }

    @Test func otherBatchOfferEndsOpenBatch() {
        var batches = IncomingBatches()
        let a0 = info("A", 0, count: 4)
        _ = batches.offer(batch: a0)
        received(&batches, a0)
        batches.outcome(.failed(.incoming, name: "f1", reason: "hash_mismatch"), batch: info("A", 1, count: 4))
        #expect(batches.offer(batch: info("B", 0, count: 2))
                == BatchSummary(direction: .incoming, batch: "A", count: 4, done: 1, silent: true, files: [url(0)]))
        let b0 = info("B", 0, count: 2)
        received(&batches, b0)
        #expect(batches.end(batch: "B", count: 2, sent: 1, cancelled: false)
                == BatchSummary(direction: .incoming, batch: "B", count: 2, done: 1, files: [url(0)]))
    }

    @Test func unbatchedOfferEndsOpenBatch() {
        var batches = IncomingBatches()
        let a0 = info("A", 0)
        _ = batches.offer(batch: a0)
        received(&batches, a0)
        #expect(batches.offer(batch: nil)
                == BatchSummary(direction: .incoming, batch: "A", count: 3, done: 1, silent: true, files: [url(0)]))
        #expect(batches.offer(batch: nil) == nil)
    }

    @Test func endAfterFallbackIgnored() {
        var batches = IncomingBatches()
        _ = batches.offer(batch: info("A", 0))
        _ = batches.offer(batch: nil)
        #expect(batches.end(batch: "A", count: 3, sent: 0, cancelled: false) == nil)
    }

    @Test func clearDropsOpenBatch() {
        var batches = IncomingBatches()
        let a0 = info("A", 0)
        _ = batches.offer(batch: a0)
        received(&batches, a0)
        batches.clear()
        #expect(batches.offer(batch: info("B", 0)) == nil)
        #expect(batches.end(batch: "A", count: 3, sent: 1, cancelled: false) == nil)
    }

    @Test func clearWithNothingOpen() {
        var batches = IncomingBatches()
        batches.clear()
        #expect(batches.offer(batch: nil) == nil)
    }

    @Test func fallbackNeverReportsMoreThanCount() {
        var batches = IncomingBatches()
        for i in 0...3 {
            let b = info("A", i, count: 5)
            _ = batches.offer(batch: b)
            received(&batches, b)
        }
        _ = batches.offer(batch: info("A", 2, count: 3))
        let summary = batches.offer(batch: nil)
        #expect(summary?.count == 3)
        #expect(summary?.done == 3)
        #expect(summary?.files == [url(0), url(1), url(2)])
    }

    @Test func duplicateIndexCountsOnceInFallback() {
        var batches = IncomingBatches()
        let a1 = info("A", 1)
        _ = batches.offer(batch: a1)
        received(&batches, a1)
        _ = batches.offer(batch: a1)
        received(&batches, a1)
        #expect(batches.offer(batch: nil)?.done == 1)
    }

    @Test func latestCountWinsInFallback() {
        var batches = IncomingBatches()
        _ = batches.offer(batch: info("A", 0, count: 3))
        _ = batches.offer(batch: info("A", 1, count: 7))
        #expect(batches.offer(batch: nil)?.count == 7)
    }

    @Test func foreignAndUnbatchedOutcomesIgnored() {
        var batches = IncomingBatches()
        _ = batches.offer(batch: info("A", 0))
        batches.outcome(.received(name: "z", url: URL(fileURLWithPath: "/z")), batch: info("Z", 1))
        batches.outcome(.sent(name: "s"), batch: info("A", 2))
        #expect(batches.end(batch: "A", count: 3, sent: 0, cancelled: false)?.files == [])
    }

    @Test func backToBackBatchesReturnNil() {
        var batches = IncomingBatches()
        _ = batches.offer(batch: info("A", 0, count: 1))
        #expect(batches.end(batch: "A", count: 1, sent: 0, cancelled: false)?.batch == "A")
        #expect(batches.offer(batch: info("B", 0, count: 1)) == nil)
    }
}
