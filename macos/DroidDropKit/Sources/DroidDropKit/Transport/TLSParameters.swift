import Foundation
import Network
import Security

public enum TLSParameters {
    /// TLS 1.3 with mutual authentication. Chain validation is replaced by `acceptPeer(fingerprint)`, called on `queue`.
    public static func make(identity: SecIdentity, queue: DispatchQueue, acceptPeer: @escaping (Data) -> Bool) -> NWParameters {
        let tls = NWProtocolTLS.Options()
        let options = tls.securityProtocolOptions
        sec_protocol_options_set_min_tls_protocol_version(options, .TLSv13)
        sec_protocol_options_set_max_tls_protocol_version(options, .TLSv13)
        sec_protocol_options_set_local_identity(options, sec_identity_create(identity)!)
        sec_protocol_options_set_peer_authentication_required(options, true)
        sec_protocol_options_set_verify_block(options, { _, trust, complete in
            let chain = SecTrustCopyCertificateChain(sec_trust_copy_ref(trust).takeRetainedValue()) as? [SecCertificate]
            guard let leaf = chain?.first else {
                complete(false)
                return
            }
            complete(acceptPeer(Pairing.fingerprint(certificateDER: SecCertificateCopyData(leaf) as Data)))
        }, queue)

        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        tcp.enableKeepalive = true
        tcp.keepaliveIdle = 30
        let parameters = NWParameters(tls: tls, tcp: tcp)
        parameters.allowLocalEndpointReuse = true
        return parameters
    }

    /// Fingerprint of the peer's leaf certificate; available once the connection is `.ready`.
    public static func peerFingerprint(of connection: NWConnection) -> Data? {
        guard let metadata = connection.metadata(definition: NWProtocolTLS.definition) as? NWProtocolTLS.Metadata else { return nil }
        var fingerprint: Data?
        _ = sec_protocol_metadata_access_peer_certificate_chain(metadata.securityProtocolMetadata) { certificate in
            if fingerprint == nil {
                let der = SecCertificateCopyData(sec_certificate_copy_ref(certificate).takeRetainedValue()) as Data
                fingerprint = Pairing.fingerprint(certificateDER: der)
            }
        }
        return fingerprint
    }
}
