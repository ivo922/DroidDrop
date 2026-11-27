import CryptoKit
import Foundation
import Testing
@testable import DroidDropKit

@Suite struct UpdateTests {
    private let sampleSHA = String(repeating: "a", count: 64)

    private func signedManifest(
        key: Curve25519.Signing.PrivateKey = .init(),
        version: String = "0.4.0",
        versionCode: Int = 400,
        notes: String = "- New feature",
        macURL: String = "https://github.com/ivo922/DroidDrop/releases/download/v0.4.0/DroidDrop-0.4.0-macOS.zip",
        androidURL: String = "https://github.com/ivo922/DroidDrop/releases/download/v0.4.0/DroidDrop-0.4.0.apk",
        sha256: String? = nil,
        size: Int = 1024
    ) throws -> (data: Data, sigBase64: String, pubBase64: String) {
        let hash = sha256 ?? sampleSHA
        let manifest = UpdateManifest(
            version: version,
            versionCode: versionCode,
            notes: notes,
            macos: UpdateAsset(url: macURL, sha256: hash, size: size),
            android: UpdateAsset(url: androidURL, sha256: hash, size: size)
        )
        let data = try JSONEncoder().encode(manifest)
        let sig = try key.signature(for: data)
        return (data, sig.base64EncodedString(), key.publicKey.rawRepresentation.base64EncodedString())
    }

    @Test func verifiesValidSignedManifest() throws {
        let (data, sig, pub) = try signedManifest()
        let decoded = try UpdateVerifier.verifyAndDecode(manifestData: data, signatureBase64: sig + "\n", publicKeyBase64: pub)
        #expect(decoded.version == "0.4.0")
        #expect(decoded.versionCode == 400)
        #expect(decoded.notes == "- New feature")
    }

    @Test func rejectsTamperedManifest() throws {
        let (data, sig, pub) = try signedManifest()
        var tampered = data
        tampered.append(0x20)
        #expect(throws: UpdateVerificationError.signatureMismatch) {
            try UpdateVerifier.verifyAndDecode(manifestData: tampered, signatureBase64: sig, publicKeyBase64: pub)
        }
    }

    @Test func rejectsWrongPublicKey() throws {
        let (data, sig, _) = try signedManifest()
        let otherPub = Curve25519.Signing.PrivateKey().publicKey.rawRepresentation.base64EncodedString()
        #expect(throws: UpdateVerificationError.signatureMismatch) {
            try UpdateVerifier.verifyAndDecode(manifestData: data, signatureBase64: sig, publicKeyBase64: otherPub)
        }
    }

    @Test func rejectsOffOriginDownloadURL() throws {
        let (data, sig, pub) = try signedManifest(macURL: "https://evil.example.com/DroidDrop.zip")
        #expect(throws: UpdateVerificationError.invalidAssetURL("https://evil.example.com/DroidDrop.zip")) {
            try UpdateVerifier.verifyAndDecode(manifestData: data, signatureBase64: sig, publicKeyBase64: pub)
        }
    }

    @Test func rejectsInvalidHashAndZeroSize() throws {
        let (badHashData, badHashSig, badHashPub) = try signedManifest(sha256: "not-a-hash")
        #expect(throws: UpdateVerificationError.invalidHash("not-a-hash")) {
            try UpdateVerifier.verifyAndDecode(manifestData: badHashData, signatureBase64: badHashSig, publicKeyBase64: badHashPub)
        }

        let (zeroData, zeroSig, zeroPub) = try signedManifest(size: 0)
        #expect(throws: UpdateVerificationError.invalidSize) {
            try UpdateVerifier.verifyAndDecode(manifestData: zeroData, signatureBase64: zeroSig, publicKeyBase64: zeroPub)
        }
    }

    @Test func committedPublicKeyIsValid32ByteEd25519Key() throws {
        let raw = try #require(Data(base64Encoded: UpdateConfig.publicKeyBase64))
        #expect(raw.count == 32)
        _ = try Curve25519.Signing.PublicKey(rawRepresentation: raw)
    }

    @Test func updatePolicyVersionThrottleAndPrompting() {
        #expect(UpdatePolicy.isNewer(remoteVersionCode: 400, currentVersionCode: 300))
        #expect(!UpdatePolicy.isNewer(remoteVersionCode: 300, currentVersionCode: 300))
        #expect(!UpdatePolicy.isNewer(remoteVersionCode: 200, currentVersionCode: 300))

        let now = Date(timeIntervalSince1970: 1_800_000_000)
        #expect(UpdatePolicy.shouldAutoCheck(lastCheckedAt: nil, now: now))
        #expect(!UpdatePolicy.shouldAutoCheck(lastCheckedAt: now.addingTimeInterval(-3600), now: now))
        #expect(UpdatePolicy.shouldAutoCheck(lastCheckedAt: now.addingTimeInterval(-86_400), now: now))

        #expect(!UpdatePolicy.shouldPrompt(remoteVersionCode: 400, dismissedVersionCode: 400, manual: false))
        #expect(UpdatePolicy.shouldPrompt(remoteVersionCode: 400, dismissedVersionCode: 400, manual: true))
        #expect(UpdatePolicy.shouldPrompt(remoteVersionCode: 500, dismissedVersionCode: 400, manual: false))
    }

    @Test func installabilityDetectsTranslocationReadOnlyAndWritable() {
        #expect(
            UpdatePolicy.installability(
                bundleURL: URL(filePath: "/private/var/folders/xx/AppTranslocation/ABC/d/DroidDrop.app"),
                isWritable: { _ in true }
            ) == .translocated
        )
        #expect(
            UpdatePolicy.installability(
                bundleURL: URL(filePath: "/Volumes/DroidDrop/DroidDrop.app"),
                isWritable: { _ in false }
            ) == .readOnlyVolume
        )
        #expect(
            UpdatePolicy.installability(
                bundleURL: URL(filePath: "/Applications/DroidDrop.app"),
                isWritable: { _ in false }
            ) == .notWritable
        )
        #expect(
            UpdatePolicy.installability(
                bundleURL: URL(filePath: "/Applications/DroidDrop.app"),
                isWritable: { _ in true }
            ) == .writable
        )
    }

    @Test func verifyDownloadedFileChecksSizeAndHash() throws {
        let dir = FileManager.default.temporaryDirectory.appending(path: "DroidDrop-UpdateTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }

        let file = dir.appending(path: "payload.zip")
        let payload = Data("hello droiddrop update".utf8)
        try payload.write(to: file)
        let expected = try FileDigest.sha256(of: file)

        try MacUpdateInstaller.verifyDownloadedFile(file, expectedSize: expected.size, expectedSHA256: expected.hex)

        #expect(throws: MacUpdateError.self) {
            try MacUpdateInstaller.verifyDownloadedFile(file, expectedSize: expected.size + 1, expectedSHA256: expected.hex)
        }
        #expect(throws: MacUpdateError.self) {
            try MacUpdateInstaller.verifyDownloadedFile(file, expectedSize: expected.size, expectedSHA256: sampleSHA)
        }
    }

    @Test func extractAndValidateChecksBundleIDAndVersion() throws {
        let dir = FileManager.default.temporaryDirectory.appending(path: "DroidDrop-ZipTest-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }

        let fakeApp = dir.appending(path: "src/DroidDrop.app/Contents")
        try FileManager.default.createDirectory(at: fakeApp, withIntermediateDirectories: true)
        let plist: [String: Any] = [
            "CFBundleIdentifier": "dev.ivo.DroidDrop",
            "CFBundleShortVersionString": "0.4.0",
            "CFBundleVersion": "400",
        ]
        let plistData = try PropertyListSerialization.data(fromPropertyList: plist, format: .xml, options: 0)
        try plistData.write(to: fakeApp.appending(path: "Info.plist"))

        let zipURL = dir.appending(path: "DroidDrop-0.4.0-macOS.zip")
        let ditto = Process()
        ditto.executableURL = URL(filePath: "/usr/bin/ditto")
        ditto.arguments = ["-c", "-k", "--keepParent", dir.appending(path: "src/DroidDrop.app").path, zipURL.path]
        try ditto.run()
        ditto.waitUntilExit()
        #expect(ditto.terminationStatus == 0)

        let extracted = try MacUpdateInstaller.extractAndValidate(
            zipURL: zipURL,
            into: dir.appending(path: "out-ok"),
            expectedBundleID: "dev.ivo.DroidDrop",
            expectedVersionCode: 400,
            verifyCodeSign: false
        )
        #expect(extracted.lastPathComponent == "DroidDrop.app")

        #expect(throws: MacUpdateError.self) {
            try MacUpdateInstaller.extractAndValidate(
                zipURL: zipURL,
                into: dir.appending(path: "out-bad-ver"),
                expectedBundleID: "dev.ivo.DroidDrop",
                expectedVersionCode: 999,
                verifyCodeSign: false
            )
        }
    }
}
