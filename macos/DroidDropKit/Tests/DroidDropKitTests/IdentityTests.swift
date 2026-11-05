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
    }
}
