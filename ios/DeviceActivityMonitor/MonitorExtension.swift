import Foundation
import os
import DeviceActivity
import CadenceCore

final class MonitorExtension: DeviceActivityMonitor {
    private static let diagnostics = DispatchQueue(label: "cadence.monitor.diagnostics", qos: .utility)

    // Shared across monitor instances. A burst retains only its latest callback context.
    private static let reconciliations = DeferredReconciliationQueue<RefreshContext> { @Sendable context, count in
        ScreenTimeCoordinator(category: "monitor").reconcile(context, coalescedCount: count)
    }

    override func intervalDidStart(for activity: DeviceActivityName) {
        super.intervalDidStart(for: activity)
        update(activity, didStart: true)
    }

    override func intervalDidEnd(for activity: DeviceActivityName) {
        super.intervalDidEnd(for: activity)
        update(activity, didStart: false)
    }

    private func update(_ activity: DeviceActivityName, didStart: Bool) {
        let watchdog = activity.rawValue == ActivityPlanner.watchdogName ? "見張り/" : ""
        let context = RefreshContext(event: watchdog + (didStart ? "intervalDidStart" : "intervalDidEnd"),
            activityName: activity.rawValue, didStart: didStart)
        let message = ScreenTimeCoordinator(category: "monitor").applyShield(context)
        let line = GroupStore(category: "monitor").formattedRecord(message)
        Logger(subsystem: "com.meowtaverse-games.cadence", category: "monitor")
            .notice("\(line, privacy: .public)")
        // Diagnostic file locks must not delay callback return or wait behind a stalled reconciliation.
        Self.diagnostics.async { @Sendable in
            GroupStore(category: "monitor").recordLine(line, systemLogAlreadyEmitted: true)
        }
        // Do not wait for reconciliation or for the OS daemon from this callback.
        Self.reconciliations.request(context)
    }
}
