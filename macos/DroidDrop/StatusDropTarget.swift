import AppKit

/// Accepts files dragged onto the status item, for sending to the connected phone.
@MainActor
final class StatusDropTarget: NSObject, NSWindowDelegate {
    private let model: AppModel

    init(model: AppModel) {
        self.model = model
    }

    /// Registers the status item's window as a drag destination, with this object as its delegate.
    func install(on window: NSWindow?) {
        window?.registerForDraggedTypes([.fileURL])
        window?.delegate = self
    }

    // NSWindowDelegate does not declare the dragging methods; the window forwards them to its delegate.
    @objc func draggingEntered(_ sender: any NSDraggingInfo) -> NSDragOperation {
        fileURLs(in: sender).isEmpty || !model.canSendFiles ? [] : .copy
    }

    @objc func performDragOperation(_ sender: any NSDraggingInfo) -> Bool {
        let urls = fileURLs(in: sender)
        guard model.canSendFiles, !urls.isEmpty else { return false }
        model.sendFiles(urls)
        return true
    }

    private func fileURLs(in sender: any NSDraggingInfo) -> [URL] {
        let objects = sender.draggingPasteboard.readObjects(
            forClasses: [NSURL.self],
            options: [.urlReadingFileURLsOnly: true]
        )
        return objects as? [URL] ?? []
    }
}
