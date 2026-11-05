import Foundation
import Testing
@testable import DroidDropKit

@Suite struct PairURITests {
    let fixture = try! Fixtures.json("pair-uri")

    @Test func parsesValidURIs() throws {
        for entry in Fixtures.list(fixture, "valid") {
            let expected = entry["expected"] as! [String: Any]
            let uri = try #require(PairURI(string: entry["uri"] as! String), "\(entry["name"]!)")
            #expect(uri.name == expected["name"] as! String)
            #expect(uri.hosts == expected["hosts"] as! [String])
            #expect(uri.port == expected["port"] as! Int)
            #expect(Base64URL.encode(uri.fingerprint) == expected["fingerprint"] as! String)
            #expect(Base64URL.encode(uri.secret) == expected["secret"] as! String)
        }
    }

    @Test func buildsURIsThatParseBack() throws {
        for entry in Fixtures.list(fixture, "valid") {
            let uri = try #require(PairURI(string: entry["uri"] as! String))
            #expect(PairURI(string: uri.string) == uri, "\(entry["name"]!)")
        }
    }

    @Test func encodesReservedCharacters() {
        let uri = PairURI(name: "A+B C", hosts: ["10.0.0.1", "10.0.0.2"], port: 47321,
                          fingerprint: Data(repeating: 1, count: 32), secret: Data(repeating: 2, count: 32))
        #expect(uri.string.contains("name=A%2BB%20C"))
        #expect(uri.string.contains("hosts=10.0.0.1%2C10.0.0.2"))
    }

    @Test func encodesNonASCIIAsUppercaseUTF8Escapes() {
        let uri = PairURI(name: "é✓", hosts: ["10.0.0.1"], port: 1,
                          fingerprint: Data(repeating: 1, count: 32), secret: Data(repeating: 2, count: 32))
        #expect(uri.string.contains("name=%C3%A9%E2%9C%93"))
        #expect(PairURI(string: uri.string) == uri)
    }

    @Test func rejectsInvalidURIs() {
        for entry in Fixtures.list(fixture, "invalid") {
            #expect(PairURI(string: entry["uri"] as! String) == nil, "\(entry["name"]!)")
        }
    }

    static let tail = "&hosts=192.168.1.20&port=47321&fp=vfgFdfREwYioGfx3pvZxktBUzEZZ6O_Lr9JYjm1o7v8"
        + "&secret=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"

    static let strictViolationHeads: [String] = [
        "droiddrop://pair?v=1&name=Mac&x=%zz",
        "droiddrop://pair?v=1&name=Mac&%zz",
        "droiddrop://pair?v=1&name=Mac&x=%FF",
        "droiddrop://pair?v=1&name=Mac&port=1",
        "droiddrop://pair?v=1&name=Mac%20%",
        "droiddrop://pair?v=1&name=M ac",
        "droiddrop://pair?v=1&name=Mac\t",
        "droiddrop://pair?v=%31&name=Mac&v=1",
        "droiddrop://pair?v=01&name=Mac",
        "droiddrop://Pair?v=1&name=Mac",
    ]

    @Test(arguments: strictViolationHeads)
    func rejectsStrictParsingViolations(head: String) {
        #expect(PairURI(string: head + Self.tail) == nil)
    }

    @Test(arguments: [
        ("droiddrop://pair?v=1&name=Mac&%78=%41", "Mac"),
        ("droiddrop://pair?v=%31&name=%4d%61c", "Mac"),
        ("droiddrop://pair?v=1&na%6De=a=b", "a=b"),
    ])
    func acceptsEscapedKeysAndValues(prefix: String, name: String) throws {
        let uri = try #require(PairURI(string: prefix + Self.tail))
        #expect(uri.name == name)
    }

    @Test func acceptsEscapedPortDigits() throws {
        let uri = try #require(PairURI(string: "droiddrop://pair?v=1&name=Mac"
            + Self.tail.replacingOccurrences(of: "port=47321", with: "port=%365535")))
        #expect(uri.port == 65535)
    }
}
