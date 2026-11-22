import Foundation
import XCTest
import CadenceCore

final class SyncTimetableTests: XCTestCase {
    func testSDKTreeRoundTripAndIntegerWriting() throws {
        final class SyntheticTimestamp {}
        var item = pattern(id: "e\u{301}", enabled: NSNumber(value: true))
        item["name"] = "e\u{301}"
        item["weekdays"] = [NSNumber(value: 1.0)]
        item["segments"] = [["kind": "free", "minutes": NSNumber(value: 10.0)],
                            ["kind": "block", "minutes": NSNumber(value: 20.0)]]
        item["extra"] = SyntheticTimestamp()
        var tree = document(patterns: [item])
        tree["schemaVersion"] = NSNumber(value: 1.0)
        tree["updatedAt"] = SyntheticTimestamp()
        XCTAssertTrue(TimetableParser.parse(object: tree).valid)
        let parsed = try SyncTimetable.parse(object: tree)
        XCTAssertEqual(parsed.patterns[0].segments[0].minutes, 10)
        XCTAssertEqual(Array(parsed.patterns[0].id.unicodeScalars), Array("e\u{301}".unicodeScalars))
        XCTAssertEqual(Array(parsed.patterns[0].name.unicodeScalars), Array("e\u{301}".unicodeScalars))
        XCTAssertEqual(Set(parsed.fields.keys), Set(["schemaVersion", "timeZone", "patterns"]))
        XCTAssertTrue(type(of: parsed.fields["schemaVersion"]!) == Int.self)
        let writtenPattern = (parsed.fields["patterns"] as! [[String: Any]])[0]
        let segment = (writtenPattern["segments"] as! [[String: Any]])[0]
        XCTAssertTrue(type(of: segment["minutes"]!) == Int.self)
        XCTAssertTrue(try TimetableContent.same(parsed, SyncTimetable.parse(object: parsed.fields)))
        item["enabled"] = NSNumber(value: 1)
        tree["patterns"] = [item]
        XCTAssertEqual(TimetableParser.parse(object: tree).errorCode, "enabled")
        XCTAssertThrowsError(try SyncTimetable.parse(object: tree))
    }

    func testActualEditableContentComparison() throws {
        let original = try SyncTimetable.parse(object: document(patterns: [pattern(id: "\u{e9}")]))
        var changed = original
        changed.patterns[0].id = "e\u{301}"
        XCTAssertFalse(try TimetableContent.same(original, changed))
        changed = original
        changed.patterns[0].name = "\u{e9}"
        var decomposed = changed
        decomposed.patterns[0].name = "e\u{301}"
        XCTAssertFalse(try TimetableContent.same(changed, decomposed))
        changed = original
        changed.patterns[0].weekdays.reverse()
        XCTAssertFalse(try TimetableContent.same(original, changed))
        changed = original
        changed.patterns[0].enabled.toggle()
        XCTAssertFalse(try TimetableContent.same(original, changed))
    }
}
