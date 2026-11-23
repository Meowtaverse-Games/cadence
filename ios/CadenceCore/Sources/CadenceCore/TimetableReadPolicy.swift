public enum TimetableReadState {
    case missing, populated, unreadable
}

public enum TimetableReadAction: Equatable {
    case evaluate(hasTimetable: Bool)
    case preserve
}

public enum TimetableReadPolicy {
    public static func action(for state: TimetableReadState, isExtension: Bool) -> TimetableReadAction {
        switch state {
        case .unreadable where isExtension: return .preserve
        case .populated: return .evaluate(hasTimetable: true)
        default: return .evaluate(hasTimetable: false)
        }
    }
}
