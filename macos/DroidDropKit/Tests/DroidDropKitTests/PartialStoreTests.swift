import Foundation
import Testing
@testable import DroidDropKit

@Suite struct PartialStoreTests {
    let sha = String(repeating: "ab", count: 32)

    private func makeStore() throws -> PartialStore {
        let store = PartialStore(directory: temporaryDirectory())
        try FileManager.default.createDirectory(at: store.directory, withIntermediateDirectories: true)
        return store
    }

    @Test func resumeOffsetIsPartialLength() throws {
        let store = PartialStore(directory: temporaryDirectory())
        #expect(store.resumeOffset(sha256: sha, size: 10) == 0)
        try FileManager.default.createDirectory(at: store.directory, withIntermediateDirectories: true)
        try Data(count: 4).write(to: store.url(sha256: sha, size: 10))
        #expect(store.url(sha256: sha, size: 10).lastPathComponent == "\(sha)-10.part")
        #expect(store.resumeOffset(sha256: sha, size: 10) == 4)
    }

    @Test func partialLongerThanSizeIsDeleted() throws {
        let store = try makeStore()
        try Data(count: 12).write(to: store.url(sha256: sha, size: 10))
        #expect(store.resumeOffset(sha256: sha, size: 10) == 0)
        #expect(!FileManager.default.fileExists(atPath: store.url(sha256: sha, size: 10).path))
    }

    @Test func pruneDeletesOnlyOldPartials() throws {
        let store = try makeStore()
        let now = Date()
        let old = store.url(sha256: sha, size: 1)
        let recent = store.url(sha256: sha, size: 2)
        try Data(count: 1).write(to: old)
        try Data(count: 2).write(to: recent)
        try FileManager.default.setAttributes([.modificationDate: now.addingTimeInterval(-8 * 86_400)], ofItemAtPath: old.path)
        try FileManager.default.setAttributes([.modificationDate: now.addingTimeInterval(-86_400)], ofItemAtPath: recent.path)
        store.prune(now: now)
        #expect(!FileManager.default.fileExists(atPath: old.path))
        #expect(FileManager.default.fileExists(atPath: recent.path))
    }
}
