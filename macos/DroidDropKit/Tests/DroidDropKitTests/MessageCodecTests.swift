import Foundation
import Testing
@testable import DroidDropKit

@Suite struct MessageCodecTests {
    let fixture = try! Fixtures.json("messages")

    @Test func validMessagesRoundTrip() throws {
        for entry in Fixtures.list(fixture, "valid") {
            let input = try JSONSerialization.data(withJSONObject: entry["json"]!)
            let message = try MessageCodec.decode(input)
            let output = try JSONSerialization.jsonObject(with: MessageCodec.encode(message))
            #expect(Fixtures.sameJSON(output, entry["json"]!), "\(entry["name"]!)")
        }
    }

    @Test func unknownMembersAreDropped() throws {
        for entry in Fixtures.list(fixture, "normalized") {
            let input = try JSONSerialization.data(withJSONObject: entry["input"]!)
            let output = try JSONSerialization.jsonObject(with: MessageCodec.encode(MessageCodec.decode(input)))
            #expect(Fixtures.sameJSON(output, entry["output"]!), "\(entry["name"]!)")
        }
    }

    @Test func unknownTypesDecodeAsUnknown() throws {
        for entry in Fixtures.list(fixture, "unknown") {
            let input = try JSONSerialization.data(withJSONObject: entry["json"]!)
            #expect(try MessageCodec.decode(input) == .unknown(type: entry["type"] as! String))
        }
    }

    @Test func invalidMessagesAreRejected() {
        let payloads = Fixtures.list(fixture, "invalid").map { ($0["name"] as! String, Data(($0["raw"] as! String).utf8)) }
            + Fixtures.list(fixture, "invalidHex").map { ($0["name"] as! String, Data(hex: $0["hex"] as! String)) }
        let accepted = payloads.filter { (_, data) in
            do {
                _ = try MessageCodec.decode(data)
                return true
            } catch {
                return !(error is ProtocolError)
            }
        }
        #expect(accepted.map(\.0) == [])
    }

    @Test func rawEncodingsDecodeLikeCanonicalJSON() throws {
        for entry in Fixtures.list(fixture, "validRaw") {
            let canonical = try MessageCodec.decode(JSONSerialization.data(withJSONObject: entry["json"]!))
            #expect(try MessageCodec.decode(Data((entry["raw"] as! String).utf8)) == canonical, "\(entry["name"]!)")
        }
    }

    @Test func nestingDepthIsBounded() throws {
        func ping(nesting depth: Int) -> Data {
            Data((#"{"type":"ping","id":1,"x":"# + String(repeating: "[", count: depth)
                + String(repeating: "]", count: depth) + "}").utf8)
        }
        #expect(try MessageCodec.decode(ping(nesting: 32)) == .ping(id: 1))
        #expect(throws: ProtocolError.self) { _ = try MessageCodec.decode(ping(nesting: 100_000)) }
    }

    @Test(arguments: [
        #"{"type":"ping","id":true}"#,
        #"{"type":"ping","id":1.0}"#,
        #"{"type":"ping","id":1e3}"#,
        #"{"type":"ping","id":9007199254740992}"#,
        #"{"type":"ping","id":18446744073709551615}"#,
        #"{"type":"ping","id":"1"}"#,
        #"{"type":"ping","id":null}"#,
        #"{"type":"pair_result","ok":false,"reason":5}"#,
        #"{"type":"hello","v":1.0,"name":"a","platform":"macos"}"#,
        #"{"type":"pair_result","ok":1}"#,
        #"{"type":"file_result","transferId":"0f8e2d1c-3b4a-4c5d-8e6f-7a8b9c0d1e2f","ok":0}"#,
        #"{"type":"clip","id":"5b2c8f9e-1d3a-4e7b-9c6d-0a1b2c3d4e5f","mime":"text/plain","text":null}"#,
    ])
    func foundationBridgingIsRejected(raw: String) {
        #expect(throws: ProtocolError.self) { _ = try MessageCodec.decode(Data(raw.utf8)) }
    }

    @Test func nonUTF8EncodingsAreRejected() {
        let utf16 = #"{"type":"ping","id":1}"#.data(using: .utf16LittleEndian)!
        #expect(throws: ProtocolError.self) { _ = try MessageCodec.decode(utf16) }
    }

    @Test func nullOptionalMembersAreAbsent() throws {
        let raw = #"{"type":"file_result","transferId":"0f8e2d1c-3b4a-4c5d-8e6f-7a8b9c0d1e2f","ok":false,"reason":null}"#
        #expect(try MessageCodec.decode(Data(raw.utf8))
            == .fileResult(transferId: "0f8e2d1c-3b4a-4c5d-8e6f-7a8b9c0d1e2f", ok: false, reason: nil))
        let pair = #"{"type":"pair_result","ok":false,"proof":null,"reason":null}"#
        #expect(try MessageCodec.decode(Data(pair.utf8)) == .pairResult(ok: false, proof: nil, reason: nil))
    }

    @Test func base64URLRoundTripsAndIsStrict() {
        let bytes = Data((0..<32).map { UInt8($0) })
        let text = Base64URL.encode(bytes)
        #expect(text == "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8")
        #expect(Base64URL.decode32(text) == bytes)
        #expect(Base64URL.decode32(text + "=") == nil)
        #expect(Base64URL.decode32(String(text.dropLast())) == nil)
        #expect(Base64URL.decode("ab+/") == nil)
        #expect(Base64URL.decode32("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB") == nil)
        #expect(Base64URL.decode("AB") == nil)
        #expect(Base64URL.decode("AA") == Data([0]))
        #expect(Base64URL.decode("") == Data())
    }
}
