import Foundation
import Network

/// TLS listener that runs one `ServerSession` per connection (SPEC §5–§7).
/// All sessions, the pairing window and the peer store are confined to `queue`.
/// Set the callbacks before `start`.
public final class DroidDropServer: @unchecked Sendable {
    public struct Configuration: Sendable {
        public var name: String
        public var port: UInt16
        public var advertise: Bool

        public init(name: String, port: UInt16 = 47321, advertise: Bool = true) {
            self.name = name
            self.port = port
            self.advertise = advertise
        }
    }

    public static let serviceType = "_droiddrop._tcp"
    public static let idleTimeout: TimeInterval = 90

    public let queue = DispatchQueue(label: "dev.ivo.droiddrop.server")
    public let identity: Identity
    public var onConnectedPeersChanged: (([Peer]) -> Void)?
    public var onPeerPaired: ((Peer) -> Void)?
    public var onMessage: ((Peer, Message) -> Void)?

    private let configuration: Configuration
    private let peers: PeerStore
    private let pairing = PairingWindow()
    private var listener: NWListener?
    private var port: UInt16?
    private var connections: [ObjectIdentifier: FramedConnection] = [:]
    private var sessions: [Data: Session] = [:]

    private final class Session {
        let framed: FramedConnection
        let fingerprint: Data
        var machine: ServerSession
        var lastReceived = Date()
        var watchdog: DispatchSourceTimer?

        init(framed: FramedConnection, fingerprint: Data, machine: ServerSession) {
            self.framed = framed
            self.fingerprint = fingerprint
            self.machine = machine
        }
    }

    public init(identity: Identity, peers: PeerStore, configuration: Configuration) {
        self.identity = identity
        self.peers = peers
        self.configuration = configuration
    }

    deinit {
        listener?.cancel()
        connections.values.forEach { $0.cancel() }
        sessions.values.forEach { $0.watchdog?.cancel() }
    }

    // MARK: Lifecycle

    /// Listens on the configured port, falling back to an OS-assigned one. `completion` runs once, on `queue`.
    public func start(completion: @escaping (Result<UInt16, Error>) -> Void) {
        queue.async { [weak self] in
            guard let self else { return completion(.failure(CancellationError())) }
            self.startListener(port: self.configuration.port, completion: completion)
        }
    }

    public func start() async throws -> UInt16 {
        try await withCheckedThrowingContinuation { continuation in
            start { continuation.resume(with: $0) }
        }
    }

    public func stop() {
        queue.async { [weak self] in
            guard let self else { return }
            self.listener?.cancel()
            self.listener = nil
            self.port = nil
            self.pairing.close()
            self.connections.values.forEach { $0.cancel() }
        }
    }

    private func startListener(port: UInt16, completion: @escaping (Result<UInt16, Error>) -> Void) {
        let listener: NWListener
        do {
            let parameters = TLSParameters.make(identity: try identity.secIdentity(), queue: queue) { [weak self] fingerprint in
                guard let self else { return false }
                return self.peers.contains(fingerprint) || self.pairing.activeSecret != nil
            }
            listener = try NWListener(using: parameters, on: port == 0 ? .any : NWEndpoint.Port(rawValue: port)!)
        } catch {
            completion(.failure(error))
            return
        }
        if configuration.advertise {
            let txt = NWTXTRecord(["v": "1", "fp": Base64URL.encode(identity.fingerprint)])
            listener.service = NWListener.Service(name: configuration.name, type: Self.serviceType, domain: nil, txtRecord: txt.data)
        }
        listener.newConnectionHandler = { [weak self] connection in
            guard let self else { return connection.cancel() }
            self.accept(connection)
        }
        var reported = false
        listener.stateUpdateHandler = { [weak self, weak listener] state in
            guard !reported else { return }
            guard let self, let listener else {
                reported = true
                return completion(.failure(CancellationError()))
            }
            switch state {
            case .ready:
                reported = true
                self.port = listener.port?.rawValue
                completion(.success(self.port ?? port))
            case .failed(let error), .waiting(let error):
                reported = true
                listener.cancel()
                if self.listener === listener { self.listener = nil }
                if port != 0 {
                    self.startListener(port: 0, completion: completion)
                } else {
                    completion(.failure(error))
                }
            case .cancelled:
                reported = true
                completion(.failure(CancellationError()))
            default:
                break
            }
        }
        self.listener = listener
        listener.start(queue: queue)
    }

    // MARK: Public operations

    public func openPairingWindow(hosts: [String]) -> PairURI {
        dispatchPrecondition(condition: .notOnQueue(queue))
        return queue.sync {
            PairURI(name: configuration.name, hosts: hosts, port: Int(port ?? configuration.port),
                    fingerprint: identity.fingerprint, secret: pairing.open())
        }
    }

    public func closePairingWindow() {
        dispatchPrecondition(condition: .notOnQueue(queue))
        queue.sync { pairing.close() }
    }

    public func pairedPeers() -> [Peer] {
        dispatchPrecondition(condition: .notOnQueue(queue))
        return queue.sync { peers.all }
    }

    public func connectedPeers() -> [Peer] {
        dispatchPrecondition(condition: .notOnQueue(queue))
        return queue.sync { currentConnectedPeers() }
    }

    public func unpair(fingerprint: Data) {
        queue.async { [weak self] in
            guard let self else { return }
            try? self.peers.remove(fingerprint)
            self.sessions[fingerprint]?.framed.cancel()
        }
    }

    public func send(_ message: Message, to fingerprint: Data) {
        queue.async { [weak self] in self?.sessions[fingerprint]?.framed.send(message) }
    }

    // MARK: Sessions

    private func accept(_ connection: NWConnection) {
        let framed = FramedConnection(connection: connection, queue: queue)
        let id = ObjectIdentifier(framed)
        var session: Session?
        framed.onEvent = { [weak self, weak framed] event in
            switch event {
            case .ready:
                guard let self, let framed, let fingerprint = TLSParameters.peerFingerprint(of: connection) else {
                    framed?.cancel()
                    return
                }
                let machine = ServerSession(serverFingerprint: self.identity.fingerprint, clientFingerprint: fingerprint,
                                            alreadyPaired: self.peers.contains(fingerprint))
                let newSession = Session(framed: framed, fingerprint: fingerprint, machine: machine)
                session = newSession
                framed.send(.hello(v: 1, name: self.configuration.name, platform: .macos))
                self.startWatchdog(for: newSession)
            case .frame(let frame):
                if let self, let session { self.handle(frame, in: session) }
            case .closed:
                session?.watchdog?.cancel()
                if let session { self?.unregister(session) }
                session = nil
                self?.connections[id] = nil
            }
        }
        connections[id] = framed
        queue.asyncAfter(deadline: .now() + Self.idleTimeout) { [weak framed] in
            if session == nil { framed?.cancel() }
        }
        framed.start()
    }

    private func handle(_ frame: Frame, in session: Session) {
        session.lastReceived = Date()
        guard case .control(let data) = frame else { return }
        let message: Message
        do {
            message = try MessageCodec.decode(data)
        } catch {
            session.framed.cancel()
            return
        }
        for action in session.machine.receive(message, pairingSecret: pairing.activeSecret) {
            switch action {
            case .send(let reply):
                session.framed.send(reply)
            case .pairPeer(let name):
                let peer = Peer(fingerprint: session.fingerprint, name: name, pairedAt: Date())
                do {
                    try peers.add(peer)
                } catch {
                    session.framed.cancel()
                    return
                }
                pairing.close()
                onPeerPaired?(peer)
            case .close:
                session.framed.close()
            case .deliver(let delivered):
                if let peer = peers.peer(for: session.fingerprint) { onMessage?(peer, delivered) }
            }
        }
        if session.machine.isPaired, sessions[session.fingerprint] !== session {
            register(session)
        }
    }

    private func register(_ session: Session) {
        if let old = sessions[session.fingerprint], old !== session { old.framed.cancel() }
        sessions[session.fingerprint] = session
        onConnectedPeersChanged?(currentConnectedPeers())
    }

    private func unregister(_ session: Session) {
        guard sessions[session.fingerprint] === session else { return }
        sessions[session.fingerprint] = nil
        onConnectedPeersChanged?(currentConnectedPeers())
    }

    private func currentConnectedPeers() -> [Peer] {
        sessions.keys.compactMap { peers.peer(for: $0) }.sorted { $0.name < $1.name }
    }

    private func startWatchdog(for session: Session) {
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 10, repeating: 10)
        timer.setEventHandler { [weak session] in
            guard let session else { return }
            if Date().timeIntervalSince(session.lastReceived) > Self.idleTimeout {
                session.framed.cancel()
            }
        }
        timer.resume()
        session.watchdog = timer
    }
}
