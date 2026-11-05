import Foundation

enum Fixtures {
    /// `<repo>/protocol/fixtures`, found relative to this source file.
    static let directory: URL = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent() // DroidDropKitTests
        .deletingLastPathComponent() // Tests
        .deletingLastPathComponent() // DroidDropKit
        .deletingLastPathComponent() // macos
        .deletingLastPathComponent() // repo root
        .appendingPathComponent("protocol/fixtures")

    static func json(_ name: String) throws -> [String: Any] {
        let data = try Data(contentsOf: directory.appendingPathComponent("\(name).json"))
        return try JSONSerialization.jsonObject(with: data) as! [String: Any]
    }

    static func list(_ object: [String: Any], _ key: String) -> [[String: Any]] {
        object[key] as! [[String: Any]]
    }

    /// Semantic JSON equality (key order and whitespace ignored).
    static func sameJSON(_ a: Any, _ b: Any) -> Bool {
        (a as! NSObject).isEqual(b)
    }
}

extension Data {
    init(hex: String) {
        var bytes: [UInt8] = []
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2)
            bytes.append(UInt8(hex[index..<next], radix: 16)!)
            index = next
        }
        self.init(bytes)
    }

    var hex: String { map { String(format: "%02x", $0) }.joined() }
}
