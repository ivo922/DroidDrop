import AppKit

/// Copies the pairing link (which carries the pairing secret) without it being synced or kept by clipboard managers.
public enum PairingLinkPasteboard {
    private static let markers: [NSPasteboard.PasteboardType] = [
        .init("org.nspasteboard.ConcealedType"),
        .init("org.nspasteboard.TransientType"),
    ]

    /// Returns the pasteboard's change count after the write, for `clear(ifUnchangedSince:on:)`.
    @discardableResult
    public static func copy(_ link: String, to pasteboard: NSPasteboard = .general) -> Int {
        pasteboard.declareTypes([.string] + markers, owner: nil)
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
