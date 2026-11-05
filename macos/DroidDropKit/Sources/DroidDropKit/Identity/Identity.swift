import CryptoKit
import Foundation
import Security
import SwiftASN1
import X509

public enum IdentityError: Error {
    case invalidCertificate
    case keyMismatch
    case incompleteIdentity
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
        let fileManager = FileManager.default
        let keyExists = fileManager.fileExists(atPath: keyURL.path)
        let certificateExists = fileManager.fileExists(atPath: certificateURL.path)

        if keyExists && certificateExists {
            return try load(keyURL: keyURL, certificateURL: certificateURL)
        }
        guard !keyExists && !certificateExists else { throw IdentityError.incompleteIdentity }

        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true,
                                        attributes: [.posixPermissions: 0o700])
        let identity = try Identity.generate(commonName: commonName)
        try writePrivately(identity.privateKey.rawRepresentation, to: keyURL)
        try identity.certificateDER.write(to: certificateURL, options: .atomic)
        return identity
    }

    private func load(keyURL: URL, certificateURL: URL) throws -> Identity {
        let privateKey = try P256.Signing.PrivateKey(rawRepresentation: Data(contentsOf: keyURL))
        let certificateDER = try Data(contentsOf: certificateURL)
        guard let certificate = try? Certificate(derEncoded: Array(certificateDER)) else {
            throw IdentityError.invalidCertificate
        }
        guard certificate.publicKey == Certificate.PublicKey(privateKey.publicKey) else {
            throw IdentityError.keyMismatch
        }
        return Identity(certificateDER: certificateDER, privateKey: privateKey)
    }

    /// The key must never exist on disk with permissions wider than 0600, even briefly.
    private func writePrivately(_ data: Data, to url: URL) throws {
        let temporaryPath = directory.appending(path: ".identity.key.\(UUID().uuidString)").path
        let descriptor = open(temporaryPath, O_WRONLY | O_CREAT | O_EXCL, 0o600)
        guard descriptor >= 0 else { throw posixError() }
        var succeeded = false
        defer { if !succeeded { unlink(temporaryPath) } }

        let writeResult: Int32 = data.withUnsafeBytes { buffer in
            var offset = 0
            while offset < buffer.count {
                let written = write(descriptor, buffer.baseAddress! + offset, buffer.count - offset)
                if written < 0 {
                    if errno == EINTR { continue }
                    return errno
                }
                offset += written
            }
            return fsync(descriptor) == 0 ? 0 : errno
        }
        let closeResult = close(descriptor) == 0 ? 0 : errno
        if writeResult != 0 { throw posixError(writeResult) }
        if closeResult != 0 { throw posixError(closeResult) }
        guard rename(temporaryPath, url.path) == 0 else { throw posixError() }
        succeeded = true
    }

    private func posixError(_ code: Int32 = errno) -> POSIXError {
        POSIXError(POSIXErrorCode(rawValue: code) ?? .EIO)
    }
}
