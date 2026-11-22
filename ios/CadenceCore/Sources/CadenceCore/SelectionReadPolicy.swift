public enum SelectionReadState {
    case missing, empty, populated, unreadable
}

public enum SelectionReadAction: Equatable {
    case evaluate(hasSelection: Bool)
    case preserve
}

public enum SelectionReadPolicy {
    public static func action(for state: SelectionReadState, isExtension: Bool) -> SelectionReadAction {
        switch state {
        case .unreadable where isExtension: return .preserve
        case .populated: return .evaluate(hasSelection: true)
        default: return .evaluate(hasSelection: false)
        }
    }
}
