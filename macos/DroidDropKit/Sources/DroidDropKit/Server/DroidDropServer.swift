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
        /// Closes a connection after this long without receiving anything (SPEC §5 rule 5).
        public var idleTimeout: TimeInterval
        /// Closes a connection that has not become an established session (TLS, hello, pairing) within this long.
        public var handshakeTimeout: TimeInterval
        /// Most connections one remote host may have that are not established sessions yet.
        public var pendingPerHost: Int

        public init(name: String, port: UInt16 = 47321, advertise: Bool = true, idleTimeout: TimeInterval = DroidDropServer.idleTimeout,
                    handshakeTimeout: TimeInterval = DroidDropServer.handshakeTimeout,
                    pendingPerHost: Int = DroidDropServer.pendingPerHost) {
            self.name = name
            self.port = port
            self.advertise = advertise
            self.idleTimeout = idleTimeout
            self.handshakeTimeout = handshakeTimeout
            self.pendingPerHost = pendingPerHost
        }
    }

    /// One established session. A peer's next session gets a new one, so frames meant for a replaced session
    /// can be dropped instead of reaching its successor (SPEC §5 rule 6).
    public struct SessionID: Hashable, Sendable {
        fileprivate let value: UInt64
    }

    public static let serviceType = "_droiddrop._tcp"
    public static let idleTimeout: TimeInterval = 90
    /// Pairing is a QR scan away from the connect, and a reconnect finishes in well under a second.
    public static let handshakeTimeout: TimeInterval = 15
    /// A phone opens one connection at a time; a few spare for reconnect overlap.
    public static let pendingPerHost = 4

    public let queue = DispatchQueue(label: "dev.ivo.droiddrop.server")
    public let identity: Identity
    public var onConnectedPeersChanged: (([Peer]) -> Void)?
    /// After `start` succeeded: the port while listening again, nil while the listener is down and being restarted.
    public var onListeningChanged: ((UInt16?) -> Void)?
    /// The listener is waiting (no usable network, Local Network access not granted). It starts by itself later.
    public var onListenerWaiting: ((Error) -> Void)?
    public var onPeerPaired: ((Peer) -> Void)?
    public var onMessage: ((Peer, Message) -> Void)?
    /// An established (registered) session: peer fingerprint and the session's id for `send` / `sendChunk`.
    public var onSessionStarted: ((Data, SessionID) -> Void)?
    /// Also fired for a session replaced by a newer one, before the newer one's onSessionStarted.
    public var onSessionEnded: ((Data) -> Void)?
    /// Chunk from an established session: fingerprint, transferId, offset, data.
    public var onChunk: ((Data, String, UInt64, Data) -> Void)?

    private let configuration: Configuration
    private let peers: PeerStore
    private let pairing = PairingWindow()
    /// The stale peer that the open pairing window's new phone replaces ("Pair Again").
    private var pairingReplaces: Data?
    private var listener: NWListener?
    private var port: UInt16?
    /// Between `start` and `stop`; a restart scheduled after `stop` does nothing.
    private var running = false
    private var listenerFailures = 0
    /// The port the last ready listener had: a restart takes it again, because phones remember it.
    private var lastPort: UInt16?
    private var connections: [ObjectIdentifier: FramedConnection] = [:]
    /// Remote host of each connection that is not an established session yet (`pendingPerHost`).
    private var pendingHosts: [ObjectIdentifier: String] = [:]
    private var sessions: [Data: Session] = [:]
    private var nextSessionID: UInt64 = 0

    private final class Session {
        let id: SessionID
        let framed: FramedConnection
        let fingerprint: Data
        var machine: ServerSession
        var lastReceived = ContinuousClock.now
        var watchdog: DispatchSourceTimer?

        init(id: SessionID, framed: FramedConnection, fingerprint: Data, machine: ServerSession) {
            self.id = id
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

    /// Listens on the configured port, falling back to an OS-assigned one. `completion` runs once, on `queue`, when
    /// the listener is ready or has failed for good. While it waits (no network yet, the Local Network prompt still
    /// open) `completion` does not run. A listener that fails later is restarted; see `onListeningChanged`.
    public func start(completion: @escaping (Result<UInt16, Error>) -> Void) {
        queue.async { [weak self] in
            guard let self else { return completion(.failure(CancellationError())) }
            self.running = true
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
            self.running = false
            self.listener?.cancel()
            self.listener = nil
            self.port = nil
            self.closePairing()
            self.connections.values.forEach { $0.cancel() }
        }
    }

    /// `completion` is nil for a restart, which reports through `onListeningChanged` instead.
    private func startListener(port: UInt16, completion: ((Result<UInt16, Error>) -> Void)?) {
        let listener: NWListener
        do {
            let parameters = TLSParameters.make(identity: try identity.secIdentity(), queue: queue) { [weak self] fingerprint in
                guard let self else { return false }
                return self.peers.contains(fingerprint) || self.pairing.activeSecret != nil
            }
            listener = try NWListener(using: parameters, on: port == 0 ? .any : NWEndpoint.Port(rawValue: port)!)
        } catch {
            if let completion { completion(.failure(error)) } else { scheduleRestart() }
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
        var completion = completion
        var wasReady = false
        listener.stateUpdateHandler = { [weak self, weak listener] state in
            guard let self, let listener else {
                completion?(.failure(CancellationError()))
                completion = nil
                return
            }
            guard self.listener === listener else {
                // Replaced, failed or stopped: its events are stale, except that a stop before ready ends `start`.
                if case .cancelled = state, let done = completion {
                    completion = nil
                    done(.failure(CancellationError()))
                }
                return
            }
            switch state {
            case .ready:
                wasReady = true
                self.port = listener.port?.rawValue
                self.lastPort = self.port
                self.listenerFailures = 0
                if let done = completion {
                    completion = nil
                    done(.success(self.port ?? port))
                } else {
                    self.onListeningChanged?(self.port)
                }
            case .failed(let error):
                listener.cancel()
                self.listener = nil
                if port != 0 && !wasReady {
                    // Port taken (EADDRINUSE arrives as .failed): an OS-assigned one; pairing URIs carry it.
                    let handOver = completion
                    completion = nil  // this listener's .cancelled must not end `start`
                    self.startListener(port: 0, completion: handOver)
                } else if let done = completion {
                    completion = nil
                    done(.failure(error))
                } else {
                    self.listenerFailed()
                }
            case .waiting(let error):
                // No usable network yet, or Local Network access not granted: Network starts it when it can.
                self.onListenerWaiting?(error)
            case .setup, .cancelled:
                break
            @unknown default:
                break
            }
        }
        self.listener = listener
        listener.start(queue: queue)
    }

    /// A listener that was serving died: report it and bring a new one up, with backoff.
    private func listenerFailed() {
        port = nil
        onListeningChanged?(nil)
        scheduleRestart()
    }

    private func scheduleRestart() {
        listenerFailures += 1
        let delay = min(30, pow(2, Double(listenerFailures - 1)))
        queue.asyncAfter(deadline: .now() + delay) { [weak self] in
            guard let self, self.running, self.listener == nil else { return }
            self.startListener(port: self.lastPort ?? self.configuration.port, completion: nil)
        }
    }

    /// Test hook: connections that are not established sessions yet.
    func pendingCountForTesting() -> Int {
        queue.sync { pendingHosts.count }
    }

    /// Test hook: delivers `.failed` to the current listener's state handler, as Network does.
    func failListenerForTesting() {
        queue.async { [weak self] in
            self?.listener?.stateUpdateHandler?(.failed(.posix(.ECONNABORTED)))
        }
    }

    // MARK: Public operations

    public func openPairingWindow(hosts: [String], replacing: Data? = nil) -> PairURI {
        dispatchPrecondition(condition: .notOnQueue(queue))
        return queue.sync {
            pairingReplaces = replacing
            return PairURI(name: configuration.name, hosts: hosts, port: Int(port ?? configuration.port),
                           fingerprint: identity.fingerprint, secret: pairing.open())
        }
    }

    public func closePairingWindow() {
        dispatchPrecondition(condition: .notOnQueue(queue))
        queue.sync { closePairing() }
    }

    public func pairedPeers() -> [Peer] {
        dispatchPrecondition(condition: .notOnQueue(queue))
        return queue.sync { peers.all }
    }

    public func connectedPeers() -> [Peer] {
        dispatchPrecondition(condition: .notOnQueue(queue))
        return queue.sync { currentConnectedPeers() }
    }

    /// Throws if the peer list cannot be saved; the peer then stays paired and connected.
    public func unpair(fingerprint: Data) throws {
        dispatchPrecondition(condition: .notOnQueue(queue))
        try queue.sync {
            try peers.remove(fingerprint)
            sessions[fingerprint]?.framed.cancel()
        }
    }

    /// Sends to the established session of `fingerprint`; with `session`, only while that session is still it.
    public func send(_ message: Message, to fingerprint: Data, session: SessionID? = nil) {
        queue.async { [weak self] in self?.established(fingerprint, session)?.framed.send(message) }
    }

    /// Sends a chunk to the established session of `fingerprint` (with `session`, only to that one);
    /// `completion(false)` when there is none. Safe from any thread.
    /// Frames sent through `send(_:to:session:)` and here leave in call order. `completion` runs on `queue`.
    public func sendChunk(transferId: String, offset: UInt64, data: Data, to fingerprint: Data, session id: SessionID? = nil,
                          completion: @escaping @Sendable (Bool) -> Void) {
        queue.async { [weak self] in
            guard let session = self?.established(fingerprint, id) else { return completion(false) }
            session.framed.send(.chunk(transferId: transferId, offset: offset, data: data), completion: completion)
        }
    }

    /// Sends to every established session. Dropped when there is none; nothing is queued for later sessions.
    public func broadcast(_ message: Message) {
        queue.async { [weak self] in self?.sessions.values.forEach { $0.framed.send(message) } }
    }

    // MARK: Sessions

    private func accept(_ connection: NWConnection) {
        let host = Self.remoteHost(of: connection)
        guard pendingHosts.values.filter({ $0 == host }).count < configuration.pendingPerHost else {
            connection.cancel()
            return
        }
        let framed = FramedConnection(connection: connection, queue: queue)
        let id = ObjectIdentifier(framed)
        pendingHosts[id] = host
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
                self.nextSessionID += 1
                let newSession = Session(id: SessionID(value: self.nextSessionID), framed: framed,
                                         fingerprint: fingerprint, machine: machine)
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
                self?.pendingHosts[id] = nil
            }
        }
        connections[id] = framed
        // Shorter than idleTimeout: an unauthenticated socket (no TLS, or an unpaired session in an open pairing
        // window) must not be held for 90 s.
        queue.asyncAfter(deadline: .now() + configuration.handshakeTimeout) { [weak self, weak framed] in
            guard let self, let framed else { return }
            if let session, self.sessions[session.fingerprint] === session { return }
            framed.cancel()
        }
        framed.start()
    }

    private static func remoteHost(of connection: NWConnection) -> String {
        if case .hostPort(let host, _) = connection.endpoint { return "\(host)" }
        return "\(connection.endpoint)"
    }

    private func handle(_ frame: Frame, in session: Session) {
        session.lastReceived = ContinuousClock.now
        let data: Data
        switch frame {
        case let .chunk(transferId, offset, chunk):
            // Only an established session may send chunks; before pairing only pair_request is allowed (SPEC §5 rule 3).
            guard sessions[session.fingerprint] === session else {
                session.framed.close()
                return
            }
            onChunk?(session.fingerprint, transferId, offset, chunk)
            return
        case .control(let payload):
            data = payload
        }
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
                let replaced = pairingReplaces.flatMap { $0 == peer.fingerprint ? nil : $0 }
                do {
                    if let replaced { try peers.replace(replaced, with: peer) } else { try peers.add(peer) }
                } catch {
                    closePairing()
                    session.framed.close(final: [.pairResult(ok: false, proof: nil, reason: "store_failed")])
                    return
                }
                closePairing()
                if let replaced { sessions[replaced]?.framed.cancel() }
                onPeerPaired?(peer)
            case .close:
                session.framed.close()
            case .deliver(let delivered):
                if let peer = peers.peer(for: session.fingerprint) { onMessage?(peer, delivered) }
            }
        }
        if session.machine.isPaired, sessions[session.fingerprint] !== session {
            guard peers.contains(session.fingerprint) else {
                session.framed.close()
                return
            }
            register(session)
        }
    }

    private func closePairing() {
        pairing.close()
        pairingReplaces = nil
    }

    private func register(_ session: Session) {
        if let old = sessions[session.fingerprint], old !== session {
            old.framed.cancel()
            onSessionEnded?(session.fingerprint)
        }
        sessions[session.fingerprint] = session
        pendingHosts[ObjectIdentifier(session.framed)] = nil
        onConnectedPeersChanged?(currentConnectedPeers())
        onSessionStarted?(session.fingerprint, session.id)
    }

    private func unregister(_ session: Session) {
        guard sessions[session.fingerprint] === session else { return }
        sessions[session.fingerprint] = nil
        onConnectedPeersChanged?(currentConnectedPeers())
        onSessionEnded?(session.fingerprint)
    }

    private func established(_ fingerprint: Data, _ id: SessionID?) -> Session? {
        guard let session = sessions[fingerprint], id == nil || session.id == id else { return nil }
        return session
    }

    private func currentConnectedPeers() -> [Peer] {
        sessions.keys.compactMap { peers.peer(for: $0) }.sorted { $0.name < $1.name }
    }

    private func startWatchdog(for session: Session) {
        let timeout = Duration.seconds(configuration.idleTimeout)
        let interval = min(10, configuration.idleTimeout / 2)
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + interval, repeating: interval)
        timer.setEventHandler { [weak session] in
            guard let session else { return }
            if ContinuousClock.now - session.lastReceived > timeout {
                session.framed.cancel()
            }
        }
        timer.resume()
        session.watchdog = timer
    }
}
