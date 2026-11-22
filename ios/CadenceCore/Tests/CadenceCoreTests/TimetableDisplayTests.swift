import XCTest
@testable import CadenceCore

final class TimetableDisplayTests: XCTestCase {
    private func timetable(start: String = "19:00", end: String = "20:00",
                           segments: [[String: Any]] = [["kind": "block", "minutes": 20], ["kind": "free", "minutes": 10]]) throws -> Timetable {
        var value = pattern(start: start, end: end)
        value["segments"] = segments
        return try validTimetable(document(patterns: [value]))
    }

    func testCurrentAndUpcomingWindow() throws {
        let table = try timetable()
        for time in ["2026-01-05T18:00:00+09:00", "2026-01-05T19:10:00+09:00"] {
            let window = try XCTUnwrap(TimetableDisplay.window(table, at: instant(time)))
            XCTAssertEqual(window.start, instant("2026-01-05T19:00:00+09:00"))
            XCTAssertEqual(window.end, instant("2026-01-05T20:00:00+09:00"))
            XCTAssertEqual(window.patternIndex, 0)
            let blocks = window.segments.filter(\.isBlock).map { BlockInterval(start: $0.start, end: $0.end) }
            XCTAssertEqual(blocks, try TimetableExpander.expand(table, from: window.start, to: window.end))
            XCTAssertFalse(try XCTUnwrap(window.segments.last).isBlock) // Ends free.
        }
        XCTAssertNil(try TimetableDisplay.window(table, at: instant("2026-01-05T20:00:00+09:00")))
        let empty = try validTimetable(document(patterns: []))
        XCTAssertNil(try TimetableDisplay.window(empty, at: instant("2026-01-05T12:00:00+09:00")))
    }

    func testOvernightDuringAndBefore() throws {
        let table = try timetable(start: "23:00", end: "01:00")
        for time in ["2026-01-05T22:00:00+09:00", "2026-01-06T00:30:00+09:00"] {
            let window = try XCTUnwrap(TimetableDisplay.window(table, at: instant(time)))
            XCTAssertEqual(window.start, instant("2026-01-05T23:00:00+09:00"))
            XCTAssertEqual(window.end, instant("2026-01-06T01:00:00+09:00"))
            XCTAssertEqual(window.segments.filter(\.isBlock).map { BlockInterval(start: $0.start, end: $0.end) },
                           try TimetableExpander.expand(table, from: window.start, to: window.end))
        }
    }

    func testShortTailIsFreeAndAdjacentBlocksMerge() throws {
        let table = try timetable(end: "19:40")
        let window = try XCTUnwrap(TimetableDisplay.window(table, at: instant("2026-01-05T19:35:00+09:00")))
        XCTAssertEqual(window.segments.count, 2)
        XCTAssertFalse(window.segments[1].isBlock)
        XCTAssertEqual(window.segments[1].start, instant("2026-01-05T19:20:00+09:00"))
        let merged = try timetable(end: "19:40", segments: [["kind": "block", "minutes": 25]])
        let whole = try XCTUnwrap(TimetableDisplay.window(merged, at: window.start))
        XCTAssertEqual(whole.segments.count, 1)
        XCTAssertTrue(whole.segments[0].isBlock)
        XCTAssertEqual(whole.segments[0].end, whole.end)
    }
}
