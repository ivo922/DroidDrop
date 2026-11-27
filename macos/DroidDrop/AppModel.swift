import AppKit
import Combine
import DroidDropKit

/// Menu-bar state for the local server.
///
/// `isListening` follows `start`'s completion, then `onListeningChanged`: a listener that fails later goes
/// "Not listening" until the server's restart brings it back. While the listener waits for a network (or Local
/// Network access), `start` has not completed and the menu shows the waiting reason from `onListenerWaiting`.
@MainActor
final class AppModel: ObservableObject {
    @Published private(set) var pairedPeers: [Peer] = []
    @Published private(set) var connectedPeers: [Peer] = []
    @Published private(set) var pairURI: PairURI?
    @Published private(set) var pairingExpiresAt: Date?
    @Published private(set) var justPaired: Peer?
    /// Listener or peer-store failure. An unpair failure is `unpairError` so it does not hide this.
    @Published private(set) var serverError: String?
    @Published private(set) var unpairError: String?
    @Published private(set) var isListening = false
    @Published private(set) var noNetwork = false
    /// The paired phone that "Pair Again" replaces; nil for a new phone.
    @Published private(set) var replacingPeer: Peer?
    /// Progress lines for the status menu; empty when no transfer is under way.
    @Published private(set) var transferLines: [String] = []
    /// A file is being sent or waiting for the phone.
    @Published private(set) var isSending = false

    let macCode: String
    /// `~/Downloads/DroidDrop`, shared with FileTransfers so the menu opens where files land (R6).
    let receivedFilesFolder = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
        .appending(path: "DroidDrop")
    private let server: DroidDropServer?
    private var clipboard: ClipboardMonitor?
    private var transfers: FileTransfers?
    private let notifier: TransferNotifier
    private var wantsPairing = false

    init(notifier: TransferNotifier) throws {
        self.notifier = notifier
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appending(path: "DroidDrop")
        let identity = try IdentityStore(directory: support).loadOrCreate(commonName: "DroidDrop Mac")
        macCode = Pairing.displayCode(fingerprint: identity.fingerprint)

        let peers: PeerStore
        do {
            peers = try PeerStore(url: support.appending(path: "peers.json"))
        } catch {
            server = nil
            serverError = Self.describe(error)
            return
        }

        let server = DroidDropServer(
            identity: identity,
            peers: peers,
            configuration: .init(name: Self.serverName())
        )
        self.server = server
        let transfers = FileTransfers(
            server: server,
            partials: PartialStore(directory: support.appending(path: "partials")),
            destination: receivedFilesFolder
        )
        self.transfers = transfers
        server.onMessage = { [weak self, weak transfers] peer, message in
            guard case .clip(_, let mime, let text) = message else {
                transfers?.receive(message, from: peer.fingerprint)
                return
            }
            // Rejected clips are dropped silently: their text is never logged.
            guard let text = ClipboardInbox.accept(mime: mime, text: text) else { return }
            DispatchQueue.main.async {
                MainActor.assumeIsolated { _ = self?.clipboard?.apply(text) }
            }
        }
        // DispatchQueue.main keeps FIFO order (one Task per callback does not), so a stale progress snapshot can
        // never land after the final idle one.
        transfers.onSnapshot = { [weak self] snapshot in
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    guard let self else { return }
                    let lines = snapshot.menuLines
                    let sending = snapshot.outgoing != nil || snapshot.waitingToSend > 0
                    self.transferLines = lines
                    self.isSending = sending
                }
            }
        }
        transfers.onOutcome = { [weak self] fingerprint, outcome in
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    guard let self else { return }
                    let name = self.pairedPeers.first { $0.fingerprint == fingerprint }?.name ?? "your phone"
                    self.notifier.post(outcome, peerName: name)
                }
            }
        }
        transfers.onBatchSummary = { [weak self] fingerprint, summary in
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    guard let self else { return }
                    let name = self.pairedPeers.first { $0.fingerprint == fingerprint }?.name ?? "your phone"
                    self.notifier.postSummary(summary, peerName: name, fingerprint: fingerprint)
                }
            }
        }
        notifier.onRetry = { [weak self] urls, fingerprint in self?.retry(urls, to: fingerprint) }
        clipboard = ClipboardMonitor { [weak server] message in server?.broadcast(message) }
        server.onConnectedPeersChanged = { [weak self] peers in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.connectedPeers = peers
                // Watch the pasteboard only while a phone can receive clips (fewer wakeups, no access alerts).
                if peers.isEmpty { self.clipboard?.stop() } else { self.clipboard?.start() }
            }
        }
        server.onPeerPaired = { [weak self] peer in
            Task { @MainActor [weak self] in
                guard let self else { return }
                // "Pair Again" removed the old fingerprint (the server skips a replace by the same one): drop its queue.
                if let replaced = self.replacingPeer?.fingerprint, replaced != peer.fingerprint {
                    self.transfers?.cancelSending(to: replaced, silent: true)
                }
                self.justPaired = peer
                self.pairURI = nil
                self.pairingExpiresAt = nil
                self.noNetwork = false
                self.refreshPeers()
            }
        }
        server.onListeningChanged = { [weak self] port in
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    guard let self else { return }
                    self.isListening = port != nil
                    // A restarted listener may have another port: an open QR code must carry the live one.
                    if port != nil, self.pairURI != nil { self.openPairingIfPossible() }
                }
            }
        }
        server.onListenerWaiting = { [weak self] error in
            let message = "Waiting for the network: " + Self.describe(error)
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    guard let self, !self.isListening else { return }
                    self.serverError = message
                }
            }
        }
        server.start { [weak self] result in
            let failure: String?
            if case .failure(let error) = result {
                failure = Self.describe(error)
            } else {
                failure = nil
            }
            Task { @MainActor [weak self] in
                guard let self else { return }
                if let failure {
                    self.isListening = false
                    self.serverError = failure
                } else {
                    self.isListening = true
                    self.serverError = nil
                }
                self.refreshPeers()
                if self.wantsPairing { self.openPairingIfPossible() }
            }
        }
    }

    /// Computer name when it is non-blank; otherwise "Mac" (SPEC §6.1 requires a non-empty name).
    static func serverName(localizedName: String? = Host.current().localizedName) -> String {
        let trimmed = localizedName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return trimmed.isEmpty ? "Mac" : trimmed
    }

    var statusLine: String {
        if !isListening {
            return serverError == nil ? "Starting…" : "Not listening"
        }
        if connectedPeers.isEmpty { return "No phone connected" }
        return "Connected: " + connectedPeers.map(\.name).joined(separator: ", ")
    }

    /// `replacing` is the stale entry a re-paired phone takes over, so it does not show up twice.
    func startPairing(replacing peer: Peer? = nil) {
        replacingPeer = peer
        restartPairing()
    }

    /// A fresh code for the same pairing target.
    func restartPairing() {
        wantsPairing = true
        justPaired = nil
        openPairingIfPossible()
    }

    /// Files go only to a connected phone (R6).
    var canSendFiles: Bool { !connectedPeers.isEmpty }

    func sendFiles(_ urls: [URL]) {
        guard let peer = connectedPeers.first else { return }
        transfers?.send(urls, to: peer.fingerprint)
    }

    /// Resends a summary's failed files, connected or not (R11), but only to a phone that is still paired.
    func retry(_ urls: [URL], to fingerprint: Data) {
        // Ask the server, not the cached `pairedPeers`: a Retry clicked right after a relaunch (or while the listener
        // waits for a network) arrives before that cache is filled, and would be dropped.
        guard let server, server.pairedPeers().contains(where: { $0.fingerprint == fingerprint }) else { return }
        transfers?.send(urls, to: fingerprint)
    }

    /// Cancels the active file and drops the queue, including files waiting for a disconnected phone.
    func cancelSending() {
        transfers?.cancelAllSending()
    }

    /// Up to the 10 most recently received files in `receivedFilesFolder`, newest first.
    func recentReceivedFiles() -> [URL] {
        ReceivedFiles.recent(in: receivedFilesFolder)
    }

    /// Opens a received file with its default app. Returns the message to show on failure.
    @discardableResult
    func openReceivedFile(_ url: URL) -> String? {
        guard FileManager.default.fileExists(atPath: url.path) else {
            return "\(url.lastPathComponent) is no longer in the received files folder."
        }
        return NSWorkspace.shared.open(url) ? nil : "Could not open \(url.lastPathComponent)."
    }

    /// Creates the folder if missing (nothing received yet) and opens it in Finder. Returns the message to show on failure.
    @discardableResult
    func openReceivedFiles() -> String? {
        do {
            try FileManager.default.createDirectory(at: receivedFilesFolder, withIntermediateDirectories: true)
        } catch {
            return error.localizedDescription // the user-facing text, not the NSError dump describe() gives
        }
        return NSWorkspace.shared.open(receivedFilesFolder) ? nil : "Finder could not open \(receivedFilesFolder.path)"
    }

    func isConnected(_ peer: Peer) -> Bool {
        connectedPeers.contains { $0.fingerprint == peer.fingerprint }
    }

    func cancelPairing() {
        wantsPairing = false
        replacingPeer = nil
        noNetwork = false
        pairURI = nil
        pairingExpiresAt = nil
        server?.closePairingWindow()
    }

    /// Unpairs on the main actor. Returns the message to show when saving the peer list fails.
    @discardableResult
    func unpair(_ fingerprint: Data) -> String? {
        guard let server else {
            return serverError ?? "DroidDrop is not running"
        }
        do {
            try server.unpair(fingerprint: fingerprint)
            unpairError = nil
            transfers?.cancelSending(to: fingerprint, silent: true)  // an unpaired phone's queued files must not linger
            refreshPeers()
            return nil
        } catch {
            let message = error.localizedDescription // shown in an alert and the menu: user-facing text
            unpairError = message
            return message
        }
    }

    private func openPairingIfPossible() {
        guard wantsPairing, let server, isListening else {
            if !isListening {
                noNetwork = false
                pairURI = nil
                pairingExpiresAt = nil
            }
            return
        }
        let hosts = LocalAddresses.ipv4().filter(PairURI.isIPv4)
        guard !hosts.isEmpty else {
            server.closePairingWindow()
            noNetwork = true
            pairURI = nil
            pairingExpiresAt = nil
            serverError = nil
            return
        }
        noNetwork = false
        let uri = server.openPairingWindow(hosts: hosts, replacing: replacingPeer?.fingerprint)
        guard PairURI(string: uri.string) != nil else {
            server.closePairingWindow()
            pairURI = nil
            pairingExpiresAt = nil
            serverError = "Could not build a pairing link"
            return
        }
        serverError = nil
        pairURI = uri
        pairingExpiresAt = Date().addingTimeInterval(PairingWindow.lifetime)
    }

    private func refreshPeers() {
        pairedPeers = server?.pairedPeers() ?? []
    }

    private static func describe(_ error: Error) -> String {
        String(describing: error).replacingOccurrences(of: "\n", with: " ")
    }
}
