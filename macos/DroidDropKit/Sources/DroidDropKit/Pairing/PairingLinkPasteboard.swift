import AppKit

/// Copies the pairing link (which carries the pairing secret) to this Mac only, marked so clipboard managers skip it.
public enum PairingLinkPasteboard {
    private static let markers = [PasteboardMarkers.concealed, PasteboardMarkers.transient]

    /// Returns the pasteboard's change count after the write, for `clear(ifUnchangedSince:on:)`.
    @discardableResult
    public static func copy(_ link: String, to pasteboard: NSPasteboard = .general) -> Int {
        // The markers are only a clipboard-manager convention; Universal Clipboard ignores them, so keep it on this Mac.
        pasteboard.prepareForNewContents(with: .currentHostOnly)
        pasteboard.setString(link, forType: .string)
        for marker in markers { pasteboard.setData(Data(), forType: marker) }
        return pasteboard.changeCount
    }

    /// Clears the link if nothing was copied since. Compares change counts so the contents are never read.
    public static func clear(ifUnchangedSince changeCount: Int, on pasteboard: NSPasteboard = .general) {
        guard pasteboard.changeCount == changeCount else { return }
        pasteboard.clearContents()
    }
}
