import AppIntents
import AppKit
import UserNotifications
// AppIntents is imported so metadata extraction sees the framework and stays quiet.

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    private var model: AppModel?
    private var statusMenu: StatusMenuController?

    func applicationDidFinishLaunching(_ notification: Notification) {
        do {
            // The delegate must be in place before any transfer can post a notification.
            let notifier = TransferNotifier()
            UNUserNotificationCenter.current().delegate = notifier
            notifier.requestAuthorization()
            let model = try AppModel(notifier: notifier)
            self.model = model
            let statusMenu = StatusMenuController(model: model)
            self.statusMenu = statusMenu
            notifier.onOpenReceivedFolder = { [weak statusMenu] in statusMenu?.openReceivedFiles() }
        } catch {
            let alert = NSAlert()
            alert.messageText = "DroidDrop could not start"
            alert.informativeText = String(describing: error)
            alert.runModal()
            NSApp.terminate(nil)
        }
    }
}
