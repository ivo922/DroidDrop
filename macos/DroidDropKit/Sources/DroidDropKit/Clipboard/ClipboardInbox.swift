/// Which clips received from the phone to apply (SPEC §4.2). No I/O.
public enum ClipboardInbox {
    /// The text to apply, or nil: another mime, empty, or more than `ClipboardOutbox.maxTextBytes` UTF-8 bytes.
    public static func accept(mime: String, text: String) -> String? {
        guard mime == "text/plain", !text.isEmpty, text.utf8.count <= ClipboardOutbox.maxTextBytes else { return nil }
        return text
    }
}
