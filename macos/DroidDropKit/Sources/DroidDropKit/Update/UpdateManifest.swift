import CryptoKit
import Foundation

public struct UpdateAsset: Codable, Equatable, Sendable {
    public let url: String
    public let sha256: String
    public let size: Int

    public init(url: String, sha256: String, size: Int) {
        self.url = url
        self.sha256 = sha256
        self.size = size
    }
}

public struct UpdateManifest: Codable, Equatable, Sendable {
    public let version: String
    public let versionCode: Int
    public let notes: String
    public let macos: UpdateAsset
    public let android: UpdateAsset

    public init(version: String, versionCode: Int, notes: String, macos: UpdateAsset, android: UpdateAsset) {
        self.version = version
        self.versionCode = versionCode
        self.notes = notes
        self.macos = macos
        self.android = android
    }
}

public enum UpdateVerificationError: Error, Equatable, LocalizedError {
    case invalidPublicKey
    case invalidSignatureFormat
    case signatureMismatch
    case invalidManifest(String)
    case invalidAssetURL(String)
    case invalidHash(String)
    case invalidSize

    public var errorDescription: String? {
        switch self {
        case .invalidPublicKey: return "Update verification key is invalid."
        case .invalidSignatureFormat: return "Update signature format is invalid."
        case .signatureMismatch: return "Update manifest signature could not be verified."
        case .invalidManifest(let detail): return "Update manifest is invalid: \(detail)"
        case .invalidAssetURL(let url): return "Update download URL is not allowed: \(url)"
        case .invalidHash(let hash): return "Update manifest hash is invalid: \(hash)"
        case .invalidSize: return "Update manifest asset size is invalid."
        }
    }
}

/// Verifies detached Ed25519 signatures over raw `update.json` bytes before decoding JSON.
public enum UpdateVerifier {
    public static func verifyAndDecode(
        manifestData: Data,
        signatureBase64: String,
        publicKeyBase64: String = UpdateConfig.publicKeyBase64,
        allowedDownloadPrefix: String = UpdateConfig.allowedDownloadPrefix
    ) throws -> UpdateManifest {
        guard let keyData = Data(base64Encoded: publicKeyBase64.trimmingCharacters(in: .whitespacesAndNewlines)),
              keyData.count == 32,
              let publicKey = try? Curve25519.Signing.PublicKey(rawRepresentation: keyData) else {
            throw UpdateVerificationError.invalidPublicKey
        }
        guard let sigData = Data(base64Encoded: signatureBase64.trimmingCharacters(in: .whitespacesAndNewlines)),
              sigData.count == 64 else {
            throw UpdateVerificationError.invalidSignatureFormat
        }
        guard publicKey.isValidSignature(sigData, for: manifestData) else {
            throw UpdateVerificationError.signatureMismatch
        }

        let manifest: UpdateManifest
        do {
            manifest = try JSONDecoder().decode(UpdateManifest.self, from: manifestData)
        } catch {
            throw UpdateVerificationError.invalidManifest(error.localizedDescription)
        }
        guard !manifest.version.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw UpdateVerificationError.invalidManifest("empty version")
        }
        guard manifest.versionCode > 0 else {
            throw UpdateVerificationError.invalidManifest("versionCode must be positive")
        }
        try validate(manifest.macos, allowedDownloadPrefix: allowedDownloadPrefix)
        try validate(manifest.android, allowedDownloadPrefix: allowedDownloadPrefix)
        return manifest
    }

    private static func validate(_ asset: UpdateAsset, allowedDownloadPrefix: String) throws {
        guard asset.url.hasPrefix(allowedDownloadPrefix),
              let parsed = URL(string: asset.url),
              parsed.scheme == "https" else {
            throw UpdateVerificationError.invalidAssetURL(asset.url)
        }
        guard isHexSHA256(asset.sha256) else {
            throw UpdateVerificationError.invalidHash(asset.sha256)
        }
        guard asset.size > 0 else {
            throw UpdateVerificationError.invalidSize
        }
    }

    private static func isHexSHA256(_ value: String) -> Bool {
        guard value.count == 64 else { return false }
        return value.unicodeScalars.allSatisfy { scalar in
            (0x30...0x39).contains(scalar.value)
                || (0x61...0x66).contains(scalar.value)
                || (0x41...0x46).contains(scalar.value)
        }
    }
}
