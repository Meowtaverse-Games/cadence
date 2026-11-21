import Foundation
import XCTest
import CadenceCore

final class FixtureTests: XCTestCase {
    func testAllSharedFixtures() throws {
        let repository = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
        let directory = repository.appendingPathComponent("spec/fixtures")
        let files = try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "json" }.sorted { $0.lastPathComponent < $1.lastPathComponent }
        XCTAssertFalse(files.isEmpty)
        for file in files {
            try XCTContext.runActivity(named: file.deletingPathExtension().lastPathComponent) { _ in
                let fixture = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: file)) as? [String: Any])
                XCTAssertEqual(fixture["name"] as? String, file.deletingPathExtension().lastPathComponent)
                let object = try XCTUnwrap(fixture["timetable"])
                // Exercise both entry points for every fixture.
                let data = try JSONSerialization.data(withJSONObject: object, options: [.fragmentsAllowed])
                let expected = try XCTUnwrap(fixture["expected"] as? [String: Any])
                let expectedValid = try XCTUnwrap(expected["valid"] as? Bool)
                for result in [TimetableParser.parse(data: data), TimetableParser.parse(object: object)] {
                    XCTAssertEqual(result.valid, expectedValid, file.lastPathComponent)
                    if !expectedValid {
                        XCTAssertNil(result.timetable)
                        XCTAssertEqual(result.errorCode, expected["error"] as? String, file.lastPathComponent)
                        continue
                    }
                    XCTAssertNil(result.errorCode)
                    let timetable = try XCTUnwrap(result.timetable)
                    let from = instant(try XCTUnwrap(fixture["from"] as? String))
                    let to = instant(try XCTUnwrap(fixture["to"] as? String))
                    let intervals = try TimetableExpander.expand(timetable, from: from, to: to)
                    let actual = intervals.map { ["start": utcText($0.start), "end": utcText($0.end)] }
                    XCTAssertEqual(actual, expected["intervals"] as? [[String: String]], file.lastPathComponent)
                }
            }
        }
        print("Shared fixtures: \(files.count) (both data and object APIs)")
    }
}
