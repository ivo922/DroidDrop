import Foundation

/// Pure decision logic for macOS update checks, prompting, and in-place installability.
public enum UpdatePolicy {
    public enum Installability: Equatable, Sendable {
        case writable
        case translocated
        case readOnlyVolume
        case notAppBundle
        case notWritable
    }

    public static func isNewer(remoteVersionCode: Int, currentVersionCode: Int) -> Bool {
        remoteVersionCode > currentVersionCode
    }

    public static func shouldAutoCheck(
        lastCheckedAt: Date?,
        now: Date = Date(),
        interval: TimeInterval = UpdateConfig.checkInterval
    ) -> Bool {
        guard let lastCheckedAt else { return true }
        return now.timeIntervalSince(lastCheckedAt) >= interval
    }

    /// Auto-checks stay quiet for a version the user dismissed with "Later"; manual checks always show it.
    public static func shouldPrompt(remoteVersionCode: Int, dismissedVersionCode: Int, manual: Bool) -> Bool {
        manual || remoteVersionCode > dismissedVersionCode
    }

    public static func installability(
        bundleURL: URL,
        isWritable: (URL) -> Bool = { FileManager.default.isWritableFile(atPath: $0.path) }
    ) -> Installability {
        let standardized = bundleURL.standardizedFileURL
        let path = standardized.path
        if path.contains("/AppTranslocation/") { return .translocated }
        if standardized.pathExtension != "app" { return .notAppBundle }
        let parent = standardized.deletingLastPathComponent()
        if path.hasPrefix("/Volumes/"), !isWritable(parent) { return .readOnlyVolume }
        if !isWritable(standardized) || !isWritable(parent) { return .notWritable }
        return .writable
    }

    public static func cannotInstallReason(for installability: Installability) -> String? {
        switch installability {
        case .writable:
            return nil
        case .translocated:
            return "Move DroidDrop to Applications to update automatically."
        case .readOnlyVolume:
            return "DroidDrop is running from a read-only disk image. Move it to Applications to update automatically."
        case .notAppBundle:
            return "Automatic updates are only available when running the packaged DroidDrop app."
        case .notWritable:
            return "DroidDrop does not have permission to replace the app in its current folder."
        }
    }
}
