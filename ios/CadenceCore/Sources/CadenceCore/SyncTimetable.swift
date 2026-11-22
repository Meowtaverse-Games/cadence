import Foundation

/// Lossless editable content, independent of the expansion model and SDK timestamp types.
public struct SyncTimetable: Codable {
    public var schemaVersion = 1
    public var timeZone = "Asia/Tokyo"
    public var patterns: [SyncPattern] = []
    public init() {}

    public struct InvalidDocument: Error {
        public let code: String
    }

    public static func parse(object: [String: Any]) throws -> SyncTimetable {
        let result = TimetableParser.parse(object: object)
        guard result.valid else { throw InvalidDocument(code: result.errorCode ?? "invalid_document") }
        // Validation guarantees these types and bounds. Read NSNumber only after validation:
        // integral doubles survive, numeric booleans cannot pass, and strings are never normalized.
        // Select known fields explicitly so even unknown nested SDK objects are ignored.
        var document = SyncTimetable()
        document.schemaVersion = (object["schemaVersion"] as! NSNumber).intValue
        document.timeZone = object["timeZone"] as! String
        document.patterns = (object["patterns"] as! [[String: Any]]).map { value in
            var pattern = SyncPattern()
            pattern.id = value["id"] as! String
            pattern.name = value["name"] as! String
            pattern.enabled = (value["enabled"] as! NSNumber).boolValue
            pattern.weekdays = (value["weekdays"] as! [NSNumber]).map(\.intValue)
            pattern.start = value["start"] as! String
            pattern.end = value["end"] as! String
            pattern.segments = (value["segments"] as! [[String: Any]]).map {
                SyncSegment(kind: $0["kind"] as! String, minutes: ($0["minutes"] as! NSNumber).intValue)
            }
            return pattern
        }
        return document
    }

    /// Exactly the three content keys; the app adds the SDK serverTimestamp for updatedAt.
    public var fields: [String: Any] {
        ["schemaVersion": schemaVersion, "timeZone": timeZone, "patterns": patterns.map { pattern -> [String: Any] in
            ["id": pattern.id, "name": pattern.name, "enabled": pattern.enabled,
             "weekdays": pattern.weekdays, "start": pattern.start, "end": pattern.end,
             "segments": pattern.segments.map { ["kind": $0.kind, "minutes": $0.minutes] as [String: Any] }]
        }]
    }
}

public struct SyncPattern: Codable, Identifiable {
    public var id = UUID().uuidString
    public var name = "新しいパターン"
    public var enabled = true
    public var weekdays = [1, 2, 3, 4, 5]
    public var start = "19:00"
    public var end = "20:00"
    public var segments = [SyncSegment(kind: "block", minutes: 25), SyncSegment(kind: "free", minutes: 5)]
    public init() {}
}

public struct SyncSegment: Codable {
    public var kind: String
    public var minutes: Int
    public init(kind: String, minutes: Int) { self.kind = kind; self.minutes = minutes }
}
