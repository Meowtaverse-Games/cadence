import Foundation
import FamilyControls
import CadenceCore
import os
import Darwin

struct ActivityRecord: Codable {
    let start: Date
    let end: Date
}

/// Atomic replacement protects readers; separate flock files serialize cross-process writers.
struct GroupStore {
    static let group = "group.com.meowtaverse-games.cadence"
    let category: String
    var diagnostics: ((String) -> Void)? = nil

    private func url(_ name: String) throws -> URL {
        guard let root = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: Self.group) else {
            throw CocoaError(.fileNoSuchFile)
        }
        return root.appendingPathComponent(name)
    }

    func locked<T>(_ name: String, _ body: () throws -> T) throws -> T {
        let path = try url(name + ".lock").path
        let fd = open(path, O_CREAT | O_RDWR, S_IRUSR | S_IWUSR)
        guard fd >= 0 else { throw CocoaError(.fileWriteUnknown) }
        defer { close(fd) }
        guard flock(fd, LOCK_EX) == 0 else { throw CocoaError(.fileLocking) }
        defer { flock(fd, LOCK_UN) }
        return try body()
    }

    private func write<T: Encodable>(_ value: T, file: String) throws {
        let data = try JSONEncoder().encode(value)
        try data.write(to: url(file), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    private func read<T: Decodable>(_ file: String, as type: T.Type) -> T? {
        do {
            let location = try url(file)
            guard FileManager.default.fileExists(atPath: location.path) else { return nil }
            return try JSONDecoder().decode(type, from: Data(contentsOf: location))
        } catch {
            record("readFailure file=\(file) reason=\(Self.reason(error)); treatedAsMissing=true")
            return nil
        }
    }

    func timetable() -> (LocalTimetable, Timetable)? {
        guard let document = read("timetable.json", as: LocalTimetable.self),
              let data = try? JSONEncoder().encode(document) else { return nil }
        let result = TimetableParser.parse(data: data)
        guard let timetable = result.timetable else {
            record("readFailure file=timetable.json reason=\(result.errorCode ?? "unknown"); treatedAsMissing=true")
            return nil
        }
        return (document, timetable)
    }

    func save(_ timetable: LocalTimetable) throws {
        let data = try JSONEncoder().encode(timetable)
        let result = TimetableParser.parse(data: data)
        guard result.valid else { throw ValidationFailure(code: result.errorCode ?? "invalid_document") }
        try locked("runtime") { try write(timetable, file: "timetable.json") }
    }

    func selection() -> FamilyActivitySelection? { read("selection.json", as: FamilyActivitySelection.self) }
    func save(_ selection: FamilyActivitySelection) throws {
        try locked("runtime") { try write(selection, file: "selection.json") }
    }

    // Diagnostic history only, never used as the source of scheduling or shield decisions.
    func activityHistory() -> [String: ActivityRecord] {
        read("activity-history.json", as: [String: ActivityRecord].self) ?? [:]
    }

    func saveHistory(_ history: [String: ActivityRecord]) throws {
        let recent = history.sorted { $0.value.start > $1.value.start }.prefix(100)
        try write(Dictionary(uniqueKeysWithValues: recent.map { ($0.key, $0.value) }), file: "activity-history.json")
    }

    func registrationTimeZone() -> String? { read("registration-time-zone.json", as: String.self) }
    // Persist before stopMonitoring, so callbacks caused by the reset don't repeat it.
    func saveRegistrationTimeZone(_ identifier: String) throws { try write(identifier, file: "registration-time-zone.json") }

    func registrationErrors() -> [String] { read("registration-errors.json", as: [String].self) ?? [] }
    // Called under the runtime lock.
    func saveErrors(_ errors: [String]) throws { try write(errors, file: "registration-errors.json") }

    static func timestamp(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = .current
        formatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSSxxx"
        return formatter.string(from: date)
    }

    static func reason(_ error: Error) -> String {
        let error = error as NSError
        // Do not log userInfo: framework errors may contain selection tokens or personal paths.
        return "\(error.domain)(\(error.code))"
    }

    private func rawLogs() throws -> [String] {
        let location = try url("events.json")
        guard FileManager.default.fileExists(atPath: location.path) else { return [] }
        return try JSONDecoder().decode([String].self, from: Data(contentsOf: location))
    }

    func record(_ message: String) {
        if let diagnostics { diagnostics(message); return }
        let bounded = String(message.prefix(4096))
        let line = "\(Self.timestamp(Date())) [\(category)] timezone=\(TimeZone.current.identifier) \(bounded)"
        let logger = Logger(subsystem: "com.meowtaverse-games.cadence", category: category)
        logger.notice("\(line, privacy: .public)")
        do {
            try locked("events") {
                var entries: [String]
                do { entries = try rawLogs() } catch {
                    let failure = "\(Self.timestamp(Date())) [\(category)] timezone=\(TimeZone.current.identifier) readFailure file=events.json reason=\(Self.reason(error)); treatedAsMissing=true"
                    logger.error("\(failure, privacy: .public)")
                    entries = [failure]
                }
                entries.append(line)
                try write(Array(entries.suffix(500)), file: "events.json")
            }
        } catch {
            logger.error("Log persistence failed: \(Self.reason(error), privacy: .public)")
        }
    }

    func logs() -> [String] {
        do { return try locked("events") { try rawLogs().reversed() } }
        catch { record("readFailure file=events.json reason=\(Self.reason(error))"); return [] }
    }

    func clearLogs() throws { try locked("events") { try write([String](), file: "events.json") } }
}
