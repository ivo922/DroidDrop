import AppKit

/// What the menu says about macOS's programmatic pasteboard access setting (macOS 15.4+).
public enum ClipboardAccess {
    /// Nil while access is allowed or the system has not asked yet.
    public static func menuMessage(for behavior: NSPasteboard.AccessBehavior) -> String? {
        switch behavior {
        case .ask: "Clipboard: set DroidDrop to Always Allow in System Settings › Privacy & Security"
        case .alwaysDeny: "Clipboard access denied: allow DroidDrop in System Settings › Privacy & Security"
        default: nil
        }
    }
}
