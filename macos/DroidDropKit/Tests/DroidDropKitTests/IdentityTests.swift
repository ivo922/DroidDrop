import Foundation
import Security
import Testing
@testable import DroidDropKit

@Suite struct IdentityTests {
    @Test func generatedIdentityBecomesSecIdentityWithSameCertificate() throws {
        let identity = try Identity.generate(commonName: "Test")
        #expect(identity.fingerprint == Pairing.fingerprint(certificateDER: identity.certificateDER))

        let secIdentity = try identity.secIdentity()
        var certificate: SecCertificate?
        #expect(SecIdentityCopyCertificate(secIdentity, &certificate) == errSecSuccess)
        #expect(SecCertificateCopyData(certificate!) as Data == identity.certificateDER)
    }

    @Test func storeCreatesOnceThenReloads() throws {
        let directory = FileManager.default.temporaryDirectory.appending(path: "droiddrop-identity-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = IdentityStore(directory: directory)

        let first = try store.loadOrCreate(commonName: "Test")
        let second = try store.loadOrCreate(commonName: "Test")
        #expect(first.fingerprint == second.fingerprint)

        let attributes = try FileManager.default.attributesOfItem(atPath: directory.appending(path: "identity.key").path)
        #expect((attributes[.posixPermissions] as! NSNumber).intValue == 0o600)
        let directoryAttributes = try FileManager.default.attributesOfItem(atPath: directory.path)
        #expect((directoryAttributes[.posixPermissions] as! NSNumber).intValue == 0o700)
    }

    @Test func corruptCertificateThrowsInvalidCertificate() throws {
        let (store, directory) = try populatedStore()
        defer { try? FileManager.default.removeItem(at: directory) }
        try Data([0x30, 0x03, 0x01, 0x02]).write(to: directory.appending(path: "identity.der"))

        #expect(throws: IdentityError.invalidCertificate) { try store.loadOrCreate(commonName: "Test") }
    }

    @Test func onlyKeyPresentThrowsAndKeepsKey() throws {
        let (store, directory) = try populatedStore()
        defer { try? FileManager.default.removeItem(at: directory) }
        let keyURL = directory.appending(path: "identity.key")
        let keyBytes = try Data(contentsOf: keyURL)
        try FileManager.default.removeItem(at: directory.appending(path: "identity.der"))

        #expect(throws: IdentityError.incompleteIdentity) { try store.loadOrCreate(commonName: "Test") }
        #expect(try Data(contentsOf: keyURL) == keyBytes)
    }

    @Test func onlyCertificatePresentThrows() throws {
        let (store, directory) = try populatedStore()
        defer { try? FileManager.default.removeItem(at: directory) }
        try FileManager.default.removeItem(at: directory.appending(path: "identity.key"))

        #expect(throws: IdentityError.incompleteIdentity) { try store.loadOrCreate(commonName: "Test") }
    }

    @Test func mismatchedKeyAndCertificateThrowsKeyMismatch() throws {
        let (store, directory) = try populatedStore()
        defer { try? FileManager.default.removeItem(at: directory) }
        let other = try Identity.generate(commonName: "Other")
        try other.certificateDER.write(to: directory.appending(path: "identity.der"))

        #expect(throws: IdentityError.keyMismatch) { try store.loadOrCreate(commonName: "Test") }
    }

    private func populatedStore() throws -> (IdentityStore, URL) {
        let directory = FileManager.default.temporaryDirectory.appending(path: "droiddrop-identity-\(UUID().uuidString)")
        let store = IdentityStore(directory: directory)
        _ = try store.loadOrCreate(commonName: "Test")
        return (store, directory)
    }
}
