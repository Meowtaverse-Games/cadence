import Foundation

public enum ExpansionError: Error {
    case invalidRange
}

public enum TimetableExpander {
    private static let daySeconds: Double = 86400
    private static let japanOffset: Double = 9 * 3600

    /// Returns whole intervals intersecting [from, to), sorted by start.
    /// Empty ranges return []; reversed, non-finite or unrepresentable ranges throw invalidRange.
    public static func expand(_ timetable: Timetable, from: Date, to: Date) throws -> [BlockInterval] {
        let fromSeconds = from.timeIntervalSince1970
        let toSeconds = to.timeIntervalSince1970
        guard fromSeconds.isFinite, toSeconds.isFinite, from <= to else { throw ExpansionError.invalidRange }
        if from == to { return [] }

        // Local day zero is 1970-01-01 (Thursday). No system calendar or zone database is involved.
        var day = floor((fromSeconds + japanOffset) / daySeconds) - 1
        let lastDay = floor((toSeconds + japanOffset) / daySeconds)
        var result: [BlockInterval] = []
        while day <= lastDay {
            let weekday = (Int(day.truncatingRemainder(dividingBy: 7)) + 10) % 7 + 1
            let midnight = day * daySeconds - japanOffset
            for pattern in timetable.patterns where pattern.enabled && pattern.weekdays.contains(weekday) {
                let start = midnight + Double(pattern.startMinute * 60)
                let end = start + Double(pattern.durationMinutes * 60)
                if start >= toSeconds || end <= fromSeconds { continue }
                var cursor = start
                var index = 0
                var blockStart: Double?
                func appendBlock(endingAt end: Double) {
                    if let start = blockStart, end - start >= 15 * 60, start < toSeconds, end > fromSeconds {
                        result.append(BlockInterval(start: Date(timeIntervalSince1970: start),
                                                    end: Date(timeIntervalSince1970: end)))
                    }
                }
                while cursor < end {
                    let segment = pattern.segments[index]
                    let next = min(cursor + Double(segment.minutes * 60), end)
                    guard next > cursor else { throw ExpansionError.invalidRange }
                    if segment.isBlock {
                        if blockStart == nil { blockStart = cursor }
                    } else {
                        appendBlock(endingAt: cursor)
                        blockStart = nil
                    }
                    cursor = next
                    index = (index + 1) % pattern.segments.count
                }
                appendBlock(endingAt: end)
            }
            guard day + 1 > day else { throw ExpansionError.invalidRange }
            day += 1
        }
        return result.sorted { $0.start < $1.start }
    }

    /// Evaluates an explicit instant with half-open bounds; never reads the system clock.
    public static func isBlockedAt(_ timetable: Timetable, now: Date) -> Bool {
        let intervals = try? expand(timetable, from: now.addingTimeInterval(-daySeconds),
                                    to: now.addingTimeInterval(daySeconds))
        return (intervals ?? []).contains { $0.contains(now) }
    }
}
