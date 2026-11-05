// swift-tools-version: 6.2
import PackageDescription

let package = Package(
    name: "DroidDropKit",
    platforms: [.macOS(.v26)],
    products: [
        .library(name: "DroidDropKit", targets: ["DroidDropKit"]),
    ],
    targets: [
        .target(
            name: "DroidDropKit",
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .testTarget(
            name: "DroidDropKitTests",
            dependencies: ["DroidDropKit"],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
    ]
)
