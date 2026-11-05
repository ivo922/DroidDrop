import Foundation
import Testing
@testable import DroidDropKit

@Suite struct FrameTests {
    let fixture = try! Fixtures.json("frames")

    @Test func limitsMatchFixture() {
        #expect(FrameLimits.maxControlPayload == fixture["maxControlPayload"] as! Int)
        #expect(FrameLimits.maxChunkData == fixture["maxChunkData"] as! Int)
        #expect(FrameLimits.maxFrameLength == fixture["maxFrameLength"] as! Int)
    }

    @Test func decodesValidFrames() throws {
        for entry in Fixtures.list(fixture, "valid") {
            var decoder = FrameDecoder()
            let frames = try decoder.append(Data(hex: entry["hex"] as! String))
            #expect(frames.count == 1, "\(entry["name"]!)")
            switch (entry["kind"] as! String, frames.first) {
            case ("control", .control(let payload)?):
                let json = try JSONSerialization.jsonObject(with: payload)
                #expect(Fixtures.sameJSON(json, entry["json"]!), "\(entry["name"]!)")
            case ("chunk", .chunk(let transferId, let offset, let data)?):
                #expect(transferId == entry["transferId"] as! String)
                #expect(offset == (entry["offset"] as! NSNumber).uint64Value)
                #expect(data.hex == entry["dataHex"] as! String)
            default:
                Issue.record("wrong frame kind for \(entry["name"]!)")
            }
        }
    }

    @Test func encodesValidFramesByteExact() throws {
        for entry in Fixtures.list(fixture, "valid") {
            var decoder = FrameDecoder()
            let frame = try decoder.append(Data(hex: entry["hex"] as! String))[0]
            #expect(FrameEncoder.encode(frame).hex == entry["hex"] as! String, "\(entry["name"]!)")
        }
    }

    @Test func decodesFramesSplitIntoSingleBytes() throws {
        let entries = Fixtures.list(fixture, "valid")
        let stream = entries.map { Data(hex: $0["hex"] as! String) }.reduce(Data(), +)
        var decoder = FrameDecoder()
        var frames: [Frame] = []
        for byte in stream {
            frames += try decoder.append(Data([byte]))
        }
        #expect(frames.count == entries.count)
    }

    @Test func partialHeaderYieldsNothing() throws {
        var decoder = FrameDecoder()
        #expect(try decoder.append(Data([0, 0, 0])).isEmpty)
    }

    @Test func rejectsInvalidFrames() {
        for entry in Fixtures.list(fixture, "invalid") {
            var decoder = FrameDecoder()
            let expected = FrameError(rawValue: entry["error"] as! String)!
            #expect(throws: expected, "\(entry["name"]!)") {
                _ = try decoder.append(Data(hex: entry["hex"] as! String))
            }
        }
    }
}
