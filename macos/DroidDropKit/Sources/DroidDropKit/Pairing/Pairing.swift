import CryptoKit
import Foundation

/// Fingerprints and pairing proofs (SPEC §2, §6.3).
///
/// Secrets and fingerprints passed to the proof functions, and the fingerprint passed to
/// `displayCode`, must be exactly 32 bytes; anything else is a programmer error and traps.
public enum Pairing {
    public static func fingerprint(certificateDER: Data) -> Data {
        Data(SHA256.hash(data: certificateDER))
    }

    public static func displayCode(fingerprint: Data) -> String {
        precondition(fingerprint.count == 32, "fingerprint must be 32 bytes")
        let hex = fingerprint.prefix(8).map { String(format: "%02X", $0) }.joined()
        return stride(from: 0, to: 16, by: 4).map { offset in
            let start = hex.index(hex.startIndex, offsetBy: offset)
            return String(hex[start..<hex.index(start, offsetBy: 4)])
        }.joined(separator: " ")
    }

    public static func clientProof(secret: Data, serverFingerprint: Data, clientFingerprint: Data) -> Data {
        Data(HMAC<SHA256>.authenticationCode(for: message("client", serverFingerprint, clientFingerprint), using: key(secret)))
    }

    public static func serverProof(secret: Data, serverFingerprint: Data, clientFingerprint: Data) -> Data {
        Data(HMAC<SHA256>.authenticationCode(for: message("server", serverFingerprint, clientFingerprint), using: key(secret)))
    }

    /// Constant-time comparison.
    public static func isValidClientProof(_ proof: Data, secret: Data, serverFingerprint: Data, clientFingerprint: Data) -> Bool {
        HMAC<SHA256>.isValidAuthenticationCode(proof, authenticating: message("client", serverFingerprint, clientFingerprint),
                                               using: key(secret))
    }

    /// Constant-time comparison.
    public static func isValidServerProof(_ proof: Data, secret: Data, serverFingerprint: Data, clientFingerprint: Data) -> Bool {
        HMAC<SHA256>.isValidAuthenticationCode(proof, authenticating: message("server", serverFingerprint, clientFingerprint),
                                               using: key(secret))
    }

    public static func newSecret() -> Data {
        SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) }
    }

    private static func key(_ secret: Data) -> SymmetricKey {
        precondition(secret.count == 32, "secret must be 32 bytes")
        return SymmetricKey(data: secret)
    }

    private static func message(_ role: String, _ serverFingerprint: Data, _ clientFingerprint: Data) -> Data {
        precondition(serverFingerprint.count == 32, "server fingerprint must be 32 bytes")
        precondition(clientFingerprint.count == 32, "client fingerprint must be 32 bytes")
        return Data("droiddrop-pair-v1|\(role)|".utf8) + serverFingerprint + clientFingerprint
    }
}
