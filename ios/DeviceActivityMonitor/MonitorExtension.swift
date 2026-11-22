import DeviceActivity
import CadenceCore

final class MonitorExtension: DeviceActivityMonitor {
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
        ScreenTimeCoordinator(category: "monitor").refresh(
            event: watchdog + (didStart ? "intervalDidStart" : "intervalDidEnd"),
            activity: activity, didStart: didStart)
    }
}
