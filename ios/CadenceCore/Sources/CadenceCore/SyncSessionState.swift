import Foundation

/// Session-local recovery state. SDK completion and rollback snapshots may arrive in either order.
public struct SyncWriteRecovery {
    public struct Failure: Equatable {
        public let sequence: Int
        public let reason: String
        public let linking: Bool
        public fileprivate(set) var restoredServer: Bool
    }

    private struct Attempt {
        let linking: Bool
        var adoptedServer = false
    }

    public private(set) var initialUploadFailed = false
    public private(set) var failure: Failure?
    private var attempts: [Int: Attempt] = [:]
    private var latestSuccess = 0

    public init() {}

    public mutating func started(sequence: Int, linking: Bool) {
        attempts[sequence] = Attempt(linking: linking)
        // Keep the warning during a retry; starting a write does not mean it succeeded.
    }

    public mutating func failed(sequence: Int, reason: String) {
        guard let attempt = attempts.removeValue(forKey: sequence) else { return }
        if attempt.linking { initialUploadFailed = true }
        guard sequence > latestSuccess, sequence >= (failure?.sequence ?? 0) else { return }
        failure = Failure(sequence: sequence, reason: reason, linking: attempt.linking,
                          restoredServer: !attempt.linking && attempt.adoptedServer)
    }

    public mutating func succeeded(sequence: Int) {
        attempts.removeValue(forKey: sequence)
        latestSuccess = max(latestSuccess, sequence)
        if let failure, sequence > failure.sequence { self.failure = nil }
        // Once an initial upload failed, automatic retries stay disabled for this session,
        // even if the explicit retry succeeded. The linked policy no longer uses this flag.
    }

    public mutating func adoptedServer() {
        for sequence in Array(attempts.keys) where attempts[sequence]?.linking == false {
            attempts[sequence]?.adoptedServer = true
        }
        if failure?.linking == false { failure?.restoredServer = true }
    }

    public mutating func dismissFailure() {
        // Initial failure must remain visible with the manual retry until explicitly resolved.
        if failure?.linking == false { failure = nil }
    }

    public mutating func choseServer() {
        if failure?.linking == true { failure = nil }
    }
}

/// Only adjacent duplicate keep decisions are quiet; meaningful actions always remain visible.
public struct SyncSnapshotLogGate {
    private struct Key: Equatable {
        let fromServer: Bool
        let pendingWrites: Bool
        let remote: SyncRemoteDocument
        let same: Bool
        let action: SyncAction
    }
    private var lastRecorded: Key?

    public init() {}

    public mutating func shouldRecord(fromServer: Bool, pendingWrites: Bool,
                                     remote: SyncRemoteDocument, same: Bool, action: SyncAction) -> Bool {
        let key = Key(fromServer: fromServer, pendingWrites: pendingWrites, remote: remote,
                      same: remote == .valid && same, action: action)
        guard action != .keep || key != lastRecorded else { return false }
        lastRecorded = key
        return true
    }
}
