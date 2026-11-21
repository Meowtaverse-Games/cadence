import Foundation
import CadenceCore
import XCTest

func instant(_ value: String) -> Date {
    ISO8601DateFormatter().date(from: value)!
}

func utcText(_ date: Date) -> String {
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.calendar = Calendar(identifier: .gregorian)
    formatter.timeZone = TimeZone(secondsFromGMT: 0)
    formatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss'Z'"
    return formatter.string(from: date)
}

func pattern(id: String = "synthetic", start: String = "19:00", end: String = "20:00",
             enabled: Any = true, minutes: Any = 60) -> [String: Any] {
    ["id": id, "name": "合成例", "enabled": enabled, "weekdays": [1, 2, 3, 4, 5, 6, 7],
     "start": start, "end": end, "segments": [["kind": "block", "minutes": minutes]]]
}

func document(patterns: [[String: Any]] = [pattern()]) -> [String: Any] {
    ["schemaVersion": 1, "timeZone": "Asia/Tokyo", "patterns": patterns]
}

func validTimetable(_ object: Any = document(), file: StaticString = #filePath, line: UInt = #line) throws -> Timetable {
    let result = TimetableParser.parse(object: object)
    XCTAssertTrue(result.valid, result.errorCode ?? "", file: file, line: line)
    XCTAssertNil(result.errorCode, file: file, line: line)
    return try XCTUnwrap(result.timetable, file: file, line: line)
}
