import SwiftUI
import CadenceCore

@main
struct CadenceApp: App {
    var body: some Scene {
        WindowGroup {
            VStack(spacing: 12) {
                Text("Cadence").font(.largeTitle)
                Text("準備中").foregroundStyle(.secondary)
            }
            .padding()
        }
    }
}
