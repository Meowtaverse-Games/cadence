import Foundation
import XCTest
import CadenceCore

final class ExpansionTests: XCTestCase {
    private let monday = instant("2026-01-05T10:00:00Z")

    func testQueryInsideBlockReturnsWholeInterval() throws {
        let timetable = try validTimetable()
        XCTAssertEqual(try TimetableExpander.expand(timetable, from: monday.addingTimeInterval(600),
                                                   to: monday.addingTimeInterval(1200)),
                       [BlockInterval(start: monday, end: monday.addingTimeInterval(3600))])
    }

    func testExplicitInstantHasHalfOpenBounds() throws {
        let timetable = try validTimetable()
        for (offset, expected) in [(-1.0, false), (0.0, true), (3599.999, true), (3600.0, false)] {
            XCTAssertEqual(TimetableExpander.isBlockedAt(timetable, now: monday.addingTimeInterval(offset)), expected)
        }
        let interval = BlockInterval(start: monday, end: monday.addingTimeInterval(3600))
        XCTAssertTrue(interval.contains(monday))
        XCTAssertFalse(interval.contains(interval.end))
    }

    func testQueryOnlyTouchesBlock() throws {
        let timetable = try validTimetable()
        XCTAssertTrue(try TimetableExpander.expand(timetable, from: monday.addingTimeInterval(-60), to: monday).isEmpty)
        XCTAssertTrue(try TimetableExpander.expand(timetable, from: monday.addingTimeInterval(3600),
                                                  to: monday.addingTimeInterval(3660)).isEmpty)
    }

    func testEmptyAndReversedRanges() throws {
        let timetable = try validTimetable()
        XCTAssertTrue(try TimetableExpander.expand(timetable, from: monday, to: monday).isEmpty)
        XCTAssertThrowsError(try TimetableExpander.expand(timetable, from: monday.addingTimeInterval(1), to: monday)) {
            guard case ExpansionError.invalidRange = $0 else { return XCTFail("Unexpected error: \($0)") }
        }
        XCTAssertThrowsError(try TimetableExpander.expand(timetable, from: Date(timeIntervalSince1970: .nan), to: monday))
    }

    func testSixWeeksAcrossYearBoundary() throws {
        let timetable = try validTimetable()
        let start = instant("2025-12-15T00:00:00Z")
        let intervals = try TimetableExpander.expand(timetable, from: start, to: start.addingTimeInterval(42 * 86400))
        XCTAssertEqual(intervals.count, 42)
        for (day, interval) in intervals.enumerated() {
            let expectedStart = start.addingTimeInterval(Double(day * 86400 + 10 * 3600))
            XCTAssertEqual(interval, BlockInterval(start: expectedStart, end: expectedStart.addingTimeInterval(3600)))
        }
    }

    func testSortedResultsDoNotMergeTouchingPatternsOrWindows() throws {
        let timetable = try validTimetable(document(patterns: [
            pattern(id: "late", start: "20:00", end: "21:00"),
            pattern(id: "early", start: "19:00", end: "20:00")
        ]))
        let intervals = try TimetableExpander.expand(timetable, from: monday, to: monday.addingTimeInterval(2 * 86400))
        XCTAssertEqual(intervals.count, 4)
        for day in 0..<2 {
            for hour in 0..<2 {
                let start = monday.addingTimeInterval(Double(day * 86400 + hour * 3600))
                XCTAssertEqual(intervals[day * 2 + hour], BlockInterval(start: start, end: start.addingTimeInterval(3600)))
            }
        }
        let fullDay = try validTimetable(document(patterns: [pattern(start: "19:00", end: "19:00")]))
        let windows = try TimetableExpander.expand(fullDay, from: monday, to: monday.addingTimeInterval(2 * 86400))
        XCTAssertEqual(windows.count, 2)
        XCTAssertEqual(windows[0].end, windows[1].start)
    }

    func testDefaultTimeZoneDoesNotAffectResults() throws {
        let timetable = try validTimetable(document(patterns: [pattern(start: "23:00", end: "01:00")]))
        let start = instant("2026-03-07T00:00:00Z")
        let end = start.addingTimeInterval(5 * 86400)
        let expected = try TimetableExpander.expand(timetable, from: start, to: end)
        let original = NSTimeZone.default
        defer { NSTimeZone.default = original }
        NSTimeZone.default = try XCTUnwrap(TimeZone(identifier: "America/Los_Angeles"))
        XCTAssertEqual(try TimetableExpander.expand(timetable, from: start, to: end), expected)
        XCTAssertTrue(TimetableExpander.isBlockedAt(timetable, now: instant("2026-03-08T15:30:00Z")))
    }

    func testWeekdaysBeforeUnixEpoch() throws {
        var item = pattern()
        item["weekdays"] = [1]
        let timetable = try validTimetable(document(patterns: [item]))
        let start = instant("1969-12-28T00:00:00Z")
        let intervals = try TimetableExpander.expand(timetable, from: start, to: instant("1970-01-01T00:00:00Z"))
        XCTAssertEqual(intervals, [BlockInterval(start: instant("1969-12-29T10:00:00Z"), end: instant("1969-12-29T11:00:00Z"))])
    }

    func testTwentyPatternsWithTwentySegmentsExpandFourteenDaysWithinBudget() throws {
        let patterns = (0..<20).reversed().map { hour -> [String: Any] in
            var item = pattern(id: "load-\(hour)", start: String(format: "%02d:00", hour),
                               end: String(format: "%02d:00", hour + 1))
            item["segments"] = (0..<20).map { index -> [String: Any] in
                ["kind": index % 2 == 0 ? "free" : "block", "minutes": index % 2 == 0 ? 1 : 15]
            }
            return item
        }
        let timetable = try validTimetable(document(patterns: patterns))
        let start = instant("2026-01-04T15:00:00Z") // Monday midnight at UTC+09:00.
        let end = start.addingTimeInterval(14 * 86400)
        for _ in 0..<5 { _ = try TimetableExpander.expand(timetable, from: start, to: end) }
        let begin = ProcessInfo.processInfo.systemUptime
        let intervals = try TimetableExpander.expand(timetable, from: start, to: end)
        let milliseconds = (ProcessInfo.processInfo.systemUptime - begin) * 1000
        print("Expansion: \(milliseconds) ms (target 50 ms; CI limit 500 ms; debug build)")
        XCTAssertLessThan(milliseconds, 500)
        XCTAssertEqual(intervals.count, 14 * 20 * 3)
        for day in 0..<14 {
            for hour in 0..<20 {
                for segment in 0..<3 {
                    let blockStart = start.addingTimeInterval(Double(day * 86400 + hour * 3600 + (1 + segment * 16) * 60))
                    XCTAssertEqual(intervals[(day * 20 + hour) * 3 + segment],
                                   BlockInterval(start: blockStart, end: blockStart.addingTimeInterval(15 * 60)))
                }
            }
        }
    }
}
