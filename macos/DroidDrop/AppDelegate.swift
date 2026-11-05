import AppIntents
import AppKit
// AppIntents is imported so metadata extraction sees the framework and stays quiet.

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    private var model: AppModel?
    private var statusMenu: StatusMenuController?

    func applicationDidFinishLaunching(_ notification: Notification) {
        do {
            let model = try AppModel()
            self.model = model
            statusMenu = StatusMenuController(model: model)
        } catch {
            let alert = NSAlert()
            alert.messageText = "DroidDrop could not start"
            alert.informativeText = String(describing: error)
            alert.runModal()
            NSApp.terminate(nil)
        }
    }
}
