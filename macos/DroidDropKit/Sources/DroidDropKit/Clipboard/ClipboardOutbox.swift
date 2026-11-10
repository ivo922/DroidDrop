import AppKit
import CryptoKit

/// The parts of a pasteboard the outbox decides on.
public struct PasteboardContents: Equatable, Sendable {
    public var types: [String]
    public var text: String?

    public init(types: [String], text: String?) {
        self.types = types
        self.text = text
    }

    public static func read(from pasteboard: NSPasteboard) -> PasteboardContents {
        PasteboardContents(types: pasteboard.types?.map(\.rawValue) ?? [], text: pasteboard.string(forType: .string))
    }
}

/// Decides which local pasteboard changes become `clip` messages (SPEC §4.2, §8). No I/O.
///
/// The first poll only records the baseline, so the pasteboard at launch is never sent, and
/// nothing is queued: a change is offered once, when it is first seen.
public struct ClipboardOutbox {
    public static let maxTextBytes = 262_144
    /// Password managers (nspasteboard.org markers) and Finder file copies, whose text is only the file name.
    public static let skippedTypes: Set<String> = [
        "org.nspasteboard.ConcealedType",
        "org.nspasteboard.TransientType",
        "public.file-url",
    ]

    private let makeID: () -> String
    private var lastChangeCount: Int?
    private var lastRemoteHash: Data?

    public init(makeID: @escaping () -> String = { UUID().uuidString.lowercased() }) {
        self.makeID = makeID
    }

    /// The clip to send for this poll, or nil. `contents` is called only when `changeCount` changed.
    public mutating func poll(changeCount: Int, contents: () -> PasteboardContents) -> Message? {
        guard let last = lastChangeCount else {
            lastChangeCount = changeCount
            return nil
        }
        guard changeCount != last else { return nil }
        lastChangeCount = changeCount

        let current = contents()
        guard Self.skippedTypes.isDisjoint(with: current.types),
              let text = current.text, !text.isEmpty,
              text.utf8.count <= Self.maxTextBytes,
              Self.hash(text) != lastRemoteHash else { return nil }
        let clip = Message.clip(id: makeID(), mime: "text/plain", text: text)
        // JSON escaping can more than double the text (U+0001 becomes six bytes), so check the frame too.
        guard let encoded = try? MessageCodec.encode(clip), encoded.count <= FrameLimits.maxControlPayload else { return nil }
        return clip
    }

    /// Records the text of a clip applied from the phone so it is not sent back.
    public mutating func noteRemote(text: String) {
        lastRemoteHash = Self.hash(text)
    }

    private static func hash(_ text: String) -> Data {
        Data(SHA256.hash(data: Data(text.utf8)))
    }
}
