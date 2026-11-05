import AppKit
import SwiftUI

struct PairingView: View {
    @ObservedObject var model: AppModel
    let close: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            if let peer = model.justPaired {
                Label("Paired with \(peer.name)", systemImage: "checkmark.circle.fill")
                    .font(.title2)
                Button("Done", action: close).keyboardShortcut(.defaultAction)
            } else if model.noNetwork {
                Text("No network")
                    .font(.headline)
                Button("Try Again") { model.startPairing() }
            } else if let uri = model.pairURI, let expiresAt = model.pairingExpiresAt {
                Text("Scan with DroidDrop on your phone").font(.headline)
                if let image = QRCode.image(for: uri.string) {
                    Image(nsImage: image).interpolation(.none).resizable().frame(width: 260, height: 260)
                }
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    let remaining = max(0, Int(expiresAt.timeIntervalSince(context.date)))
                    Text(remaining > 0 ? "Expires in \(remaining) s" : "Expired — generate a new code")
                        .monospacedDigit()
                        .foregroundStyle(.secondary)
                }
                Text("Mac code: \(model.macCode)").font(.caption.monospaced()).foregroundStyle(.secondary)
                HStack {
                    Button("Copy Pairing Link") {
                        NSPasteboard.general.clearContents()
                        NSPasteboard.general.setString(uri.string, forType: .string)
                    }
                    Button("New Code") { model.startPairing() }
                }
            } else if let error = model.serverError {
                Text(model.isListening ? "Pairing unavailable" : "Not listening")
                    .font(.headline)
                Text(error)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .textSelection(.enabled)
            } else {
                Text("Starting…")
                    .foregroundStyle(.secondary)
            }
        }
        .padding(24)
        .frame(width: 340, height: 480, alignment: .center)
    }
}
