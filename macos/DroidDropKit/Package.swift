// swift-tools-version: 6.2
import PackageDescription

let package = Package(
    name: "DroidDropKit",
    platforms: [.macOS(.v26)],
    products: [
        .library(name: "DroidDropKit", targets: ["DroidDropKit"]),
        .executable(name: "droiddrop-server", targets: ["DroidDropServerCLI"]),
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-certificates.git", from: "1.21.0"),
        .package(url: "https://github.com/apple/swift-asn1.git", from: "1.0.0"),
    ],
    targets: [
        .target(
            name: "DroidDropKit",
            dependencies: [
                .product(name: "X509", package: "swift-certificates"),
                .product(name: "SwiftASN1", package: "swift-asn1"),
            ],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .executableTarget(
            name: "DroidDropServerCLI",
            dependencies: ["DroidDropKit"],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .testTarget(
            name: "DroidDropKitTests",
            dependencies: ["DroidDropKit"],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
    ]
)
