import DroidDropKit
import SwiftUI

struct UpdateView: View {
    @ObservedObject var updater: UpdateController
    let close: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            if let manifest = activeManifest {
                header(for: manifest)
                if !manifest.notes.isEmpty {
                    ScrollView {
                        Text(manifest.notes)
                            .font(.callout)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .textSelection(.enabled)
                    }
                    .frame(maxHeight: 160)
                    .padding(10)
                    .background(Color(nsColor: .textBackgroundColor))
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                }
                statusArea(for: manifest)
                actions(for: manifest)
            } else {
                Text("DroidDrop \(updater.currentVersion) is up to date.")
                    .font(.headline)
                HStack {
                    Spacer()
                    Button("Done", action: close).keyboardShortcut(.defaultAction)
                }
            }
        }
        .padding(20)
        .frame(width: 380)
    }

    private var activeManifest: UpdateManifest? {
        switch updater.status {
        case .available(let m), .downloading(let m, _), .installing(let m):
            return m
        case .failed(let m, _):
            return m ?? updater.availableUpdate
        case .idle, .checking:
            return updater.availableUpdate
        }
    }

    @ViewBuilder
    private func header(for manifest: UpdateManifest) -> some View {
        HStack(spacing: 12) {
            Image(nsImage: NSApp.applicationIconImage)
                .resizable()
                .frame(width: 48, height: 48)
            VStack(alignment: .leading, spacing: 2) {
                Text("DroidDrop \(manifest.version) is available")
                    .font(.headline)
                Text("You have \(updater.currentVersion)")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
    }

    @ViewBuilder
    private func statusArea(for manifest: UpdateManifest) -> some View {
        switch updater.status {
        case .downloading(_, let progress):
            VStack(alignment: .leading, spacing: 6) {
                ProgressView(value: progress)
                Text("Downloading update… \(Int(progress * 100))%")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
            }
        case .installing:
            HStack(spacing: 8) {
                ProgressView().controlSize(.small)
                Text("Verifying and installing…")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        case .failed(_, let message):
            Text(message)
                .font(.caption)
                .foregroundStyle(.red)
                .textSelection(.enabled)
        case .available, .idle, .checking:
            if let reason = updater.cannotInstallReason {
                Text(reason)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }

    @ViewBuilder
    private func actions(for manifest: UpdateManifest) -> some View {
        HStack {
            if updater.cannotInstallReason != nil {
                Button("Download from GitHub…") {
                    updater.openReleasesPage()
                    close()
                }
                .keyboardShortcut(.defaultAction)
                Spacer()
                Button("Close", action: close)
            } else {
                Button("Later") {
                    updater.dismiss(manifest)
                    close()
                }
                .disabled(updater.isBusy)
                Spacer()
                if case .failed = updater.status {
                    Button("Open Releases…") {
                        updater.openReleasesPage()
                    }
                }
                Button("Update & Relaunch") {
                    updater.install(manifest)
                }
                .keyboardShortcut(.defaultAction)
                .disabled(updater.isBusy)
            }
        }
    }
}
