enum WireFormat {
    static let maxInteger = 9_007_199_254_740_991

    static func isUUID(_ string: String) -> Bool {
        let bytes = Array(string.utf8)
        guard bytes.count == 36 else { return false }
        for (index, byte) in bytes.enumerated() {
            if [8, 13, 18, 23].contains(index) {
                if byte != UInt8(ascii: "-") { return false }
            } else if !isLowerHex(byte) {
                return false
            }
        }
        return true
    }

    static func isSHA256Hex(_ string: String) -> Bool {
        let bytes = Array(string.utf8)
        return bytes.count == 64 && bytes.allSatisfy(isLowerHex)
    }

    private static func isLowerHex(_ byte: UInt8) -> Bool {
        (UInt8(ascii: "0")...UInt8(ascii: "9")).contains(byte) || (UInt8(ascii: "a")...UInt8(ascii: "f")).contains(byte)
    }
}
