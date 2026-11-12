import AppKit
import DroidDropKit
import UserNotifications

/// Completion notifications for file transfers. A received file offers "Show in Finder".
@MainActor
final class TransferNotifier: NSObject, UNUserNotificationCenterDelegate {
    private static let receivedCategory = "received"
    private static let showAction = "show"
    private static let pathKey = "path"

    /// Registers the category and asks for permission. Denial is fine: transfers still work, silently.
    func requestAuthorization() {
        let center = UNUserNotificationCenter.current()
        let show = UNNotificationAction(identifier: Self.showAction, title: "Show in Finder")
        center.setNotificationCategories([
            UNNotificationCategory(identifier: Self.receivedCategory, actions: [show], intentIdentifiers: [])
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

    // Banners show even while DroidDrop is frontmost.
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .sound]
    }

    // Both the "Show in Finder" action and a click on the banner reveal the file.
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse
    ) async {
        guard response.actionIdentifier == Self.showAction || response.actionIdentifier == UNNotificationDefaultActionIdentifier,
              let path = response.notification.request.content.userInfo[Self.pathKey] as? String else { return }
        await MainActor.run {
            NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: path)])
        }
    }
}
