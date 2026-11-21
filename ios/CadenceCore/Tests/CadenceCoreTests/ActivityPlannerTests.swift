import Foundation
import XCTest
import CadenceCore

final class ActivityPlannerTests: XCTestCase {
    let monday = instant("2026-01-05T10:00:00Z")

    func testSevenLimitIncludesCurrentWholeInterval() throws {
        let items = try ActivityPlanner.plan(validTimetable(), now: monday.addingTimeInterval(600))
        XCTAssertEqual(items.count, 7)
        XCTAssertEqual(items.first?.interval.start, monday)
        XCTAssertEqual(items.last?.interval.start, monday.addingTimeInterval(6 * 86400))
    }

    func testFourteenDayHorizonAndStableNames() throws {
        var item = pattern()
        item["weekdays"] = [1]
        let timetable = try validTimetable(document(patterns: [item]))
        let items = try ActivityPlanner.plan(timetable, now: monday)
        XCTAssertEqual(items.map(\.interval.start), [monday, monday.addingTimeInterval(7 * 86400)])
        XCTAssertEqual(items.first?.name, try ActivityPlanner.plan(timetable, now: monday.addingTimeInterval(1)).first?.name)
        XCTAssertNotEqual(items[0].name, ActivityPlanner.watchdogName)
        XCTAssertEqual(ActivityPlanner.start(for: items[0].name), monday)
        XCTAssertNil(ActivityPlanner.start(for: ActivityPlanner.watchdogName))
    }

    func testEarlyStartAndDelayedEventsEvaluateTimeNotEventType() throws {
        let timetable = try validTimetable()
        let early = monday.addingTimeInterval(-2)
        let time = ActivityPlanner.evaluationTime(now: early, intervalDidStart: true, activityStart: monday)
        XCTAssertEqual(time, monday)
        XCTAssertTrue(ActivityPlanner.isBlocked(timetable, at: time))
        let late = monday.addingTimeInterval(3600)
        XCTAssertEqual(ActivityPlanner.evaluationTime(now: late, intervalDidStart: true, activityStart: monday), late)
        XCTAssertFalse(ActivityPlanner.isBlocked(timetable, at: late))
        XCTAssertEqual(ActivityPlanner.evaluationTime(now: early, intervalDidStart: false, activityStart: monday), early)
    }

    func testTouchingWindowsDoNotReleaseAndNextSwitchSkipsBoundary() throws {
        let timetable = try validTimetable(document(patterns: [
            pattern(id: "a", start: "19:00", end: "19:15"),
            pattern(id: "b", start: "19:15", end: "19:30")
        ]))
        let boundary = monday.addingTimeInterval(900)
        XCTAssertTrue(ActivityPlanner.isBlocked(timetable, at: boundary))
        XCTAssertEqual(try ActivityPlanner.nextTransition(timetable, now: monday), monday.addingTimeInterval(1800))
        XCTAssertEqual(try ActivityPlanner.plan(timetable, now: boundary).first?.interval.start, boundary)
    }

    func testNextTransitionBeyondSevenDays() throws {
        // Eight consecutive full-day windows, then a gap (weekly repetition gives a run > 7 days
        // only when queried before its start; the end below is seven days after the query).
        var item = pattern(start: "19:00", end: "19:00")
        item["weekdays"] = [1, 2, 3, 4, 5, 6]
        let timetable = try validTimetable(document(patterns: [item]))
        XCTAssertEqual(try ActivityPlanner.nextTransition(timetable, now: monday), monday.addingTimeInterval(6 * 86400))
        var weekly = pattern()
        weekly["weekdays"] = [1]
        let weeklyTable = try validTimetable(document(patterns: [weekly]))
        XCTAssertEqual(try ActivityPlanner.nextTransition(weeklyTable, now: monday.addingTimeInterval(3600)),
                       monday.addingTimeInterval(7 * 86400))
        let continuous = try validTimetable(document(patterns: [pattern(start: "19:00", end: "19:00")]))
        XCTAssertNil(try ActivityPlanner.nextTransition(continuous, now: monday))
    }

    func testCalendarZoneConversionPreservesInstant() {
        for offset in [9 * 3600, -8 * 3600] {
            var calendar = Calendar(identifier: .gregorian)
            calendar.timeZone = TimeZone(secondsFromGMT: offset)!
            let components = ActivityPlanner.components(for: monday, calendar: calendar)
            XCTAssertEqual(components.hour, offset > 0 ? 19 : 2)
            XCTAssertEqual(calendar.date(from: components), monday)
            XCTAssertNil(components.timeZone)
        }
    }

    func testEmptyTimetable() throws {
        let empty = try validTimetable(document(patterns: []))
        XCTAssertEqual(try ActivityPlanner.plan(empty, now: monday), [])
        XCTAssertNil(try ActivityPlanner.nextTransition(empty, now: monday))
        XCTAssertFalse(ActivityPlanner.isBlocked(empty, at: monday))
        XCTAssertFalse(ActivityPlanner.isBlocked(nil, at: monday))
    }
}
