import AppKit
import DroidDropKit
import Foundation

/// Drives silent daily checks, manual checks, download progress, and atomic in-place app updates on macOS.
@MainActor
final class UpdateController: ObservableObject {
    enum Status: Equatable {
        case idle
        case checking
        case available(UpdateManifest)
        case downloading(manifest: UpdateManifest, progress: Double)
        case installing(manifest: UpdateManifest)
        case failed(manifest: UpdateManifest?, message: String)
    }

    private static let lastCheckKey = "updateLastCheckAt"
    private static let dismissedCodeKey = "updateDismissedVersionCode"

    @Published private(set) var status: Status = .idle
    @Published private(set) var availableUpdate: UpdateManifest?

    let currentVersion: String
    let currentVersionCode: Int
    private let defaults = UserDefaults.standard
    private var periodicTimer: Timer?
    private var activeTask: Task<Void, Never>?

    var onOpenWindow: (() -> Void)?
    var onAlert: ((String, String) -> Void)?

    init(bundle: Bundle = .main) {
        currentVersion = (bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) ?? "0.0.0"
        currentVersionCode = (bundle.object(forInfoDictionaryKey: "CFBundleVersion") as? String).flatMap(Int.init) ?? 0
    }

    var isBusy: Bool {
        switch status {
        case .checking, .downloading, .installing: return true
        case .idle, .available, .failed: return false
        }
    }

    var installability: UpdatePolicy.Installability {
        UpdatePolicy.installability(bundleURL: Bundle.main.bundleURL)
    }

    var cannotInstallReason: String? {
        UpdatePolicy.cannotInstallReason(for: installability)
    }

    func startAutomaticChecks() {
        guard periodicTimer == nil else { return }
        // Delay the first check slightly so launch stays instant.
        DispatchQueue.main.asyncAfter(deadline: .now() + 15) { [weak self] in
            self?.checkIfDue()
        }
        // Poll every 6 hours so Macs that sleep across days still run once per 24h window.
        periodicTimer = Timer.scheduledTimer(withTimeInterval: 6 * 60 * 60, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                self?.checkIfDue()
            }
        }
    }

    func checkIfDue() {
        guard !isBusy else { return }
        let last = defaults.object(forKey: Self.lastCheckKey) as? Date
        guard UpdatePolicy.shouldAutoCheck(lastCheckedAt: last) else { return }
        runCheck(manual: false)
    }

    func checkManually() {
        if case .downloading = status {
            onOpenWindow?()
            return
        }
        if case .installing = status {
            onOpenWindow?()
            return
        }
        guard !isBusy else { return }
        runCheck(manual: true)
    }

    func openUpdateWindow() {
        if let manifest = availableUpdate, case .idle = status {
            status = .available(manifest)
        }
        onOpenWindow?()
    }

    func dismiss(_ manifest: UpdateManifest) {
        defaults.set(manifest.versionCode, forKey: Self.dismissedCodeKey)
    }

    func openReleasesPage() {
        NSWorkspace.shared.open(UpdateConfig.releasesPageURL)
    }

    func install(_ manifest: UpdateManifest) {
        guard !isBusy else { return }
        if let reason = cannotInstallReason {
            status = .failed(manifest: manifest, message: reason)
            return
        }
        let bundleURL = Bundle.main.bundleURL
        let expectedBundleID = Bundle.main.bundleIdentifier ?? "dev.ivo.DroidDrop"
        status = .downloading(manifest: manifest, progress: 0)

        activeTask = Task { [weak self] in
            let staging = FileManager.default.temporaryDirectory
                .appending(path: "DroidDrop-Update-\(UUID().uuidString)")
            defer { try? FileManager.default.removeItem(at: staging) }
            do {
                let zipURL = staging.appending(path: "DroidDrop-\(manifest.version)-macOS.zip")
                try await MacUpdateInstaller.downloadAndVerify(asset: manifest.macos, to: zipURL) { progress in
                    DispatchQueue.main.async {
                        MainActor.assumeIsolated {
                            self?.status = .downloading(manifest: manifest, progress: progress)
                        }
                    }
                }
                self?.status = .installing(manifest: manifest)
                let extractedApp = try await Task.detached(priority: .userInitiated) {
                    try MacUpdateInstaller.extractAndValidate(
                        zipURL: zipURL,
                        into: staging.appending(path: "unpacked"),
                        expectedBundleID: expectedBundleID,
                        expectedVersionCode: manifest.versionCode,
                        verifyCodeSign: true
                    )
                }.value
                try MacUpdateInstaller.replaceApp(at: bundleURL, with: extractedApp)
                try MacUpdateInstaller.scheduleRelaunch(of: bundleURL)
                NSApp.terminate(nil)
            } catch {
                self?.status = .failed(manifest: manifest, message: error.localizedDescription)
            }
        }
    }

    private func runCheck(manual: Bool) {
        status = .checking
        activeTask = Task { [weak self] in
            guard let self else { return }
            do {
                let manifest = try await MacUpdateInstaller.fetchLatest()
                self.defaults.set(Date(), forKey: Self.lastCheckKey)
                if UpdatePolicy.isNewer(remoteVersionCode: manifest.versionCode, currentVersionCode: self.currentVersionCode) {
                    self.availableUpdate = manifest
                    self.status = .available(manifest)
                    let dismissed = self.defaults.integer(forKey: Self.dismissedCodeKey)
                    if UpdatePolicy.shouldPrompt(remoteVersionCode: manifest.versionCode, dismissedVersionCode: dismissed, manual: manual),
                       manual {
                        self.onOpenWindow?()
                    }
                } else {
                    self.availableUpdate = nil
                    self.status = .idle
                    if manual {
                        self.onAlert?("You're up to date", "DroidDrop \(self.currentVersion) is the latest version.")
                    }
                }
            } catch {
                self.status = self.availableUpdate.map(Status.available) ?? .idle
                if manual {
                    self.onAlert?("Could not check for updates", error.localizedDescription)
                }
            }
        }
    }
}
