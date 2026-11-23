import AppKit
import DroidDropKit
import UserNotifications

/// Completion notifications for file transfers. A received file or batch offers "Show in Finder"; a sent batch
/// with failures offers "Retry".
@MainActor
final class TransferNotifier: NSObject, UNUserNotificationCenterDelegate {
    private nonisolated static let receivedCategory = "received"
    private nonisolated static let showAction = "show"
    private nonisolated static let pathKey = "path"
    private nonisolated static let receivedBatchCategory = "receivedBatch"
    private nonisolated static let sentBatchCategory = "sentBatch"
    private nonisolated static let retryAction = "retry"
    private nonisolated static let pathsKey = "paths"
    private nonisolated static let fingerprintKey = "fingerprint"
    private nonisolated static let sentSummaryID = "sent-summary"
    private nonisolated static let receivedSummaryID = "received-summary"

    /// Set by `AppModel`: Retry resends the failed files to the stored fingerprint.
    var onRetry: (([URL], Data) -> Void)?
    /// Set by `AppModel` (`receivedFilesFolder`): where Show in Finder falls back to.
    var receivedFolder: URL?

    /// Registers the category and asks for permission. Denial is fine: transfers still work, silently.
    func requestAuthorization() {
        let center = UNUserNotificationCenter.current()
        let show = UNNotificationAction(identifier: Self.showAction, title: "Show in Finder")
        let retry = UNNotificationAction(identifier: Self.retryAction, title: "Retry")
        center.setNotificationCategories([
            UNNotificationCategory(identifier: Self.receivedCategory, actions: [show], intentIdentifiers: []),
            UNNotificationCategory(identifier: Self.receivedBatchCategory, actions: [show], intentIdentifiers: []),
            UNNotificationCategory(identifier: Self.sentBatchCategory, actions: [retry], intentIdentifiers: [])
        ])
        center.requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    /// A success replaces the previous success in its direction (R12); each failure stays on its own.
    func post(_ outcome: TransferOutcome, peerName: String) {
        let text = outcome.notificationText(peerName: peerName)
        let content = UNMutableNotificationContent()
        content.title = text.title
        content.body = text.body
        content.sound = .default
        let identifier: String
        switch outcome {
        case .received(_, let url):
            identifier = "received"
            content.categoryIdentifier = Self.receivedCategory
            content.userInfo = [Self.pathKey: url.path]
        case .sent:
            identifier = "sent"
        case .failed:
            identifier = UUID().uuidString
        }
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: identifier, content: content, trigger: nil))
    }

    /// A batch summary replaces the previous one in its direction's slot (R6). Silent when `summary.silent` (R8).
    func postSummary(_ summary: BatchSummary, peerName: String, fingerprint: Data) {
        let text = summary.notificationText(peerName: peerName)
        let content = UNMutableNotificationContent()
        content.title = text.title
        content.body = text.body
        content.sound = summary.silent ? nil : .default
        let identifier: String
        switch summary.direction {
        case .incoming:
            identifier = Self.receivedSummaryID
            content.categoryIdentifier = Self.receivedBatchCategory
            content.userInfo = [Self.pathsKey: summary.files.map(\.path)]
        case .outgoing:
            identifier = Self.sentSummaryID
            if !summary.failures.isEmpty, !summary.cancelled, !summary.files.isEmpty {
                content.categoryIdentifier = Self.sentBatchCategory
                content.userInfo = [
                    Self.pathsKey: summary.files.map(\.path),
                    Self.fingerprintKey: fingerprint.base64EncodedString()
                ]
            }
        }
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: identifier, content: content, trigger: nil))
    }

    /// Selects the files that still exist; with none left, opens the received-files folder.
    private func showInFinder(_ paths: [String]) {
        let urls = paths.filter { FileManager.default.fileExists(atPath: $0) }.map { URL(fileURLWithPath: $0) }
        if !urls.isEmpty {
            NSWorkspace.shared.activateFileViewerSelecting(urls)
            return
        }
        guard let folder = receivedFolder else { return }
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        NSWorkspace.shared.open(folder)
    }

    private func retry(_ paths: [String], fingerprint: Data) {
        onRetry?(paths.map { URL(fileURLWithPath: $0) }, fingerprint)
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [Self.sentSummaryID])
    }

    // Banners show even while DroidDrop is frontmost.
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .sound]
    }

    // Both the "Show in Finder" action and a click on the banner reveal the file(s). A sent summary's banner click
    // does nothing; only its Retry action acts.
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse
    ) async {
        let action = response.actionIdentifier
        let info = response.notification.request.content.userInfo
        let category = response.notification.request.content.categoryIdentifier
        let reveal = action == Self.showAction || action == UNNotificationDefaultActionIdentifier
        if category == Self.receivedBatchCategory, reveal {
            let paths = info[Self.pathsKey] as? [String] ?? []
            await MainActor.run { showInFinder(paths) }
        } else if category == Self.sentBatchCategory, action == Self.retryAction {
            let paths = info[Self.pathsKey] as? [String] ?? []
            guard let encoded = info[Self.fingerprintKey] as? String, let fingerprint = Data(base64Encoded: encoded),
                  !paths.isEmpty else { return }
            await MainActor.run { retry(paths, fingerprint: fingerprint) }
        } else if reveal, let path = info[Self.pathKey] as? String {
            await MainActor.run {
                NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: path)])
            }
        }
    }
}
