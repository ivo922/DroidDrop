import Testing
@testable import DroidDropKit

@Suite struct StrictJSONTests {
    @Test(arguments: [
        #"{}"#, #"[]"#, #"0"#, #"-1.5e+3"#, #"2E-2"#, #"0.0"#, #"-0.0"#, #"-0e1"#, #""""#, #"null"#,
        #"{"a":[],"b":{},"c":[true,false,null],"d":"\u00e9\/\b\f\n\r\t\"\\\u0000"}"#,
        #"{"a":1,"A":2}"#, #"["\uD83D\uDE00"]"#,
    ])
    func accepts(json: String) {
        #expect(throws: Never.self) { try StrictJSON.validate(Array(json.utf8)) }
    }

    @Test(arguments: [
        "", " ", "-0", "-", "01", "1.", ".5", "1e", "1e+", "+1", "tru", "nul", "True", "NaN", "Infinity",
        #""\x""#, #""\u12""#, #""\u12G4""#, "\"a\tb\"", "\"open", "[1 2]", "[,]", "[1,]", #"{"a" 1}"#, "{,}",
        #"{"a":1,}"#, "{a:1}", "{'a':1}", "[1]]", "[1]\u{0C}", "\u{FEFF}[]", #"["\uDE00\uD800"]"#, #"["\uD800x"]"#,
        #"{"é":1,"\u00e9":2}"#,
    ])
    func rejects(json: String) {
        #expect(throws: ProtocolError.self) { try StrictJSON.validate(Array(json.utf8)) }
    }

    @Test func depthLimitIsExact() {
        func nested(_ depth: Int) -> [UInt8] {
            Array((String(repeating: "[", count: depth) + String(repeating: "]", count: depth)).utf8)
        }
        #expect(throws: Never.self) { try StrictJSON.validate(nested(StrictJSON.maxDepth)) }
        #expect(throws: ProtocolError.self) { try StrictJSON.validate(nested(StrictJSON.maxDepth + 1)) }
    }
}
