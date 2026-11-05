/// Strict RFC 8259 grammar check (SPEC §4): no BOM, no duplicate member names in any object
/// (compared after unescaping), no unpaired surrogate escapes, no `-0`. Input must already be valid UTF-8.
enum StrictJSON {
    static let maxDepth = 64

    static func validate(_ bytes: [UInt8]) throws {
        var parser = Parser(bytes: bytes)
        parser.skipWhitespace()
        try parser.value(depth: 0)
        parser.skipWhitespace()
        guard parser.peek == nil else { throw parser.error("trailing data") }
    }

    private struct Parser {
        let bytes: [UInt8]
        var index = 0

        var peek: UInt8? { index < bytes.count ? bytes[index] : nil }

        func error(_ reason: String) -> ProtocolError { ProtocolError("invalid JSON at byte \(index): \(reason)") }

        mutating func skipWhitespace() {
            while let byte = peek, byte == 0x20 || byte == 0x09 || byte == 0x0A || byte == 0x0D { index += 1 }
        }

        mutating func expect(_ byte: Unicode.Scalar) throws {
            guard peek == UInt8(ascii: byte) else { throw error("expected '\(byte)'") }
            index += 1
        }

        mutating func value(depth: Int) throws {
            switch peek {
            case UInt8(ascii: "{"): try object(depth: depth + 1)
            case UInt8(ascii: "["): try array(depth: depth + 1)
            case UInt8(ascii: "\""): _ = try string()
            case UInt8(ascii: "t"): try literal("true")
            case UInt8(ascii: "f"): try literal("false")
            case UInt8(ascii: "n"): try literal("null")
            default: try number()
            }
        }

        mutating func object(depth: Int) throws {
            guard depth <= StrictJSON.maxDepth else { throw error("nesting deeper than \(StrictJSON.maxDepth)") }
            index += 1
            skipWhitespace()
            if peek == UInt8(ascii: "}") { index += 1; return }
            var names = Set<[UInt8]>()
            while true {
                skipWhitespace()
                guard names.insert(try string()).inserted else { throw error("duplicate member name") }
                skipWhitespace()
                try expect(":")
                skipWhitespace()
                try value(depth: depth)
                skipWhitespace()
                if peek == UInt8(ascii: ",") { index += 1; continue }
                try expect("}")
                return
            }
        }

        mutating func array(depth: Int) throws {
            guard depth <= StrictJSON.maxDepth else { throw error("nesting deeper than \(StrictJSON.maxDepth)") }
            index += 1
            skipWhitespace()
            if peek == UInt8(ascii: "]") { index += 1; return }
            while true {
                skipWhitespace()
                try value(depth: depth)
                skipWhitespace()
                if peek == UInt8(ascii: ",") { index += 1; continue }
                try expect("]")
                return
            }
        }

        /// Returns the unescaped UTF-8 bytes, so escaped and literal spellings of a name compare equal.
        mutating func string() throws -> [UInt8] {
            try expect("\"")
            var out: [UInt8] = []
            while let byte = peek {
                index += 1
                switch byte {
                case UInt8(ascii: "\""): return out
                case UInt8(ascii: "\\"): try escape(into: &out)
                case 0..<0x20: throw error("unescaped control character")
                default: out.append(byte)
                }
            }
            throw error("unterminated string")
        }

        mutating func escape(into out: inout [UInt8]) throws {
            guard let byte = peek else { throw error("unterminated escape") }
            index += 1
            switch byte {
            case UInt8(ascii: "\""), UInt8(ascii: "\\"), UInt8(ascii: "/"): out.append(byte)
            case UInt8(ascii: "b"): out.append(0x08)
            case UInt8(ascii: "f"): out.append(0x0C)
            case UInt8(ascii: "n"): out.append(0x0A)
            case UInt8(ascii: "r"): out.append(0x0D)
            case UInt8(ascii: "t"): out.append(0x09)
            case UInt8(ascii: "u"):
                var scalar = try hex4()
                if (0xD800...0xDBFF).contains(scalar) {
                    try expect("\\")
                    try expect("u")
                    let low = try hex4()
                    guard (0xDC00...0xDFFF).contains(low) else { throw error("unpaired surrogate escape") }
                    scalar = 0x10000 + ((scalar - 0xD800) << 10) + (low - 0xDC00)
                } else if (0xDC00...0xDFFF).contains(scalar) {
                    throw error("unpaired surrogate escape")
                }
                UTF8.encode(Unicode.Scalar(scalar)!) { out.append($0) }
            default:
                throw error("invalid escape")
            }
        }

        mutating func hex4() throws -> UInt32 {
            var value: UInt32 = 0
            for _ in 0..<4 {
                guard let byte = peek, let digit = Character(Unicode.Scalar(byte)).hexDigitValue else {
                    throw error("invalid \\u escape")
                }
                value = value << 4 | UInt32(digit)
                index += 1
            }
            return value
        }

        mutating func literal(_ word: String) throws {
            for byte in word.utf8 {
                guard peek == byte else { throw error("invalid literal") }
                index += 1
            }
        }

        mutating func number() throws {
            let start = index
            if peek == UInt8(ascii: "-") { index += 1 }
            guard let first = peek, isDigit(first) else { throw error("unexpected character") }
            index += 1
            if first != UInt8(ascii: "0") { skipDigits() }
            var isInteger = true
            if peek == UInt8(ascii: ".") {
                index += 1
                try requireDigits()
                isInteger = false
            }
            if peek == UInt8(ascii: "e") || peek == UInt8(ascii: "E") {
                index += 1
                if peek == UInt8(ascii: "+") || peek == UInt8(ascii: "-") { index += 1 }
                try requireDigits()
                isInteger = false
            }
            if isInteger && bytes[start..<index].elementsEqual("-0".utf8) { throw error("-0 is not an integer") }
        }

        mutating func requireDigits() throws {
            guard let byte = peek, isDigit(byte) else { throw error("expected digit") }
            skipDigits()
        }

        mutating func skipDigits() {
            while let byte = peek, isDigit(byte) { index += 1 }
        }

        func isDigit(_ byte: UInt8) -> Bool { (UInt8(ascii: "0")...UInt8(ascii: "9")).contains(byte) }
    }
}
