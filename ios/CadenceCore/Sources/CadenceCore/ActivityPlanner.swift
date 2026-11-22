import Foundation

public struct PlannedActivity: Equatable {
    public let name: String
    public let interval: BlockInterval
}

/// iOS scheduling policy; the shared timetable expansion rules remain unchanged.
public enum ActivityPlanner {
    public static let watchdogName = "cadence.watchdog"
    public static let horizon: TimeInterval = 14 * 86400

    public static func plan(_ timetable: Timetable, now: Date) throws -> [PlannedActivity] {
        try TimetableExpander.expand(timetable, from: now, to: now.addingTimeInterval(horizon))
            .prefix(7).map { PlannedActivity(name: name(for: $0.start), interval: $0) }
    }

    public static func name(for start: Date) -> String {
        "cadence.block.\(Int64(start.timeIntervalSince1970))"
    }

    public static func start(for name: String) -> Date? {
        let prefix = "cadence.block."
        guard name.hasPrefix(prefix), let seconds = Int64(name.dropFirst(prefix.count)) else { return nil }
        return Date(timeIntervalSince1970: Double(seconds))
    }

    public static func evaluationTime(now: Date, intervalDidStart: Bool, activityStart: Date?) -> Date {
        intervalDidStart ? max(now, activityStart ?? now) : now
    }

    public static func isBlocked(_ timetable: Timetable?, at instant: Date) -> Bool {
        timetable.map { TimetableExpander.isBlockedAt($0, now: instant) } ?? false
    }

    /// Adjacent intervals keep the shield on: return the end of the entire contiguous run.
    /// A full weekly cycle without a gap has no next switch.
    public static func nextTransition(_ timetable: Timetable, now: Date) throws -> Date? {
        let intervals = try TimetableExpander.expand(timetable, from: now, to: now.addingTimeInterval(horizon))
        guard let first = intervals.first else { return nil }
        guard first.contains(now) else { return first.start }
        var end = first.end
        for interval in intervals.dropFirst() {
            if interval.start > end { return end }
            end = max(end, interval.end)
        }
        return end >= now.addingTimeInterval(horizon) ? nil : end
    }

    public static func components(for instant: Date, calendar: Calendar) -> DateComponents {
        calendar.dateComponents([.year, .month, .day, .hour, .minute], from: instant)
    }
}
