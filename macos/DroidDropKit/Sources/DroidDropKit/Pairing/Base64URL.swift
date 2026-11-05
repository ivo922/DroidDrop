import Foundation

public enum Base64URL {
    public static func encode(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    /// Strict: unpadded base64url alphabet only, canonical (unused trailing bits zero).
    public static func decode(_ string: String) -> Data? {
        guard string.utf8.allSatisfy(isAlphabet), string.utf8.count % 4 != 1 else { return nil }
        var base64 = string
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        while base64.utf8.count % 4 != 0 { base64 += "=" }
        guard let data = Data(base64Encoded: base64), encode(data) == string else { return nil }
        return data
    }

    /// Exactly 32 bytes (43 characters), as used for fingerprints, secrets and proofs.
    public static func decode32(_ string: String) -> Data? {
        guard string.utf8.count == 43, let data = decode(string), data.count == 32 else { return nil }
        return data
    }

    private static func isAlphabet(_ byte: UInt8) -> Bool {
        switch byte {
        case UInt8(ascii: "A")...UInt8(ascii: "Z"), UInt8(ascii: "a")...UInt8(ascii: "z"),
             UInt8(ascii: "0")...UInt8(ascii: "9"), UInt8(ascii: "-"), UInt8(ascii: "_"):
            return true
        default:
            return false
        }
    }
}
