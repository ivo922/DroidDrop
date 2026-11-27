import AppKit
import Combine
import DroidDropKit
import ServiceManagement

@MainActor
final class StatusMenuController: NSObject, NSMenuDelegate, NSMenuItemValidation {
    private let model: AppModel
    private let updater: UpdateController
    private let statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
    private let pairingWindow: PairingWindowController
    private let updateWindow: UpdateWindowController
    private let dropTarget: StatusDropTarget
    private var subscription: AnyCancellable?
    private var transferSubscription: AnyCancellable?
    /// A transfer shows at most an incoming and an outgoing line. Fixed slots let an open menu update live.
    private let transferItems = [NSMenuItem(), NSMenuItem()]
    private let cancelSendingItem = NSMenuItem()
    private weak var receivedFilesMenu: NSMenu?

    init(model: AppModel, updater: UpdateController) {
        self.model = model
        self.updater = updater
        pairingWindow = PairingWindowController(model: model)
        updateWindow = UpdateWindowController(updater: updater)
        dropTarget = StatusDropTarget(model: model)
        super.init()
        updater.onOpenWindow = { [weak self] in self?.updateWindow.show() }
        updater.onAlert = { [weak self] title, message in
            let alert = NSAlert()
            alert.messageText = title
            alert.informativeText = message
            self?.present(alert)
        }
        cancelSendingItem.title = "Cancel Sending"
        cancelSendingItem.action = #selector(cancelSending)
        cancelSendingItem.target = self
        dropTarget.install(on: statusItem.button?.window)
        let menu = NSMenu()
        menu.delegate = self
        statusItem.menu = menu
        subscription = Publishers.CombineLatest3(model.$connectedPeers, model.$isListening, model.$serverError)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _, _, _ in
                self?.updateIcon()
            }
        transferSubscription = Publishers.CombineLatest(model.$transferLines, model.$isSending)
            .sink { [weak self] lines, isSending in
                self?.showTransfers(lines, isSending: isSending)
            }
        updateIcon()
    }

    func menuNeedsUpdate(_ menu: NSMenu) {
        if menu === receivedFilesMenu {
            populateReceivedFiles(menu)
            return
        }
        menu.removeAllItems()
        menu.addItem(disabled(model.statusLine))
        if let error = model.serverError { menu.addItem(disabled("Error: \(error)")) }
        if let error = model.unpairError { menu.addItem(disabled("Could not unpair: \(error)")) }
        if let access = ClipboardAccess.menuMessage(for: NSPasteboard.general.accessBehavior) { menu.addItem(disabled(access)) }
        for transferItem in transferItems {
            transferItem.isEnabled = false
            menu.addItem(transferItem)
        }
        showTransfers(model.transferLines, isSending: model.isSending)
        menu.addItem(item("Send Files…", #selector(sendFiles)))
        menu.addItem(cancelSendingItem)
        let received = NSMenuItem(title: "Received Files", action: nil, keyEquivalent: "")
        let receivedSubmenu = NSMenu()
        receivedSubmenu.delegate = self
        received.submenu = receivedSubmenu
        receivedFilesMenu = receivedSubmenu
        menu.addItem(received)
        menu.addItem(.separator())

        menu.addItem(item("Pair New Phone…", #selector(pair)))
        if !model.pairedPeers.isEmpty {
            let devices = NSMenuItem(title: "Paired Devices", action: nil, keyEquivalent: "")
            let submenu = NSMenu()
            for peer in model.pairedPeers {
                let status = model.isConnected(peer) ? "Connected" : "Not connected"
                let device = NSMenuItem(title: "\(peer.name) — \(status)", action: nil, keyEquivalent: "")
                let actions = NSMenu()
                let pairAgain = item("Pair Again…", #selector(pairAgain(_:)))
                pairAgain.representedObject = peer.fingerprint as NSData
                actions.addItem(pairAgain)
                let unpair = item("Unpair", #selector(unpair(_:)))
                unpair.representedObject = peer.fingerprint as NSData
                actions.addItem(unpair)
                device.submenu = actions
                submenu.addItem(device)
            }
            devices.submenu = submenu
            menu.addItem(devices)
        }
        menu.addItem(.separator())

        let login = item("Launch at Login", #selector(toggleLaunchAtLogin))
        login.state = SMAppService.mainApp.status == .enabled ? .on : .off
        menu.addItem(login)
        if let update = updater.availableUpdate {
            menu.addItem(item("Update Available (\(update.version))…", #selector(showUpdateWindow)))
        } else {
            let checkTitle: String
            switch updater.status {
            case .checking: checkTitle = "Checking for Updates…"
            case .downloading(_, let progress): checkTitle = "Downloading Update (\(Int(progress * 100))%)…"
            case .installing: checkTitle = "Installing Update…"
            default: checkTitle = "Check for Updates…"
            }
            let checkItem = item(checkTitle, #selector(checkForUpdates))
            checkItem.isEnabled = !updater.isBusy
            menu.addItem(checkItem)
        }
        menu.addItem(disabled("Mac code: \(model.macCode)"))
        menu.addItem(.separator())
        menu.addItem(NSMenuItem(title: "Quit DroidDrop", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q"))
    }

    /// Sending needs a connected phone (R6).
    func validateMenuItem(_ menuItem: NSMenuItem) -> Bool {
        if menuItem.action == #selector(sendFiles) { return model.canSendFiles }
        if menuItem.action == #selector(checkForUpdates) { return !updater.isBusy }
        return true
    }

    private func showTransfers(_ lines: [String], isSending: Bool) {
        for (index, transferItem) in transferItems.enumerated() {
            transferItem.title = index < lines.count ? lines[index] : ""
            transferItem.isHidden = index >= lines.count
        }
        cancelSendingItem.isHidden = !isSending
    }

    @objc private func sendFiles() {
        NSApp.activate()
        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = true
        guard panel.runModal() == .OK else { return }
        model.sendFiles(panel.urls)
    }

    private func populateReceivedFiles(_ menu: NSMenu) {
        menu.removeAllItems()
        let files = model.recentReceivedFiles()
        if files.isEmpty {
            menu.addItem(disabled("No received files"))
        } else {
            for url in files {
                let name = url.lastPathComponent
                let title = ReceivedFiles.menuTitle(for: name)
                let fileItem = item(title, #selector(openReceivedFile(_:)))
                fileItem.representedObject = url
                if title != name { fileItem.toolTip = name }
                if let icon = NSWorkspace.shared.icon(forFile: url.path).copy() as? NSImage {
                    icon.size = NSSize(width: 16, height: 16)
                    fileItem.image = icon
                }
                menu.addItem(fileItem)
            }
        }
        menu.addItem(.separator())
        menu.addItem(item("Open in Finder…", #selector(openReceivedFiles)))
    }

    /// Also Show in Finder's fallback when none of a batch's files is left.
    @objc func openReceivedFiles() {
        guard let message = model.openReceivedFiles() else { return }
        let alert = NSAlert()
        alert.messageText = "Could not open the received files folder"
        alert.informativeText = message
        present(alert)
    }

    @objc private func openReceivedFile(_ sender: NSMenuItem) {
        guard let url = sender.representedObject as? URL else { return }
        guard let message = model.openReceivedFile(url) else { return }
        let alert = NSAlert()
        alert.messageText = "Could not open file"
        alert.informativeText = message
        present(alert)
    }

    @objc private func cancelSending() {
        model.cancelSending()
    }

    @objc private func pair() {
        pairingWindow.show()
    }

    @objc private func pairAgain(_ sender: NSMenuItem) {
        guard let fingerprint = sender.representedObject as? Data,
              let peer = model.pairedPeers.first(where: { $0.fingerprint == fingerprint }) else { return }
        pairingWindow.show(replacing: peer)
    }

    @objc private func unpair(_ sender: NSMenuItem) {
        guard let fingerprint = sender.representedObject as? Data else { return }
        guard let message = model.unpair(fingerprint) else { return }
        let alert = NSAlert()
        alert.messageText = "Could not unpair"
        alert.informativeText = message
        present(alert)
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
            present(alert)
        }
    }

    @objc private func checkForUpdates() {
        updater.checkManually()
    }

    @objc private func showUpdateWindow() {
        updater.openUpdateWindow()
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

    /// A menu-bar app is never frontmost on its own: without activating, the alert shows but is not key, so Return
    /// does not dismiss it.
    private func present(_ alert: NSAlert) {
        NSApp.activate()
        alert.runModal()
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
