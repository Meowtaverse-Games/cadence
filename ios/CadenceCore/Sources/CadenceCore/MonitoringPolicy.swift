import Foundation

/// Runtime decisions that don't depend on Screen Time framework state or the system clock.
public enum MonitoringPolicy {
    public static func permitsMonitoring(isApp: Bool, authorized: Bool) -> Bool {
        // Extensions must not tear down monitoring solely because their authorization snapshot is stale.
        !isApp || authorized
    }

    public static func needsTimeZoneReset(registered: String?, current: String) -> Bool {
        registered != current
    }
}
