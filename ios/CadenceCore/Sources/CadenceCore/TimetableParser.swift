import Foundation

public enum TimetableParser {
    /// Invalid JSON (including unpaired surrogate escapes) never escapes as an error.
    /// Fragments are decoded so valid JSON with a non-object root is invalid_document.
    public static func parse(data: Data) -> ValidationResult {
        do {
            return parse(object: try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]))
        } catch {
            return invalid("invalid_json")
        }
    }

    /// Accepts Foundation/Swift object trees, ignoring unknown fields without serializing them.
    public static func parse(object: Any) -> ValidationResult {
        guard let root = object as? [String: Any] else { return invalid("invalid_document") }
        guard integer(root["schemaVersion"], in: 1...1) != nil else { return invalid("schema_version") }
        guard textEquals(root["timeZone"], "Asia/Tokyo") else { return invalid("time_zone") }
        guard let items = root["patterns"] as? [Any], items.count <= 20 else { return invalid("patterns") }

        var patterns: [Pattern] = []
        // Swift String equality normalizes canonically equivalent sequences; scalar arrays do not.
        var ids = Set<[UInt32]>()
        for item in items {
            guard let item = item as? [String: Any] else { return invalid("patterns") }
            guard let id = item["id"] as? String, !id.isEmpty,
                  ids.insert(id.unicodeScalars.map(\.value)).inserted else { return invalid("pattern_id") }
            guard let name = item["name"] as? String,
                  (1...40).contains(name.unicodeScalars.count) else { return invalid("pattern_name") }
            guard let enabled = boolean(item["enabled"]) else { return invalid("enabled") }
            guard let days = item["weekdays"] as? [Any], !days.isEmpty else { return invalid("weekdays") }
            var weekdays = Set<Int>()
            for day in days {
                guard let day = integer(day, in: 1...7), weekdays.insert(day).inserted else {
                    return invalid("weekdays")
                }
            }
            guard let start = minute(item["start"]), let end = minute(item["end"]) else {
                return invalid("window_time")
            }
            guard let values = item["segments"] as? [Any], (1...20).contains(values.count) else {
                return invalid("segments")
            }
            var segments: [Segment] = []
            for value in values {
                guard let value = value as? [String: Any] else { return invalid("segments") }
                let isBlock = textEquals(value["kind"], "block")
                guard isBlock || textEquals(value["kind"], "free") else { return invalid("segment_kind") }
                guard let minutes = integer(value["minutes"], in: 1...720) else {
                    return invalid("segment_minutes")
                }
                guard !isBlock || minutes >= 15 else { return invalid("block_minutes") }
                segments.append(Segment(isBlock: isBlock, minutes: minutes))
            }
            guard !enabled || segments.contains(where: \.isBlock) else { return invalid("missing_block") }
            patterns.append(Pattern(enabled: enabled, weekdays: weekdays, startMinute: start,
                                    durationMinutes: end > start ? end - start : end - start + 1440,
                                    segments: segments))
        }
        guard !overlaps(patterns) else { return invalid("window_overlap") }
        return ValidationResult(timetable: Timetable(patterns: patterns), errorCode: nil)
    }

    private static func boolean(_ value: Any?) -> Bool? {
        guard let number = value as? NSNumber,
              CFGetTypeID(number) == CFBooleanGetTypeID() else { return nil }
        return number.boolValue
    }

    private static func integer(_ value: Any?, in range: ClosedRange<Int>) -> Int? {
        guard let number = value as? NSNumber,
              CFGetTypeID(number) != CFBooleanGetTypeID() else { return nil }
        let value = number.doubleValue
        // Check bounds before conversion: huge, infinite and NaN inputs cannot trap.
        guard value.isFinite, value.rounded(.towardZero) == value,
              value >= Double(range.lowerBound), value <= Double(range.upperBound) else { return nil }
        return Int(value)
    }

    private static func textEquals(_ value: Any?, _ expected: String) -> Bool {
        guard let value = value as? String else { return false }
        return value.unicodeScalars.elementsEqual(expected.unicodeScalars)
    }

    private static func minute(_ value: Any?) -> Int? {
        guard let text = value as? String else { return nil }
        let bytes = Array(text.utf8)
        guard bytes.count == 5, bytes[2] == 58,
              (48...50).contains(bytes[0]), (48...57).contains(bytes[1]),
              (48...53).contains(bytes[3]), (48...57).contains(bytes[4]) else { return nil }
        let hour = Int(bytes[0] - 48) * 10 + Int(bytes[1] - 48)
        guard hour < 24 else { return nil }
        return hour * 60 + Int(bytes[3] - 48) * 10 + Int(bytes[4] - 48)
    }

    private static func overlaps(_ patterns: [Pattern]) -> Bool {
        let week = 7 * 1440
        var windows: [(owner: Int, start: Int, end: Int)] = []
        for (owner, pattern) in patterns.enumerated() where pattern.enabled {
            for day in pattern.weekdays {
                let start = (day - 1) * 1440 + pattern.startMinute
                windows.append((owner, start, start + pattern.durationMinutes))
            }
        }
        for i in windows.indices {
            for j in windows.indices where j > i && windows[i].owner != windows[j].owner {
                for shift in [-week, 0, week] {
                    if windows[i].start < windows[j].end + shift && windows[j].start + shift < windows[i].end {
                        return true
                    }
                }
            }
        }
        return false
    }

    private static func invalid(_ code: String) -> ValidationResult {
        ValidationResult(timetable: nil, errorCode: code)
    }
}
