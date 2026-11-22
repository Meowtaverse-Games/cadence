// CI-only guard against capturing the launch surface before SwiftUI has drawn its content.
import AppKit
import Foundation

let arguments = CommandLine.arguments
if arguments.count != 2 { exit(1) }
guard let data = try? Data(contentsOf: URL(fileURLWithPath: arguments[1])),
      let bitmap = NSBitmapImageRep(data: data) else { exit(1) }
var foregroundSamples = 0
// Exclude system status/navigation areas: they can be visible while the app is still black.
for y in stride(from: bitmap.pixelsHigh / 5, to: bitmap.pixelsHigh * 4 / 5, by: 8) {
    for x in stride(from: bitmap.pixelsWide / 10, to: bitmap.pixelsWide * 9 / 10, by: 8) {
        guard let color = bitmap.colorAt(x: x, y: y)?.usingColorSpace(.deviceRGB) else { continue }
        if max(color.redComponent, max(color.greenComponent, color.blueComponent)) > 0.2 {
            foregroundSamples += 1
            if foregroundSamples >= 20 { exit(0) }
        }
    }
}
fputs("Preview content has not rendered yet.\n", stderr)
exit(1)
