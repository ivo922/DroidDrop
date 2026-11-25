import Foundation

/// Launch at Login starts on: the first launch from an Applications folder registers the app once. After that the menu
/// toggle is the user's choice and is never overridden. Copies elsewhere (Downloads, dev builds) never register.
public enum LoginItemDefault {
    public static let appliedKey = "loginItemDefaultApplied"

    public static func shouldRegister(alreadyApplied: Bool, bundleURL: URL, applicationsFolders: [URL]) -> Bool {
        guard !alreadyApplied else { return false }
        let bundle = bundleURL.standardizedFileURL.path
        return applicationsFolders.contains { folder in
            bundle.hasPrefix(folder.standardizedFileURL.path + "/")
        }
    }
}
