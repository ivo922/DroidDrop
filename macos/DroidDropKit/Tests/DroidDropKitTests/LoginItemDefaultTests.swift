import Foundation
import Testing
@testable import DroidDropKit

@Suite struct LoginItemDefaultTests {
    private let folders = [URL(fileURLWithPath: "/Applications"), URL(fileURLWithPath: "/Users/me/Applications")]

    @Test func turnsOnOnceWhenInstalledInApplications() {
        #expect(LoginItemDefault.shouldRegister(
            alreadyApplied: false, bundleURL: URL(fileURLWithPath: "/Applications/DroidDrop.app"),
            applicationsFolders: folders))
        #expect(LoginItemDefault.shouldRegister(
            alreadyApplied: false, bundleURL: URL(fileURLWithPath: "/Users/me/Applications/DroidDrop.app"),
            applicationsFolders: folders))
    }

    @Test func leavesTheUsersChoiceAloneAfterTheFirstTime() {
        #expect(!LoginItemDefault.shouldRegister(
            alreadyApplied: true, bundleURL: URL(fileURLWithPath: "/Applications/DroidDrop.app"),
            applicationsFolders: folders))
    }

    @Test func skipsCopiesOutsideApplications() {
        for path in ["/Users/me/Downloads/DroidDrop.app", "/Users/me/src/macos/build/Debug/DroidDrop.app",
                     "/ApplicationsOld/DroidDrop.app"] {
            #expect(!LoginItemDefault.shouldRegister(
                alreadyApplied: false, bundleURL: URL(fileURLWithPath: path), applicationsFolders: folders))
        }
    }
}
