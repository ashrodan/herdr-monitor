// swift-tools-version: 6.0
import PackageDescription

// HerdrKit: models, the server API, the live store, the outbox (no UI).
// HerdrUI: every SwiftUI screen. The Xcode app target (project.yml) is a
// thin shell over HerdrUI, so `swift build` on a Mac checks all the code.
let package = Package(
    name: "HerdrMonitor",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "HerdrKit", targets: ["HerdrKit"]),
        .library(name: "HerdrUI", targets: ["HerdrUI"]),
    ],
    targets: [
        .target(name: "HerdrKit", path: "Sources/HerdrKit"),
        .target(name: "HerdrUI", dependencies: ["HerdrKit"], path: "Sources/HerdrUI"),
        .testTarget(name: "HerdrKitTests", dependencies: ["HerdrKit"], path: "Tests/HerdrKitTests"),
    ],
    swiftLanguageModes: [.v5]
)
