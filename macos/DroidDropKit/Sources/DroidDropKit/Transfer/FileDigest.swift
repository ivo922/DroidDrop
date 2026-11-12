import CryptoKit
import Foundation

public enum FileDigest {
    static let pieceSize = 1_048_576

    /// Lowercase hex SHA-256 and byte count, read in 1 MiB pieces (CryptoKit).
    public static func sha256(of url: URL) throws -> (hex: String, size: Int) {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hasher = SHA256()
        var size = 0
        while let piece = try handle.read(upToCount: pieceSize), !piece.isEmpty {
            hasher.update(data: piece)
            size += piece.count
        }
        return (hasher.finalize().map { String(format: "%02x", $0) }.joined(), size)
    }
}
