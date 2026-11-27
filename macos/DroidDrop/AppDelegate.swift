import AppIntents
import AppKit
import DroidDropKit
import ServiceManagement
import UserNotifications
// AppIntents is imported so metadata extraction sees the framework and stays quiet.

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    private var model: AppModel?
    private var updater: UpdateController?
    private var statusMenu: StatusMenuController?

    func applicationDidFinishLaunching(_ notification: Notification) {
        do {
            // The delegate must be in place before any transfer can post a notification.
            let notifier = TransferNotifier()
            UNUserNotificationCenter.current().delegate = notifier
            notifier.requestAuthorization()
            let model = try AppModel(notifier: notifier)
            self.model = model
            let updater = UpdateController()
            self.updater = updater
            let statusMenu = StatusMenuController(model: model, updater: updater)
            self.statusMenu = statusMenu
            notifier.onOpenReceivedFolder = { [weak statusMenu] in statusMenu?.openReceivedFiles() }
            applyLoginItemDefault()
            updater.startAutomaticChecks()
        } catch {
            let alert = NSAlert()
            alert.messageText = "DroidDrop could not start"
            alert.informativeText = String(describing: error)
            alert.runModal()
            NSApp.terminate(nil)
        }
    }

    private func applyLoginItemDefault() {
        let defaults = UserDefaults.standard
        let folders = FileManager.default.urls(for: .applicationDirectory, in: [.localDomainMask, .userDomainMask])
        guard LoginItemDefault.shouldRegister(
            alreadyApplied: defaults.bool(forKey: LoginItemDefault.appliedKey),
            bundleURL: Bundle.main.bundleURL, applicationsFolders: folders) else { return }
        // Marked first so a failure is not retried on every launch; the menu toggle can still turn it on.
        defaults.set(true, forKey: LoginItemDefault.appliedKey)
        try? SMAppService.mainApp.register()
    }
}
