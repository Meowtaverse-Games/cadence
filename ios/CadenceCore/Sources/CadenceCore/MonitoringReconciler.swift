/// Framework-independent reconciliation; reads one snapshot and stops before starting.
public protocol MonitoringClient {
    var activities: [String] { get }
    func stopAll()
    func stop(_ names: [String])
    func start(_ name: String)
}

public enum MonitoringReconciler {
    public enum Operation: Equatable {
        case stopAll
        case stop([String])
        case start(String)
    }

    public static func operations(current: Set<String>, desired: [String], reset: Bool) -> [Operation] {
        var result: [Operation] = []
        let obsolete = current.subtracting(desired).sorted()
        if reset { result.append(.stopAll) }
        else if !obsolete.isEmpty { result.append(.stop(obsolete)) }
        var retained = reset ? Set<String>() : current
        for name in desired where retained.insert(name).inserted {
            result.append(.start(name))
        }
        return result
    }

    public static func reconcile<Client: MonitoringClient>(_ client: Client, desired: [String], reset: Bool) {
        let current = Set(client.activities)
        for operation in operations(current: current, desired: desired, reset: reset) {
            switch operation {
            case .stopAll: client.stopAll()
            case .stop(let names): client.stop(names)
            case .start(let name): client.start(name)
            }
        }
    }
}
