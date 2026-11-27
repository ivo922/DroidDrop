import Foundation

public enum MacUpdateError: Error, LocalizedError {
    case httpStatus(Int)
    case insecureRedirect(String)
    case sizeMismatch(expected: Int, actual: Int)
    case hashMismatch
    case extractionFailed(String)
    case missingAppBundle
    case invalidBundleIdentifier(String?)
    case versionMismatch(expected: Int, actual: Int?)
    case codeSignFailed(String)
    case replaceFailed(String)
    case relaunchFailed(String)

    public var errorDescription: String? {
        switch self {
        case .httpStatus(let code):
            return "Update server returned HTTP \(code)."
        case .insecureRedirect(let url):
            return "Refused insecure redirect to \(url)."
        case .sizeMismatch(let expected, let actual):
            return "Downloaded update size (\(actual) bytes) did not match expected (\(expected) bytes)."
        case .hashMismatch:
            return "Downloaded update checksum did not match the signed manifest."
        case .extractionFailed(let detail):
            return "Could not unpack the update archive: \(detail)"
        case .missingAppBundle:
            return "Update archive did not contain DroidDrop.app."
        case .invalidBundleIdentifier(let actual):
            return "Update bundle identifier mismatch (\(actual ?? "missing"))."
        case .versionMismatch(let expected, let actual):
            return "Update bundle version (\(actual.map(String.init) ?? "missing")) did not match manifest (\(expected))."
        case .codeSignFailed(let detail):
            return "Update bundle code signature check failed: \(detail)"
        case .replaceFailed(let detail):
            return "Could not replace the installed app: \(detail)"
        case .relaunchFailed(let detail):
            return "Updated the app, but could not relaunch it automatically: \(detail)"
        }
    }
}

/// Downloads, verifies, unpacks, and atomically replaces the running macOS app bundle.
public enum MacUpdateInstaller {
    private static let requestTimeout: TimeInterval = 20

    public static func fetchLatest(
        manifestURL: URL = UpdateConfig.manifestURL,
        signatureURL: URL = UpdateConfig.signatureURL,
        publicKeyBase64: String = UpdateConfig.publicKeyBase64,
        allowedDownloadPrefix: String = UpdateConfig.allowedDownloadPrefix
    ) async throws -> UpdateManifest {
        let session = makeSession(delegate: HTTPSRedirectDelegate())
        defer { session.finishTasksAndInvalidate() }
        async let manifestData = fetchBytes(from: manifestURL, session: session)
        async let sigData = fetchBytes(from: signatureURL, session: session)
        let (rawManifest, rawSig) = try await (manifestData, sigData)
        guard let sigText = String(data: rawSig, encoding: .utf8) else {
            throw UpdateVerificationError.invalidSignatureFormat
        }
        return try UpdateVerifier.verifyAndDecode(
            manifestData: rawManifest,
            signatureBase64: sigText,
            publicKeyBase64: publicKeyBase64,
            allowedDownloadPrefix: allowedDownloadPrefix
        )
    }

    /// Downloads `asset` into `destination`, verifies exact byte size and SHA-256, and reports progress (`0...1`).
    public static func downloadAndVerify(
        asset: UpdateAsset,
        to destination: URL,
        onProgress: @escaping @Sendable (Double) -> Void
    ) async throws {
        guard let url = URL(string: asset.url), url.scheme == "https" else {
            throw UpdateVerificationError.invalidAssetURL(asset.url)
        }
        let tempFile = try await downloadFile(from: url, expectedSize: asset.size, onProgress: onProgress)
        defer { try? FileManager.default.removeItem(at: tempFile) }

        try verifyDownloadedFile(tempFile, expectedSize: asset.size, expectedSHA256: asset.sha256)
        let fileManager = FileManager.default
        try fileManager.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
        if fileManager.fileExists(atPath: destination.path) {
            try fileManager.removeItem(at: destination)
        }
        try fileManager.moveItem(at: tempFile, to: destination)
    }

    public static func verifyDownloadedFile(_ fileURL: URL, expectedSize: Int, expectedSHA256: String) throws {
        let digest = try FileDigest.sha256(of: fileURL)
        guard digest.size == expectedSize else {
            throw MacUpdateError.sizeMismatch(expected: expectedSize, actual: digest.size)
        }
        guard digest.hex.caseInsensitiveCompare(expectedSHA256) == .orderedSame else {
            throw MacUpdateError.hashMismatch
        }
    }

    /// Unpacks `zipURL` with `ditto` and validates the extracted `DroidDrop.app` bundle identity, version, and signature.
    public static func extractAndValidate(
        zipURL: URL,
        into directory: URL,
        expectedBundleID: String,
        expectedVersionCode: Int,
        verifyCodeSign: Bool = true
    ) throws -> URL {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let dittoResult = try runProcess("/usr/bin/ditto", arguments: ["-x", "-k", zipURL.path, directory.path])
        guard dittoResult.status == 0 else {
            throw MacUpdateError.extractionFailed(dittoResult.stderr.isEmpty ? "exit \(dittoResult.status)" : dittoResult.stderr)
        }

        let appURL = directory.appending(path: "DroidDrop.app")
        guard FileManager.default.fileExists(atPath: appURL.path),
              let bundle = Bundle(url: appURL) else {
            throw MacUpdateError.missingAppBundle
        }
        guard bundle.bundleIdentifier == expectedBundleID else {
            throw MacUpdateError.invalidBundleIdentifier(bundle.bundleIdentifier)
        }
        let actualVersion = (bundle.object(forInfoDictionaryKey: "CFBundleVersion") as? String).flatMap(Int.init)
        guard actualVersion == expectedVersionCode else {
            throw MacUpdateError.versionMismatch(expected: expectedVersionCode, actual: actualVersion)
        }

        if verifyCodeSign {
            let signResult = try runProcess("/usr/bin/codesign", arguments: ["--verify", "--deep", "--strict", appURL.path])
            guard signResult.status == 0 else {
                throw MacUpdateError.codeSignFailed(signResult.stderr.isEmpty ? "exit \(signResult.status)" : signResult.stderr)
            }
        }
        return appURL
    }

    /// Atomically swaps the installed `.app` bundle on APFS/HFS+.
    public static func replaceApp(at currentBundleURL: URL, with newAppURL: URL) throws {
        do {
            _ = try FileManager.default.replaceItemAt(currentBundleURL, withItemAt: newAppURL)
        } catch {
            throw MacUpdateError.replaceFailed(error.localizedDescription)
        }
    }

    /// Spawns a detached shell helper that waits for `pid` to exit and then opens the updated app bundle.
    public static func scheduleRelaunch(
        of appURL: URL,
        afterExiting pid: Int32 = ProcessInfo.processInfo.processIdentifier
    ) throws {
        let process = Process()
        process.executableURL = URL(filePath: "/bin/sh")
        process.arguments = [
            "-c",
            #"pid="$1"; app="$2"; while kill -0 "$pid" 2>/dev/null; do sleep 0.1; done; open -n "$app""#,
            "_",
            String(pid),
            appURL.path,
        ]
        process.standardInput = FileHandle.nullDevice
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        do {
            try process.run()
        } catch {
            throw MacUpdateError.relaunchFailed(error.localizedDescription)
        }
    }

    private static func makeSession(delegate: URLSessionDelegate) -> URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        config.timeoutIntervalForRequest = requestTimeout
        config.timeoutIntervalForResource = 300
        return URLSession(configuration: config, delegate: delegate, delegateQueue: nil)
    }

    private static func fetchBytes(from url: URL, session: URLSession) async throws -> Data {
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalAndRemoteCacheData, timeoutInterval: requestTimeout)
        request.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
        let (data, response) = try await session.data(for: request)
        if let http = response as? HTTPURLResponse, http.statusCode != 200 {
            throw MacUpdateError.httpStatus(http.statusCode)
        }
        return data
    }

    private static func downloadFile(
        from url: URL,
        expectedSize: Int,
        onProgress: @escaping @Sendable (Double) -> Void
    ) async throws -> URL {
        try await withCheckedThrowingContinuation { continuation in
            let delegate = DownloadDelegate(expectedSize: expectedSize, onProgress: onProgress, continuation: continuation)
            let session = makeSession(delegate: delegate)
            delegate.session = session
            let request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalAndRemoteCacheData, timeoutInterval: requestTimeout)
            session.downloadTask(with: request).resume()
        }
    }

    private static func runProcess(_ path: String, arguments: [String]) throws -> (status: Int32, stderr: String) {
        let process = Process()
        process.executableURL = URL(filePath: path)
        process.arguments = arguments
        let stderrPipe = Pipe()
        process.standardOutput = FileHandle.nullDevice
        process.standardError = stderrPipe
        try process.run()
        let errData = stderrPipe.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        let stderr = String(data: errData, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return (process.terminationStatus, stderr)
    }
}

private class HTTPSRedirectDelegate: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        guard request.url?.scheme == "https" else {
            completionHandler(nil)
            return
        }
        completionHandler(request)
    }
}

private final class DownloadDelegate: HTTPSRedirectDelegate, URLSessionDownloadDelegate, @unchecked Sendable {
    private let expectedSize: Int
    private let onProgress: @Sendable (Double) -> Void
    private var continuation: CheckedContinuation<URL, Error>?
    private let lock = NSLock()
    var session: URLSession?

    init(
        expectedSize: Int,
        onProgress: @escaping @Sendable (Double) -> Void,
        continuation: CheckedContinuation<URL, Error>
    ) {
        self.expectedSize = expectedSize
        self.onProgress = onProgress
        self.continuation = continuation
    }

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didWriteData bytesWritten: Int64,
        totalBytesWritten: Int64,
        totalBytesExpectedToWrite: Int64
    ) {
        let total = totalBytesExpectedToWrite > 0 ? totalBytesExpectedToWrite : Int64(expectedSize)
        guard total > 0 else { return }
        onProgress(min(max(Double(totalBytesWritten) / Double(total), 0), 1))
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        if let http = downloadTask.response as? HTTPURLResponse, http.statusCode != 200 {
            finish(.failure(MacUpdateError.httpStatus(http.statusCode)))
            return
        }
        let staged = FileManager.default.temporaryDirectory
            .appending(path: "DroidDrop-update-\(UUID().uuidString).zip")
        do {
            try FileManager.default.moveItem(at: location, to: staged)
            onProgress(1)
            finish(.success(staged))
        } catch {
            finish(.failure(error))
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let error else { return }
        finish(.failure(error))
    }

    private func finish(_ result: Result<URL, Error>) {
        lock.lock()
        let cont = continuation
        continuation = nil
        let activeSession = session
        session = nil
        lock.unlock()
        activeSession?.finishTasksAndInvalidate()
        cont?.resume(with: result)
    }
}
