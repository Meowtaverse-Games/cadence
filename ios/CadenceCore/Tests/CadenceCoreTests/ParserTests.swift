import Foundation
import XCTest
import CadenceCore

final class ParserTests: XCTestCase {
    func testSwiftBooleanIsNotANumberAndNumericBooleanIsRejected() {
        for value in [0, 1, Int64(1), Double(1), NSNumber(value: 1)] as [Any] {
            XCTAssertEqual(TimetableParser.parse(object: document(patterns: [pattern(enabled: value)])).errorCode, "enabled")
        }
        XCTAssertTrue(TimetableParser.parse(object: document(patterns: [pattern(enabled: false)])).valid)
        XCTAssertEqual(TimetableParser.parse(object: document(patterns: [pattern(minutes: true)])).errorCode, "segment_minutes")
        var object = document()
        object["schemaVersion"] = true
        XCTAssertEqual(TimetableParser.parse(object: object).errorCode, "schema_version")
    }

    func testIntegralSwiftAndFoundationNumbers() {
        for value in [10, Int64(10), Double(10), NSNumber(value: 10.0)] as [Any] {
            var item = pattern()
            item["segments"] = [["kind": "free", "minutes": value], ["kind": "block", "minutes": 15]]
            XCTAssertTrue(TimetableParser.parse(object: document(patterns: [item])).valid, "\(value)")
        }
        for value in [10.5, NSNumber(value: 10.5), Double.infinity, -Double.infinity,
                           Double.nan, Double.greatestFiniteMagnitude, Int64.max, Int64.min] as [Any] {
            XCTAssertEqual(TimetableParser.parse(object: document(patterns: [pattern(minutes: value)])).errorCode,
                           "segment_minutes", "\(value)")
        }
    }

    func testFoundationTreeAndUnknownSDKValues() throws {
        struct SyntheticTimestamp { let seconds: Int64 }
        let item: NSDictionary = ["id": NSString(string: "foundation"), "name": NSString(string: "合成例"),
                                  "enabled": NSNumber(value: true), "weekdays": NSArray(array: [NSNumber(value: 1)]),
                                  "start": "19:00", "end": "20:00",
                                  "segments": NSArray(array: [NSDictionary(dictionary: ["kind": "block", "minutes": 60])])]
        let object: NSDictionary = ["schemaVersion": NSNumber(value: 1), "timeZone": NSString(string: "Asia/Tokyo"),
                                    "patterns": NSArray(array: [item]), "updatedAt": SyntheticTimestamp(seconds: 123),
                                    "extra": NSNull()]
        _ = try validTimetable(object)
        var swiftObject = document()
        swiftObject["updatedAt"] = SyntheticTimestamp(seconds: 456)
        _ = try validTimetable(swiftObject)
    }

    func testMalformedJSONAndUnpairedSurrogatesAnywhere() {
        let invalid = ["", "{", "{\"x\":}", #""\ud800""#, #""\udc00""#,
                       #"{"schemaVersion":1,"timeZone":"Asia/Tokyo","patterns":[],"extra":"\ud800"}"#,
                       #"{"\udc00":1}"#, #"{"extra":"\ud800\u0041"}"#]
        for json in invalid {
            XCTAssertEqual(TimetableParser.parse(data: Data(json.utf8)).errorCode, "invalid_json", json)
        }
        XCTAssertEqual(TimetableParser.parse(data: Data([0xFF, 0xFE, 0xFF])).errorCode, "invalid_json")
        let paired = #"{"schemaVersion":1,"timeZone":"Asia/Tokyo","patterns":[],"extra":"\ud83d\ude00"}"#
        XCTAssertTrue(TimetableParser.parse(data: Data(paired.utf8)).valid)
        let literalEscape = #"{"schemaVersion":1,"timeZone":"Asia/Tokyo","patterns":[],"extra":"\\ud800"}"#
        XCTAssertTrue(TimetableParser.parse(data: Data(literalEscape.utf8)).valid)
    }

    func testNonObjectRoots() {
        for json in ["null", "[]", "1", "true", #""text""#] {
            XCTAssertEqual(TimetableParser.parse(data: Data(json.utf8)).errorCode, "invalid_document")
        }
        for object in [NSNull(), [Any](), "text", 1, true] as [Any] {
            XCTAssertEqual(TimetableParser.parse(object: object).errorCode, "invalid_document")
        }
    }

    func testCodePointIDsAndDuplicateIDs() throws {
        let first = pattern(id: "\u{E9}", start: "19:00", end: "20:00")
        let second = pattern(id: "e\u{301}", start: "20:00", end: "21:00")
        _ = try validTimetable(document(patterns: [first, second]))
        var duplicate = second
        duplicate["id"] = "\u{E9}"
        XCTAssertEqual(TimetableParser.parse(object: document(patterns: [first, duplicate])).errorCode, "pattern_id")
        // Distinct IDs still participate in overlap checking.
        var overlapping = second
        overlapping["start"] = "19:30"
        XCTAssertEqual(TimetableParser.parse(object: document(patterns: [first, overlapping])).errorCode, "window_overlap")
    }

    func testExactStringsAndWholeTime() {
        for text in ["Asia/Tokyo\n", "asia/tokyo", "Asia/Tokyo\u{301}"] {
            var object = document()
            object["timeZone"] = text
            XCTAssertEqual(TimetableParser.parse(object: object).errorCode, "time_zone")
        }
        for text in ["block\n", "Block", "bl\u{F3}ck", "blo\u{301}ck"] {
            var item = pattern()
            item["segments"] = [["kind": text, "minutes": 60]]
            XCTAssertEqual(TimetableParser.parse(object: document(patterns: [item])).errorCode, "segment_kind")
        }
        for time in ["19:00\n", "9:00", "24:00", "１９:００", "19:00\u{0}"] {
            XCTAssertEqual(TimetableParser.parse(object: document(patterns: [pattern(start: time)])).errorCode, "window_time")
        }
    }

    func testUnicodeScalarNameLength() {
        for (name, valid) in [(String(repeating: "😀", count: 40), true),
                              (String(repeating: "e\u{301}", count: 20), true),
                              (String(repeating: "e\u{301}", count: 20) + "x", false)] {
            var item = pattern()
            item["name"] = name
            XCTAssertEqual(TimetableParser.parse(object: document(patterns: [item])).valid, valid)
        }
    }
}
