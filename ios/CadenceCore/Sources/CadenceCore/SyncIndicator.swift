/// Presentation only; this value never drives synchronization decisions or writes.
public enum SyncIndicator: Equatable {
    case synced
    case pending
    case attention
    case inactive

    public static func resolve(configured: Bool, signedIn: Bool, hasError: Bool,
                               requiresChoice: Bool, progress: SyncIndicator) -> SyncIndicator {
        if hasError { return .attention }
        guard configured && signedIn else { return .inactive }
        if requiresChoice { return .attention }
        return progress
    }
}
