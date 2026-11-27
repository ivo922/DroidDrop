import Foundation

/// Lists recently saved files in the macOS received-files folder for the status menu.
public enum ReceivedFiles {
    public static let recentLimit = 10

    struct Entry: Equatable, Sendable {
        let url: URL
        let addedAt: Date
        let modifiedAt: Date
    }

    /// Up to `limit` regular, non-hidden files in `directory`, newest first. Empty when missing or unreadable.
    public static func recent(in directory: URL, limit: Int = recentLimit) -> [URL] {
        guard limit > 0 else { return [] }
        let keys: [URLResourceKey] = [
            .isRegularFileKey,
            .addedToDirectoryDateKey,
            .contentModificationDateKey,
            .creationDateKey,
        ]
        guard let urls = try? FileManager.default.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: keys,
            options: [.skipsHiddenFiles]
        ) else { return [] }
        let keySet = Set(keys)
        let entries: [Entry] = urls.compactMap { url in
            guard let values = try? url.resourceValues(forKeys: keySet),
                  values.isRegularFile == true else { return nil }
            let modified = values.contentModificationDate ?? values.creationDate ?? .distantPast
            let added = values.addedToDirectoryDate ?? modified
            return Entry(url: url, addedAt: added, modifiedAt: modified)
        }
        return selectRecent(entries, limit: limit)
    }

    /// Middle-truncates long file names so one wide name does not stretch the submenu, keeping the extension visible.
    public static func menuTitle(for name: String, maxCharacters: Int = 50) -> String {
        guard maxCharacters > 0 else { return "" }
        guard name.count > maxCharacters else { return name }
        guard maxCharacters > 1 else { return "…" }
        let tailCount = (maxCharacters - 1) * 2 / 5
        let headCount = maxCharacters - 1 - tailCount
        return "\(name.prefix(headCount))…\(name.suffix(tailCount))"
    }

    static func selectRecent(_ entries: [Entry], limit: Int = recentLimit) -> [URL] {
        guard limit > 0 else { return [] }
        return entries
            .sorted { lhs, rhs in
                if lhs.addedAt != rhs.addedAt { return lhs.addedAt > rhs.addedAt }
                if lhs.modifiedAt != rhs.modifiedAt { return lhs.modifiedAt > rhs.modifiedAt }
                let byName = lhs.url.lastPathComponent.localizedStandardCompare(rhs.url.lastPathComponent)
                if byName != .orderedSame { return byName == .orderedAscending }
                return lhs.url.path < rhs.url.path
            }
            .prefix(limit)
            .map(\.url)
    }
}
