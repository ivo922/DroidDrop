import AppKit
import Combine
import ServiceManagement

@MainActor
final class StatusMenuController: NSObject, NSMenuDelegate {
    private let model: AppModel
    private let statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
    private let pairingWindow: PairingWindowController
    private var subscription: AnyCancellable?

    init(model: AppModel) {
        self.model = model
        pairingWindow = PairingWindowController(model: model)
        super.init()
        let menu = NSMenu()
        menu.delegate = self
        statusItem.menu = menu
        subscription = Publishers.CombineLatest3(model.$connectedPeers, model.$isListening, model.$serverError)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _, _, _ in
                self?.updateIcon()
            }
        updateIcon()
    }

    func menuNeedsUpdate(_ menu: NSMenu) {
        menu.removeAllItems()
        menu.addItem(disabled(model.statusLine))
        if let error = model.serverError { menu.addItem(disabled("Error: \(error)")) }
        if let error = model.unpairError { menu.addItem(disabled("Could not unpair: \(error)")) }
        menu.addItem(.separator())

        menu.addItem(item("Pair New Phone…", #selector(pair)))
        if !model.pairedPeers.isEmpty {
            let devices = NSMenuItem(title: "Paired Devices", action: nil, keyEquivalent: "")
            let submenu = NSMenu()
            for peer in model.pairedPeers {
                let unpair = item("Unpair \(peer.name)", #selector(unpair(_:)))
                unpair.representedObject = peer.fingerprint as NSData
                submenu.addItem(unpair)
            }
            devices.submenu = submenu
            menu.addItem(devices)
        }
        menu.addItem(.separator())

        let login = item("Launch at Login", #selector(toggleLaunchAtLogin))
        login.state = SMAppService.mainApp.status == .enabled ? .on : .off
        menu.addItem(login)
        menu.addItem(disabled("Mac code: \(model.macCode)"))
        menu.addItem(.separator())
        menu.addItem(NSMenuItem(title: "Quit DroidDrop", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q"))
    }

    @objc private func pair() {
        pairingWindow.show()
    }

    @objc private func unpair(_ sender: NSMenuItem) {
        guard let fingerprint = sender.representedObject as? Data else { return }
        guard let message = model.unpair(fingerprint) else { return }
        let alert = NSAlert()
        alert.messageText = "Could not unpair"
        alert.informativeText = message
        alert.runModal()
    }

    @objc private func toggleLaunchAtLogin() {
        do {
            if SMAppService.mainApp.status == .enabled {
                try SMAppService.mainApp.unregister()
            } else {
                try SMAppService.mainApp.register()
            }
        } catch {
            let alert = NSAlert(error: error)
            alert.runModal()
        }
    }

    private func updateIcon() {
        let symbol = model.isListening
            ? (model.connectedPeers.isEmpty ? "arrow.left.arrow.right.circle" : "arrow.left.arrow.right.circle.fill")
            : (model.serverError == nil ? "arrow.left.arrow.right.circle" : "exclamationmark.circle")
        let description = model.serverError.map { "\(model.statusLine): \($0)" } ?? model.statusLine
        let image = NSImage(systemSymbolName: symbol, accessibilityDescription: description)
        image?.isTemplate = true
        statusItem.button?.image = image
        statusItem.button?.toolTip = description
    }

    private func item(_ title: String, _ action: Selector) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: action, keyEquivalent: "")
        item.target = self
        return item
    }

    private func disabled(_ title: String) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: nil, keyEquivalent: "")
        item.isEnabled = false
        return item
    }
}
