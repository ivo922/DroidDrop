import Foundation

/// Receiver-side file name handling (SPEC §9.1).
public enum FileName {
    public static let maxBytes = 200
    public static let maxExtensionBytes = 16

    /// SPEC §9.1 rules 1–4.
    public static func sanitize(_ name: String) -> String {
        let replaced = String(String.UnicodeScalarView(name.unicodeScalars.map { scalar in
            switch scalar.value {
            case 0x00...0x1F, 0x7F, 0x2F, 0x5C, 0x3A: return "_"  // control, `/`, `\`, `:`
            default: return scalar
            }
        }))
        let trimmed = replaced.trimmingCharacters(in: .whitespacesAndNewlines)
        let stripped = String(String.UnicodeScalarView(trimmed.unicodeScalars.drop { $0 == "." }))
        if stripped.isEmpty { return "file" }
        if stripped.utf8.count <= maxBytes { return stripped }

        let (stem, ext) = split(stripped)
        if !ext.isEmpty && ext.utf8.count <= maxExtensionBytes {
            return cut(stem, toBytes: maxBytes - ext.utf8.count) + ext
        }
        return cut(stripped, toBytes: maxBytes)
    }

    /// SPEC §9.1 rule 5: `name` if free, else `stem (n)ext` with the smallest free n ≥ 1.
    public static func unique(_ name: String, exists: (String) -> Bool) -> String {
        if !exists(name) { return name }
        let (stem, ext) = split(name)
        var n = 1
        while exists("\(stem) (\(n))\(ext)") { n += 1 }
        return "\(stem) (\(n))\(ext)"
    }

    /// Splits at the last `.` (kept with the extension) unless that `.` is the first character.
    private static func split(_ name: String) -> (stem: String, ext: String) {
        let scalars = name.unicodeScalars
        guard let dot = scalars.lastIndex(of: "."), dot != scalars.startIndex else { return (name, "") }
        return (String(scalars[..<dot]), String(scalars[dot...]))
    }

    /// Longest prefix of whole Unicode scalars that fits in `limit` UTF-8 bytes.
    private static func cut(_ s: String, toBytes limit: Int) -> String {
        var bytes = 0
        var result = String.UnicodeScalarView()
        for scalar in s.unicodeScalars {
            bytes += UTF8.width(scalar)
            if bytes > limit { break }
            result.append(scalar)
        }
        return String(result)
    }
}
