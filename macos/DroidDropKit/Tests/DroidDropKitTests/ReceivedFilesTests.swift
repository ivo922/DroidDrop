import Foundation
import Testing
@testable import DroidDropKit

@Suite struct ReceivedFilesTests {
    private func makeTempDir() throws -> URL {
        let dir = FileManager.default.temporaryDirectory
            .appending(path: "DroidDrop-ReceivedFilesTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    @Test func missingDirectoryReturnsEmpty() {
        let missing = FileManager.default.temporaryDirectory
            .appending(path: "DroidDrop-Missing-\(UUID().uuidString)")
        #expect(ReceivedFiles.recent(in: missing).isEmpty)
    }

    @Test func emptyDirectoryReturnsEmpty() throws {
        let dir = try makeTempDir()
        defer { try? FileManager.default.removeItem(at: dir) }
        #expect(ReceivedFiles.recent(in: dir).isEmpty)
    }

    @Test func skipsHiddenFilesAndSubdirectories() throws {
        let dir = try makeTempDir()
        defer { try? FileManager.default.removeItem(at: dir) }

        try Data("hidden".utf8).write(to: dir.appending(path: ".DS_Store"))
        let sub = dir.appending(path: "subfolder")
        try FileManager.default.createDirectory(at: sub, withIntermediateDirectories: true)
        try Data("nested".utf8).write(to: sub.appending(path: "nested.txt"))
        let visible = dir.appending(path: "note.txt")
        try Data("ok".utf8).write(to: visible)

        let recent = ReceivedFiles.recent(in: dir)
        #expect(recent.map(\.lastPathComponent) == ["note.txt"])
    }

    @Test func returnsNewestFirstCappedAtTen() throws {
        let dir = try makeTempDir()
        defer { try? FileManager.default.removeItem(at: dir) }

        for i in 1...12 {
            let file = dir.appending(path: "file-\(i).txt")
            try Data("item \(i)".utf8).write(to: file)
            // Brief pause so APFS records distinct addedToDirectoryDate timestamps even on fast disks.
            Thread.sleep(forTimeInterval: 0.005)
        }

        let recent = ReceivedFiles.recent(in: dir)
        #expect(recent.count == 10)
        #expect(recent.map(\.lastPathComponent) == (3...12).reversed().map { "file-\($0).txt" })
    }

    @Test func selectRecentPrefersAddedDateThenModifiedDateThenName() {
        let base = Date(timeIntervalSince1970: 1_700_000_000)
        let entries = [
            ReceivedFiles.Entry(url: URL(filePath: "/d/b.txt"), addedAt: base, modifiedAt: base),
            ReceivedFiles.Entry(url: URL(filePath: "/d/a.txt"), addedAt: base, modifiedAt: base),
            ReceivedFiles.Entry(url: URL(filePath: "/d/edited.txt"), addedAt: base, modifiedAt: base.addingTimeInterval(60)),
            ReceivedFiles.Entry(url: URL(filePath: "/d/newest.txt"), addedAt: base.addingTimeInterval(10), modifiedAt: base),
        ]

        let ordered = ReceivedFiles.selectRecent(entries, limit: 10).map(\.lastPathComponent)
        #expect(ordered == ["newest.txt", "edited.txt", "a.txt", "b.txt"])
        #expect(ReceivedFiles.selectRecent(entries, limit: 2).map(\.lastPathComponent) == ["newest.txt", "edited.txt"])
        #expect(ReceivedFiles.selectRecent(entries, limit: 0).isEmpty)
    }

    @Test func menuTitleKeepsShortNamesAndMiddleTruncatesLongOnes() {
        #expect(ReceivedFiles.menuTitle(for: "IMG_2041.jpg") == "IMG_2041.jpg")
        let exact = String(repeating: "a", count: 50)
        #expect(ReceivedFiles.menuTitle(for: exact) == exact)

        let long = "quarterly_financial_report_final_reviewed_copy_2026_11_27.pdf"
        let shortened = ReceivedFiles.menuTitle(for: long, maxCharacters: 30)
        #expect(shortened == "quarterly_financia…6_11_27.pdf")
        #expect(shortened.count == 30)
    }
}
