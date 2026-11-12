import Foundation

/// Where inbound transfers keep their unfinished bytes, so a dropped session can resume (SPEC §9, R1).
public struct PartialStore: Sendable {
    public static let maxAge: TimeInterval = 7 * 24 * 60 * 60
    private static let fileExtension = "part"

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

    /// Deletes `.part` files modified more than `maxAge` before `now`.
    public func prune(now: Date = Date()) {
        let files = (try? FileManager.default.contentsOfDirectory(
            at: directory, includingPropertiesForKeys: [.contentModificationDateKey])) ?? []
        for file in files where file.pathExtension == Self.fileExtension {
            guard let modified = try? file.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate,
                  now.timeIntervalSince(modified) > Self.maxAge else { continue }
            try? FileManager.default.removeItem(at: file)
        }
    }
}
