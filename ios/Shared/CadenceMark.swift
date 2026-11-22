import UIKit

/// Shared by the application and the shield; no service or storage dependencies.
enum CadencePalette {
    static func color(_ hex: UInt32) -> UIColor {
        UIColor(red: CGFloat((hex >> 16) & 255) / 255, green: CGFloat((hex >> 8) & 255) / 255,
                blue: CGFloat(hex & 255) / 255, alpha: 1)
    }
    static let ground = color(0x0B1216)
    static let surface = color(0x131D22)
    static let surface2 = color(0x1A272D)
    static let line = color(0x2A3A41)
    static let text = color(0xE9F1EF)
    static let muted = color(0x9DB0AE)
    static let focus = color(0xFFB648)
    static let free = color(0x2FB9A5)
    static let alert = color(0xFF8A7A)
    static let grid = color(0x59E3C8)
}

enum CadenceMark {
    /// scale=1 bounds the shield allocation to 180×180 pixels, regardless of device scale.
    static func image(pixels: CGFloat = 180) -> UIImage {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: CGSize(width: pixels, height: pixels), format: format).image { renderer in
            let context = renderer.cgContext
            context.scaleBy(x: pixels / 100, y: pixels / 100)
            context.setLineWidth(9)
            context.setStrokeColor(CadencePalette.surface2.cgColor)
            context.strokeEllipse(in: CGRect(x: 20, y: 20, width: 60, height: 60))
            for cycle in 0..<3 {
                let offset = Double(cycle) * 62.83
                for (start, length, color) in [(offset, 38.9, CadencePalette.focus),
                                               (offset + 41.4, 18.93, CadencePalette.free)] {
                    context.setStrokeColor(color.cgColor)
                    context.addArc(center: CGPoint(x: 50, y: 50), radius: 30,
                                   startAngle: start / 30 - .pi / 2,
                                   endAngle: (start + length) / 30 - .pi / 2, clockwise: false)
                    context.strokePath()
                }
            }
            context.translateBy(x: 50, y: 50)
            context.rotate(by: .pi / 4)
            context.setFillColor(CadencePalette.text.cgColor)
            context.addPath(UIBezierPath(roundedRect: CGRect(x: -7, y: -7, width: 14, height: 14), cornerRadius: 1.5).cgPath)
            context.fillPath()
        }
    }
}
