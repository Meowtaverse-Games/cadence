import Foundation

/// Presentation only. Segments are the complement of the existing expander's block intervals.
public struct DisplayWindow {
    public let start: Date
    public let end: Date
    /// Index in Timetable (and in the validated editable document), preserving scalar identity.
    public let patternIndex: Int
    public let segments: [DisplaySegment]
}

public struct DisplaySegment {
    public let start: Date
    public let end: Date
    public let isBlock: Bool
}

public enum TimetableDisplay {
    /// Current window, including yesterday's overnight window; otherwise today's next window in JST.
    public static func window(_ timetable: Timetable, at now: Date) throws -> DisplayWindow? {
        let seconds = now.timeIntervalSince1970
        guard seconds.isFinite, abs(seconds) < 1e12 else { throw ExpansionError.invalidRange }
        let today = floor((seconds + 32400) / 86400)
        var windows: [(Date, Date, Int)] = []
        for day in [today - 1, today] {
            let weekday = (Int(day.truncatingRemainder(dividingBy: 7)) + 10) % 7 + 1
            for (index, pattern) in timetable.patterns.enumerated()
                where pattern.enabled && pattern.weekdays.contains(weekday) {
                let start = Date(timeIntervalSince1970: day * 86400 - 32400 + Double(pattern.startMinute * 60))
                let end = start.addingTimeInterval(Double(pattern.durationMinutes * 60))
                if start <= now && now < end || day == today && start > now {
                    windows.append((start, end, index))
                }
            }
        }
        guard let selected = windows.sorted(by: { $0.0 < $1.0 }).first else { return nil }
        let (start, end, index) = selected
        let blocks = try TimetableExpander.expand(timetable, from: start, to: end)
        var cursor = start
        var segments: [DisplaySegment] = []
        for block in blocks {
            let lower = max(start, block.start)
            let upper = min(end, block.end)
            if cursor < lower { segments.append(DisplaySegment(start: cursor, end: lower, isBlock: false)) }
            if lower < upper { segments.append(DisplaySegment(start: lower, end: upper, isBlock: true)) }
            cursor = max(cursor, upper)
        }
        if cursor < end { segments.append(DisplaySegment(start: cursor, end: end, isBlock: false)) }
        return DisplayWindow(start: start, end: end, patternIndex: index, segments: segments)
    }
}
