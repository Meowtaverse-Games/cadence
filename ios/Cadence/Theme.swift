import SwiftUI
import CadenceCore

enum Theme {
    static let ground = Color(uiColor: CadencePalette.ground)
    static let surface = Color(uiColor: CadencePalette.surface)
    static let surface2 = Color(uiColor: CadencePalette.surface2)
    static let line = Color(uiColor: CadencePalette.line)
    static let text = Color(uiColor: CadencePalette.text)
    static let muted = Color(uiColor: CadencePalette.muted)
    static let focus = Color(uiColor: CadencePalette.focus)
    static let free = Color(uiColor: CadencePalette.free)
    static let alert = Color(uiColor: CadencePalette.alert)
    static let radius: CGFloat = 16
    static let spacing: CGFloat = 16
    static let mono = Font.system(.caption, design: .monospaced)
    static let tag = Font.system(.caption, design: .monospaced).weight(.bold)
    static let clock = Font.system(.largeTitle, design: .monospaced).weight(.semibold)

    static func time(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "Asia/Tokyo")
        formatter.dateFormat = "HH:mm"
        return formatter.string(from: date)
    }
    static func remaining(until date: Date, now: Date) -> String {
        let seconds = max(0, Int(ceil(date.timeIntervalSince(now))))
        if seconds >= 3600 { return String(format: "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60) }
        return String(format: "%02d:%02d", seconds / 60, seconds % 60)
    }
}

struct GridBackground: View {
    var body: some View {
        Theme.ground.overlay {
            Canvas { context, size in
                var path = Path()
                for x in stride(from: CGFloat(0), through: size.width, by: 24) {
                    path.move(to: CGPoint(x: x, y: 0)); path.addLine(to: CGPoint(x: x, y: size.height))
                }
                for y in stride(from: CGFloat(0), through: size.height, by: 24) {
                    path.move(to: CGPoint(x: 0, y: y)); path.addLine(to: CGPoint(x: size.width, y: y))
                }
                context.stroke(path, with: .color(Color(uiColor: CadencePalette.grid).opacity(0.05)), lineWidth: 0.5)
            }.accessibilityHidden(true)
        }.ignoresSafeArea()
    }
}

extension View {
    func cadenceCard() -> some View {
        padding(Theme.spacing).background(Theme.surface, in: RoundedRectangle(cornerRadius: Theme.radius))
            .overlay(RoundedRectangle(cornerRadius: Theme.radius).stroke(Theme.line, lineWidth: 1))
    }
    func cadenceField() -> some View {
        padding(12).frame(minHeight: 44).background(Theme.surface2, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Theme.line, lineWidth: 1))
    }
    func cadenceScreen() -> some View {
        foregroundStyle(Theme.text).tint(Theme.free).background { GridBackground() }
            .preferredColorScheme(.dark)
            .toolbarBackground(Theme.ground, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
    }
}

struct CadenceButtonStyle: ButtonStyle {
    var primary = false
    var outlined = false
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(.subheadline.weight(primary ? .bold : .medium))
            .padding(.horizontal, 14).padding(.vertical, 10).frame(minHeight: 44)
            .foregroundStyle(primary ? Theme.ground : Theme.text)
            .background(primary ? Theme.focus : outlined ? Color.clear : Theme.surface2, in: RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(primary ? Theme.focus : Theme.line, lineWidth: 1))
            .opacity(enabled ? (configuration.isPressed ? 0.7 : 1) : 0.4)
    }
}

struct StateTag: View {
    let text: String
    let color: Color
    var body: some View { Text(text).font(Theme.tag).tracking(2).foregroundStyle(color) }
}

struct WindowBar: View {
    let window: DisplayWindow
    let name: String
    var now: Date?
    var body: some View {
        VStack(spacing: 8) {
            Canvas { context, size in
                let duration = window.end.timeIntervalSince(window.start)
                for segment in window.segments {
                    let x = segment.start.timeIntervalSince(window.start) / duration * size.width
                    let width = segment.end.timeIntervalSince(segment.start) / duration * size.width
                    let rect = CGRect(x: x, y: 0, width: max(0, width - 2), height: size.height)
                    let shape = Path(roundedRect: rect, cornerRadius: 3)
                    context.fill(shape, with: .color(segment.isBlock ? Theme.focus : Theme.free.opacity(0.55)))
                    if segment.isBlock {
                        var clipped = context
                        clipped.clip(to: shape)
                        var stripes = Path()
                        for offset in stride(from: rect.minX - size.height, through: rect.maxX, by: 9) {
                            stripes.move(to: CGPoint(x: offset, y: size.height))
                            stripes.addLine(to: CGPoint(x: offset + size.height, y: 0))
                        }
                        clipped.stroke(stripes, with: .color(Theme.ground.opacity(0.22)), lineWidth: 3)
                    }
                }
                if let now, window.start <= now && now < window.end {
                    let x = now.timeIntervalSince(window.start) / duration * size.width
                    context.fill(Path(CGRect(x: x, y: 0, width: 2, height: size.height)), with: .color(Theme.text))
                }
            }.frame(height: 28).accessibilityHidden(true)
            HStack {
                Text(Theme.time(window.start)); Spacer(); Text(name); Spacer(); Text(Theme.time(window.end))
            }.font(Theme.mono).foregroundStyle(Theme.muted)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(name)、\(Theme.time(window.start))から\(Theme.time(window.end))。" + window.segments.map {
            "\($0.isBlock ? "FOCUS 集中" : "FREE 自由") \(Theme.time($0.start))から\(Theme.time($0.end))"
        }.joined(separator: "、"))
    }
}
