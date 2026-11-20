import Foundation

/// Immutable and constructible only through successful validation.
public struct Timetable {
    let patterns: [Pattern]
}

struct Pattern {
    let enabled: Bool
    let weekdays: Set<Int>
    let startMinute: Int
    let durationMinutes: Int
    let segments: [Segment]
}

struct Segment {
    let isBlock: Bool
    let minutes: Int
}

public struct ValidationResult {
    public let timetable: Timetable?
    public let errorCode: String?
    public var valid: Bool { timetable != nil }
}

/// A half-open interval [start, end), expressed as absolute instants.
public struct BlockInterval: Equatable {
    public let start: Date
    public let end: Date

    public init(start: Date, end: Date) {
        self.start = start
        self.end = end
    }

    public func contains(_ instant: Date) -> Bool {
        start <= instant && instant < end
    }
}
