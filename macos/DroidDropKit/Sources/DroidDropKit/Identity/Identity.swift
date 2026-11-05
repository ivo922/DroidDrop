import CryptoKit
import Foundation
import Security
import SwiftASN1
import X509

public enum IdentityError: Error {
    case invalidCertificate
    case keyMismatch
}

public struct Identity: Sendable {
    public let certificateDER: Data
    public let privateKey: P256.Signing.PrivateKey

    public var fingerprint: Data { Pairing.fingerprint(certificateDER: certificateDER) }

    public static func generate(commonName: String) throws -> Identity {
        let key = P256.Signing.PrivateKey()
        let name = try DistinguishedName { CommonName(commonName) }
        let now = Date()
        let certificate = try Certificate(
            version: .v3,
            serialNumber: Certificate.SerialNumber(),
            publicKey: Certificate.PublicKey(key.publicKey),
            notValidBefore: now.addingTimeInterval(-86_400),
            notValidAfter: now.addingTimeInterval(20 * 365 * 86_400),
            issuer: name,
            subject: name,
            signatureAlgorithm: .ecdsaWithSHA256,
            extensions: try Certificate.Extensions { Critical(BasicConstraints.notCertificateAuthority) },
            issuerPrivateKey: Certificate.PrivateKey(key)
        )
        var serializer = DER.Serializer()
        try serializer.serialize(certificate)
        return Identity(certificateDER: Data(serializer.serializedBytes), privateKey: key)
    }

    /// In-memory identity for TLS; never touches the keychain.
    public func secIdentity() throws -> SecIdentity {
        guard let certificate = SecCertificateCreateWithData(nil, certificateDER as CFData) else {
            throw IdentityError.invalidCertificate
        }
        let attributes: [CFString: Any] = [
            kSecAttrKeyType: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeyClass: kSecAttrKeyClassPrivate,
        ]
        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateWithData(privateKey.x963Representation as CFData, attributes as CFDictionary, &error) else {
            throw error!.takeRetainedValue() as Error
        }
        guard let identity = SecIdentityCreate(nil, certificate, key) else { throw IdentityError.keyMismatch }
        return identity
    }
}

public struct IdentityStore {
    public let directory: URL

    public init(directory: URL) {
        self.directory = directory
    }

    public func loadOrCreate(commonName: String) throws -> Identity {
        let keyURL = directory.appending(path: "identity.key")
        let certificateURL = directory.appending(path: "identity.der")
        if let keyData = try? Data(contentsOf: keyURL), let certificate = try? Data(contentsOf: certificateURL) {
            return Identity(certificateDER: certificate, privateKey: try P256.Signing.PrivateKey(rawRepresentation: keyData))
        }
        let fileManager = FileManager.default
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        let identity = try Identity.generate(commonName: commonName)
        try? fileManager.removeItem(at: keyURL)
        guard fileManager.createFile(atPath: keyURL.path, contents: identity.privateKey.rawRepresentation,
                                     attributes: [.posixPermissions: 0o600]) else {
            throw CocoaError(.fileWriteUnknown)
        }
        try identity.certificateDER.write(to: certificateURL, options: .atomic)
        return identity
    }
}
