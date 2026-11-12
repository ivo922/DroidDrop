import Foundation
import Testing
@testable import DroidDropKit

@Suite struct FileNameTests {
    @Test func matchesSanitizeFixtures() throws {
        let fixture = try Fixtures.json("sanitize")
        for entry in Fixtures.list(fixture, "cases") {
            let input = entry["input"] as! String
            #expect(FileName.sanitize(input) == entry["output"] as! String, "\(input.prefix(40))")
        }
    }

    @Test func uniqueAddsSmallestFreeSuffix() {
        let taken: Set = ["a.txt", "a (1).txt", "a (3).txt", "README", "archive.tar.gz"]
        #expect(FileName.unique("b.txt") { taken.contains($0) } == "b.txt")
        #expect(FileName.unique("a.txt") { taken.contains($0) } == "a (2).txt")
        #expect(FileName.unique("README") { taken.contains($0) } == "README (1)")
        #expect(FileName.unique("archive.tar.gz") { taken.contains($0) } == "archive.tar (1).gz")
    }
}
