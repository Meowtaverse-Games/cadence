import Foundation

public enum SyncRemoteDocument: Equatable {
    case missing
    case invalid(String)
    case valid
}

public enum SyncAction: Equatable {
    case keep
    case adopt
    case upload
    case askUser
    case markLinked
}

public enum SyncNotice: Equatable {
    case waitingForServer
    case pendingWrites
    case missing
    case invalid(String)
    case conflict
}

public struct SyncDecision: Equatable {
    public let action: SyncAction
    public let notice: SyncNotice?
    public let confirmSync: Bool

    public init(_ action: SyncAction, notice: SyncNotice? = nil, confirmSync: Bool = false) {
        self.action = action
        self.notice = notice
        self.confirmSync = confirmSync
    }
}

/// No clocks, persistence or Firebase types: the caller executes effects only after this decision.
public enum TimetableSyncPolicy {
    public static func decide(linked: Bool, fromServer: Bool, pendingWrites: Bool,
                              remote: SyncRemoteDocument, hasLocal: Bool, same: Bool,
                              initialUploadFailed: Bool = false) -> SyncDecision {
        if !linked && !fromServer { return SyncDecision(.keep, notice: .waitingForServer) }
        // Local echoes cannot confirm an initial upload or roll back a newer local edit.
        if pendingWrites { return SyncDecision(.keep, notice: .pendingWrites) }
        switch remote {
        case .missing:
            if !fromServer { return SyncDecision(.keep) }
            if linked { return SyncDecision(.keep, notice: .missing) }
            if initialUploadFailed { return SyncDecision(.keep, notice: .missing) }
            return SyncDecision(hasLocal ? .upload : .markLinked)
        case .invalid(let code):
            return SyncDecision(.keep, notice: .invalid(code))
        case .valid:
            // After a rejected initial write, snapshots cannot silently resolve the retry UI.
            // The user may retry uploading or explicitly choose the latest server content.
            if !linked && initialUploadFailed { return SyncDecision(.askUser, notice: .conflict) }
            if hasLocal && same {
                return SyncDecision(linked ? .keep : .markLinked, confirmSync: fromServer)
            }
            if linked || !hasLocal {
                return SyncDecision(.adopt, confirmSync: fromServer)
            }
            return SyncDecision(.askUser, notice: .conflict)
        }
    }
}

/// Compare only the caller's content model (no updatedAt). JSON preserves scalar sequences and
/// array order, unlike String.==, which treats canonically equivalent Unicode as equal.
public enum TimetableContent {
    public static func same<T: Encodable>(_ lhs: T, _ rhs: T) throws -> Bool {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return try encoder.encode(lhs) == encoder.encode(rhs)
    }
}
