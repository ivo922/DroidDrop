import Foundation
import Testing
@testable import DroidDropKit

@Suite struct PairingTests {
    let vectors = Fixtures.list(try! Fixtures.json("pairing"), "vectors")

    @Test func fingerprintsAndProofsMatchVectors() {
        for v in vectors {
            let secret = Base64URL.decode32(v["secret"] as! String)!
            let serverFP = Pairing.fingerprint(certificateDER: Data(hex: v["serverCertificateHex"] as! String))
            let clientFP = Pairing.fingerprint(certificateDER: Data(hex: v["clientCertificateHex"] as! String))
            #expect(Base64URL.encode(serverFP) == v["serverFingerprint"] as! String)
            #expect(Base64URL.encode(clientFP) == v["clientFingerprint"] as! String)
            #expect(Pairing.displayCode(fingerprint: serverFP) == v["serverDisplayCode"] as! String)
            #expect(Pairing.displayCode(fingerprint: clientFP) == v["clientDisplayCode"] as! String)

            let clientProof = Pairing.clientProof(secret: secret, serverFingerprint: serverFP, clientFingerprint: clientFP)
            let serverProof = Pairing.serverProof(secret: secret, serverFingerprint: serverFP, clientFingerprint: clientFP)
            #expect(Base64URL.encode(clientProof) == v["clientProof"] as! String)
            #expect(Base64URL.encode(serverProof) == v["serverProof"] as! String)
            #expect(Pairing.isValidClientProof(clientProof, secret: secret, serverFingerprint: serverFP, clientFingerprint: clientFP))
            #expect(Pairing.isValidServerProof(serverProof, secret: secret, serverFingerprint: serverFP, clientFingerprint: clientFP))
            #expect(!Pairing.isValidClientProof(serverProof, secret: secret, serverFingerprint: serverFP, clientFingerprint: clientFP))
        }
    }

    @Test func tamperedInputsInvalidateProofs() {
        for v in vectors {
            let secret = Base64URL.decode32(v["secret"] as! String)!
            let serverFP = Base64URL.decode32(v["serverFingerprint"] as! String)!
            let clientFP = Base64URL.decode32(v["clientFingerprint"] as! String)!
            let clientProof = Base64URL.decode32(v["clientProof"] as! String)!
            let serverProof = Base64URL.decode32(v["serverProof"] as! String)!
            let badSecret = flipFirstBit(secret)
            let badServerFP = flipFirstBit(serverFP)
            let badClientFP = flipFirstBit(clientFP)

            #expect(!Pairing.isValidClientProof(clientProof, secret: badSecret, serverFingerprint: serverFP, clientFingerprint: clientFP))
            #expect(!Pairing.isValidClientProof(clientProof, secret: secret, serverFingerprint: badServerFP, clientFingerprint: clientFP))
            #expect(!Pairing.isValidClientProof(clientProof, secret: secret, serverFingerprint: serverFP, clientFingerprint: badClientFP))
            #expect(!Pairing.isValidServerProof(serverProof, secret: badSecret, serverFingerprint: serverFP, clientFingerprint: clientFP))
            #expect(!Pairing.isValidServerProof(serverProof, secret: secret, serverFingerprint: badServerFP, clientFingerprint: clientFP))
            #expect(!Pairing.isValidServerProof(serverProof, secret: secret, serverFingerprint: serverFP, clientFingerprint: badClientFP))
            #expect(!Pairing.isValidClientProof(flipFirstBit(clientProof), secret: secret, serverFingerprint: serverFP, clientFingerprint: clientFP))
            #expect(!Pairing.isValidClientProof(clientProof.prefix(31), secret: secret, serverFingerprint: serverFP, clientFingerprint: clientFP))
        }
    }

    @Test func rejectsShortSecret() async {
        await #expect(processExitsWith: .failure) {
            _ = Pairing.clientProof(secret: Data(count: 31), serverFingerprint: Data(count: 32), clientFingerprint: Data(count: 32))
        }
    }

    @Test func rejectsShortServerFingerprint() async {
        await #expect(processExitsWith: .failure) {
            _ = Pairing.clientProof(secret: Data(count: 32), serverFingerprint: Data(count: 31), clientFingerprint: Data(count: 32))
        }
    }

    @Test func rejectsLongClientFingerprint() async {
        await #expect(processExitsWith: .failure) {
            _ = Pairing.serverProof(secret: Data(count: 32), serverFingerprint: Data(count: 32), clientFingerprint: Data(count: 33))
        }
    }

    @Test func rejectsShortSecretWhenValidating() async {
        await #expect(processExitsWith: .failure) {
            _ = Pairing.isValidServerProof(Data(count: 32), secret: Data(count: 31),
                                           serverFingerprint: Data(count: 32), clientFingerprint: Data(count: 32))
        }
    }

    @Test func rejectsShortDisplayCodeFingerprint() async {
        await #expect(processExitsWith: .failure) {
            _ = Pairing.displayCode(fingerprint: Data(count: 8))
        }
    }

    @Test func newSecretIs32RandomBytes() {
        let a = Pairing.newSecret()
        #expect(a.count == 32)
        #expect(a != Pairing.newSecret())
    }

    private func flipFirstBit(_ data: Data) -> Data {
        var copy = Data(data)
        copy[copy.startIndex] ^= 1
        return copy
    }
}
