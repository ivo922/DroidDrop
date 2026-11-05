import Foundation

/// QR pairing URI (SPEC §6.1). Parsed by hand: `URLComponents` is more lenient than the spec allows.
public struct PairURI: Equatable, Sendable {
    public var name: String
    public var hosts: [String]
    public var port: Int
    /// Server fingerprint.
    public var fingerprint: Data
    /// One-time pairing secret.
    public var secret: Data

    public init(name: String, hosts: [String], port: Int, fingerprint: Data, secret: Data) {
        self.name = name
        self.hosts = hosts
        self.port = port
        self.fingerprint = fingerprint
        self.secret = secret
    }

    public init?(string: String) {
        let bytes = Array(string.utf8)
        guard bytes.starts(with: Self.prefix),
              bytes.allSatisfy({ (0x21...0x7E).contains($0) && $0 != UInt8(ascii: "#") }) else { return nil }

        var params: [String: String] = [:]
        for part in bytes[Self.prefix.count...].split(separator: UInt8(ascii: "&"), omittingEmptySubsequences: false) {
            let eq = part.firstIndex(of: UInt8(ascii: "="))
            guard let key = Self.percentDecode(part[..<(eq ?? part.endIndex)]) else { return nil }
            var value: String?
            if let eq {
                guard let decoded = Self.percentDecode(part[(eq + 1)...]) else { return nil }
                value = decoded
            }
            guard Self.known.contains(key) else { continue }
            guard let value, params.updateValue(value, forKey: key) == nil else { return nil }
        }

        guard params["v"] == "1",
              let name = params["name"], !name.isEmpty,
              let hosts = params["hosts"]?.utf8.split(separator: UInt8(ascii: ","), omittingEmptySubsequences: false)
                  .map({ String(decoding: $0, as: UTF8.self) }),
              hosts.allSatisfy(Self.isIPv4),
              let portText = params["port"], Self.isPort(portText), let port = Int(portText), port <= 65_535,
              let fingerprint = params["fp"].flatMap(Base64URL.decode32),
              let secret = params["secret"].flatMap(Base64URL.decode32) else { return nil }
        self.init(name: name, hosts: hosts, port: port, fingerprint: fingerprint, secret: secret)
    }

    public var string: String {
        let parameters = [
            ("v", "1"),
            ("name", name),
            ("hosts", hosts.joined(separator: ",")),
            ("port", String(port)),
            ("fp", Base64URL.encode(fingerprint)),
            ("secret", Base64URL.encode(secret)),
        ]
        return "droiddrop://pair?" + parameters.map { "\($0)=\(Self.percentEncode($1))" }.joined(separator: "&")
    }

    private static let prefix = Array("droiddrop://pair?".utf8)
    private static let known: Set<String> = ["v", "name", "hosts", "port", "fp", "secret"]
    private static let hexDigits = Array("0123456789ABCDEF".utf8)

    private static func isUnreserved(_ byte: UInt8) -> Bool {
        switch byte {
        case UInt8(ascii: "A")...UInt8(ascii: "Z"), UInt8(ascii: "a")...UInt8(ascii: "z"),
             UInt8(ascii: "0")...UInt8(ascii: "9"), UInt8(ascii: "-"), UInt8(ascii: "."), UInt8(ascii: "_"), UInt8(ascii: "~"):
            return true
        default:
            return false
        }
    }

    private static func percentEncode(_ value: String) -> String {
        var out: [UInt8] = []
        for byte in value.utf8 {
            if isUnreserved(byte) {
                out.append(byte)
            } else {
                out += [UInt8(ascii: "%"), hexDigits[Int(byte >> 4)], hexDigits[Int(byte & 0x0F)]]
            }
        }
        return String(decoding: out, as: UTF8.self)
    }

    /// Percent-decoding where `+` stays a literal plus; nil on a malformed escape or invalid UTF-8.
    private static func percentDecode(_ raw: ArraySlice<UInt8>) -> String? {
        var out: [UInt8] = []
        var i = raw.startIndex
        while i < raw.endIndex {
            if raw[i] == UInt8(ascii: "%") {
                guard i + 2 < raw.endIndex, let hi = hexValue(raw[i + 1]), let lo = hexValue(raw[i + 2]) else { return nil }
                out.append(hi << 4 | lo)
                i += 3
            } else {
                out.append(raw[i])
                i += 1
            }
        }
        return String(validating: out, as: UTF8.self)
    }

    private static func hexValue(_ byte: UInt8) -> UInt8? {
        switch byte {
        case UInt8(ascii: "0")...UInt8(ascii: "9"): byte - UInt8(ascii: "0")
        case UInt8(ascii: "a")...UInt8(ascii: "f"): byte - UInt8(ascii: "a") + 10
        case UInt8(ascii: "A")...UInt8(ascii: "F"): byte - UInt8(ascii: "A") + 10
        default: nil
        }
    }

    /// `[1-9][0-9]{0,4}`, ASCII digits only.
    private static func isPort(_ text: String) -> Bool {
        let utf8 = Array(text.utf8)
        return (1...5).contains(utf8.count) && utf8[0] != UInt8(ascii: "0")
            && utf8.allSatisfy { (UInt8(ascii: "0")...UInt8(ascii: "9")).contains($0) }
    }

    static func isIPv4(_ string: String) -> Bool {
        let parts = string.utf8.split(separator: UInt8(ascii: "."), omittingEmptySubsequences: false)
        return parts.count == 4 && parts.allSatisfy { part in
            guard (1...3).contains(part.count),
                  part.allSatisfy({ (UInt8(ascii: "0")...UInt8(ascii: "9")).contains($0) }),
                  !(part.count > 1 && part.first == UInt8(ascii: "0")) else { return false }
            return part.reduce(0) { $0 * 10 + Int($1 - UInt8(ascii: "0")) } <= 255
        }
    }
}
