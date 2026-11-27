import Foundation

/// Release feed endpoints and Ed25519 verification key for in-app macOS updates.
public enum UpdateConfig {
    public static let manifestURL = URL(string: "https://github.com/ivo922/DroidDrop/releases/latest/download/update.json")!
    public static let signatureURL = URL(string: "https://github.com/ivo922/DroidDrop/releases/latest/download/update.json.sig")!
    public static let allowedDownloadPrefix = "https://github.com/ivo922/DroidDrop/releases/download/"
    public static let releasesPageURL = URL(string: "https://github.com/ivo922/DroidDrop/releases/latest")!
    /// Base64 of the 32-byte raw Ed25519 public key matching `DROIDDROP_UPDATE_KEY_BASE64` in CI.
    public static let publicKeyBase64 = "BEtFvQOnEd+q/MbtBU8cm5bs27MOALwEy8UwsfM7z+4="
    public static let checkInterval: TimeInterval = 24 * 60 * 60
}
