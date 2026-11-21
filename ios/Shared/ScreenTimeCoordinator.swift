import Foundation
import CadenceCore
import DeviceActivity
import FamilyControls
import ManagedSettings

struct ScreenTimeCoordinator {
    let category: String
    private var files: GroupStore { GroupStore(category: category) }
    private let center = DeviceActivityCenter()
    private let settings = ManagedSettingsStore(named: .init("cadence"))

    // Change only for the documented device experiment; default follows Calendar.current.
    static let useExplicitTokyoTimeZone = false
    private func components(_ date: Date) -> DateComponents {
        var calendar = Calendar.current
        if Self.useExplicitTokyoTimeZone { calendar.timeZone = TimeZone(secondsFromGMT: 9 * 3600)! }
        var value = ActivityPlanner.components(for: date, calendar: calendar)
        if Self.useExplicitTokyoTimeZone { value.timeZone = calendar.timeZone }
        return value
    }

    func refresh(event: String, activity: DeviceActivityName? = nil, didStart: Bool = false, reset: Bool = false) {
        let received = Date()
        do {
            try files.locked("runtime") {
                let timetable = files.timetable()?.1
                let selection = files.selection()
                let start = activity.flatMap { ActivityPlanner.start(for: $0.rawValue) }
                let evaluation = ActivityPlanner.evaluationTime(now: received, intervalDidStart: didStart, activityStart: start)
                let authorized = AuthorizationCenter.shared.authorizationStatus == .approved
                let blocked = authorized && selection != nil && ActivityPlanner.isBlocked(timetable, at: evaluation)
                // Always apply the desired state, even if monitoring fails or a callback repeats.
                let applications = blocked ? selection?.applicationTokens : nil
                let domains = blocked ? selection?.webDomainTokens : nil
                let categories: ShieldSettings.ActivityCategoryPolicy<Application>? = blocked
                    ? .specific(selection?.categoryTokens ?? []) : nil
                let unchanged = settings.shield.applications == applications && settings.shield.webDomains == domains
                    && settings.shield.applicationCategories == categories
                settings.shield.applications = applications
                settings.shield.applicationCategories = categories
                settings.shield.webDomains = domains

                var end: Date?
                if let timetable, let start {
                    end = try TimetableExpander.expand(timetable, from: start, to: start.addingTimeInterval(1))
                        .first(where: { $0.start == start })?.end
                }
                let scheduled = activity.flatMap { center.schedule(for: $0) }
                let scheduleText = start.map(GroupStore.timestamp) ?? scheduled.map { String(describing: $0.intervalStart) } ?? "none"
                let endText = end.map(GroupStore.timestamp) ?? scheduled.map { String(describing: $0.intervalEnd) } ?? "none"
                files.record("event=\(event) activity=\(activity?.rawValue ?? "none") scheduledStart=\(scheduleText) scheduledEnd=\(endText) received=\(GroupStore.timestamp(received)) evaluation=\(GroupStore.timestamp(evaluation)) decision=\(unchanged ? "変えない" : blocked ? "かけた" : "外した") blocked=\(blocked) authorized=\(authorized) selectionCounts=\(selection?.applicationTokens.count ?? 0)/\(selection?.categoryTokens.count ?? 0)/\(selection?.webDomainTokens.count ?? 0)")

                if reset { center.stopMonitoring() }
                var errors: [String] = []
                let plan = try timetable.map { try ActivityPlanner.plan($0, now: evaluation) } ?? []
                let canMonitor = authorized && timetable != nil && selection != nil
                let desired = canMonitor ? Set(plan.map { DeviceActivityName($0.name) } + [DeviceActivityName(ActivityPlanner.watchdogName)]) : []
                let obsolete = center.activities.filter { !desired.contains($0) }
                if !obsolete.isEmpty { center.stopMonitoring(obsolete) }

                func register(_ name: DeviceActivityName, _ schedule: DeviceActivitySchedule) {
                    guard !center.activities.contains(name) else { return }
                    do {
                        try center.startMonitoring(name, during: schedule)
                    } catch {
                        let reason = "\(name.rawValue): \(GroupStore.reason(error))"
                        errors.append(reason)
                        files.record("startMonitoringFailure activity=\(name.rawValue) reason=\(GroupStore.reason(error))")
                    }
                }
                if canMonitor {
                    register(DeviceActivityName(ActivityPlanner.watchdogName), DeviceActivitySchedule(
                        intervalStart: DateComponents(hour: 0, minute: 0),
                        intervalEnd: DateComponents(hour: 23, minute: 59), repeats: true))
                    for item in plan {
                        register(DeviceActivityName(item.name), DeviceActivitySchedule(
                            intervalStart: components(item.interval.start), intervalEnd: components(item.interval.end), repeats: false))
                    }
                }
                try files.saveErrors(errors)
                let names = center.activities.map(\.rawValue).sorted()
                files.record("registered count=\(names.count) names=\(names.joined(separator: ",")) plan=\(plan.map { "\($0.name)[\(GroupStore.timestamp($0.interval.start)),\(GroupStore.timestamp($0.interval.end)))" }.joined(separator: ","))")
            }
        } catch { files.record("refreshFailure event=\(event) reason=\(GroupStore.reason(error))") }
    }
}
