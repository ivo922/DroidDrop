import Foundation

/// Where inbound transfers keep their unfinished bytes, so a dropped session can resume (SPEC §9, R1).
public struct PartialStore: Sendable {
    public static let maxAge: TimeInterval = 7 * 24 * 60 * 60
    public static let completionMaxAge: TimeInterval = 10 * 60
    private static let fileExtension = "part"
    private static let completionExtension = "done"

    public let directory: URL

    public init(directory: URL) {
        self.directory = directory
    }

    /// `<directory>/<sha256>-<size>.part` (R1)
    public func url(sha256: String, size: Int) -> URL {
        directory.appending(path: "\(sha256)-\(size).\(Self.fileExtension)")
    }

    /// Length of the retained partial, else 0. Deletes a partial longer than `size` first.
    public func resumeOffset(sha256: String, size: Int) -> Int {
        let url = url(sha256: sha256, size: size)
        guard let length = (try? FileManager.default.attributesOfItem(atPath: url.path))?[.size] as? Int else { return 0 }
        if length > size {
            try? FileManager.default.removeItem(at: url)
            return 0
        }
        return length
    }

    /// `<directory>/<sha256>-<size>.done`: the UTF-8 path a finished transfer was saved to, so a `file_result` lost
    /// with the session does not make the sender's re-offer save the file twice.
    public func completionMarker(sha256: String, size: Int) -> URL {
        directory.appending(path: "\(sha256)-\(size).\(Self.completionExtension)")
    }

    /// Records where a verified file was saved. Best effort: without a marker a re-offer just saves it again.
    public func markCompleted(sha256: String, size: Int, location: URL) {
        try? Data(location.path.utf8).write(to: completionMarker(sha256: sha256, size: size))
    }

    /// The saved file of a transfer completed less than `completionMaxAge` before `now` that still exists, else nil.
    /// A marker that is expired, unreadable or points at a missing file is deleted.
    public func completedLocation(sha256: String, size: Int, now: Date = Date()) -> URL? {
        let marker = completionMarker(sha256: sha256, size: size)
        let fileManager = FileManager.default
        guard fileManager.fileExists(atPath: marker.path) else { return nil }
        if let modified = (try? fileManager.attributesOfItem(atPath: marker.path))?[.modificationDate] as? Date,
           now.timeIntervalSince(modified) < Self.completionMaxAge,
           let data = try? Data(contentsOf: marker), let path = String(data: data, encoding: .utf8), !path.isEmpty,
           fileManager.fileExists(atPath: path) {
            return URL(filePath: path, directoryHint: .notDirectory)
        }
        try? fileManager.removeItem(at: marker)
        return nil
    }

    /// Deletes `.part` files modified more than `maxAge` before `now`, and `.done` markers more than `completionMaxAge`.
    public func prune(now: Date = Date()) {
        let files = (try? FileManager.default.contentsOfDirectory(
            at: directory, includingPropertiesForKeys: [.contentModificationDateKey])) ?? []
        for file in files {
            let maxAge: TimeInterval
            switch file.pathExtension {
            case Self.fileExtension: maxAge = Self.maxAge
            case Self.completionExtension: maxAge = Self.completionMaxAge
            default: continue
            }
            guard let modified = try? file.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate,
                  now.timeIntervalSince(modified) > maxAge else { continue }
            try? FileManager.default.removeItem(at: file)
        }
    }
}
