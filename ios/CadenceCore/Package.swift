// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "CadenceCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "CadenceCore", targets: ["CadenceCore"])],
    targets: [
        .target(name: "CadenceCore"),
        .testTarget(name: "CadenceCoreTests", dependencies: ["CadenceCore"])
    ]
)
