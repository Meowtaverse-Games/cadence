import Foundation
import CadenceCore
import DeviceActivity
import FamilyControls
import ManagedSettings

struct ScreenTimeCoordinator {
    let category: String
    private let center = DeviceActivityCenter()
    private let settings = ManagedSettingsStore(named: .init("cadence"))

    // Change only for the documented device experiment; default follows Calendar.current.
    static let useExplicitTokyoTimeZone = false
    private func components(_ date: Date) -> DateComponents {
        var calendar = Calendar.current
        if Self.useExplicitTokyoTimeZone { calendar.timeZone = TimeZone(identifier: "Asia/Tokyo")! }
        var value = ActivityPlanner.components(for: date, calendar: calendar)
        if Self.useExplicitTokyoTimeZone { value.timeZone = calendar.timeZone }
        return value
    }

    func refresh(event: String, activity: DeviceActivityName? = nil, didStart: Bool = false, reset: Bool = false) {
        let received = Date()
        var diagnostics: [String] = []
        let files = GroupStore(category: category, diagnostics: { diagnostics.append($0) })
        defer { GroupStore(category: category).record(diagnostics.joined(separator: " | ")) }
        // GroupStore owns all bounded write locks. Every read and framework call here is unlocked.
        // A failed diagnostic write must never skip shield application or monitoring reconciliation.
        func persist(_ file: String, _ write: () throws -> Void) {
            do { try write() }
            catch { files.record("statusWriteFailure file=\(file) reason=\(GroupStore.reason(error))") }
        }
        do {
            let timetable = files.timetable()?.1
            let selection = files.selection()
            let start = activity.flatMap { ActivityPlanner.start(for: $0.rawValue) }
            let evaluation = ActivityPlanner.evaluationTime(now: received, intervalDidStart: didStart, activityStart: start)
            let authorization = AuthorizationCenter.shared.authorizationStatus
            let authorized = authorization.isApprovedForCadence
            let permitted = MonitoringPolicy.permitsMonitoring(isApp: category == "app", authorized: authorized)
            let authorizationAction = permitted ? "evaluateTimetable" : "clearShieldAndStopMonitoring"
            let hasSelection = selection.map {
                !$0.applicationTokens.isEmpty || !$0.categoryTokens.isEmpty || !$0.webDomainTokens.isEmpty
            } ?? false
            let blocked = permitted && hasSelection && ActivityPlanner.isBlocked(timetable, at: evaluation)
            // Always apply the desired state, even if monitoring fails or a callback repeats.
            let applications = blocked ? selection?.applicationTokens : nil
            let domains = blocked ? selection?.webDomainTokens : nil
            let categories: ShieldSettings.ActivityCategoryPolicy<Application>? = blocked
                ? .specific(selection?.categoryTokens ?? []) : nil
            let webCategories: ShieldSettings.ActivityCategoryPolicy<WebDomain>? = blocked
                ? .specific(selection?.categoryTokens ?? []) : nil
            let unchanged = settings.shield.applications == applications && settings.shield.webDomains == domains
                && settings.shield.applicationCategories == categories && settings.shield.webDomainCategories == webCategories
            settings.shield.applications = applications
            settings.shield.applicationCategories = categories
            settings.shield.webDomains = domains
            settings.shield.webDomainCategories = webCategories

            var history = files.activityHistory()
            var eventInterval = activity.flatMap { history[$0.rawValue] }
            if activity?.rawValue == ActivityPlanner.watchdogName {
                let midnight = Calendar.current.startOfDay(for: received)
                let end = Calendar.current.date(bySettingHour: 23, minute: 59, second: 0, of: received)!
                eventInterval = ActivityRecord(start: midnight, end: end)
            }
            let scheduleText = eventInterval.map { GroupStore.timestamp($0.start) } ?? start.map(GroupStore.timestamp) ?? "none"
            let endText = eventInterval.map { GroupStore.timestamp($0.end) } ?? "none"
            files.record("event=\(event) activity=\(activity?.rawValue ?? "none") scheduledStart=\(scheduleText) scheduledEnd=\(endText) received=\(GroupStore.timestamp(received)) evaluation=\(GroupStore.timestamp(evaluation)) decision=\(unchanged ? "変えない" : blocked ? "かけた" : "外した") blocked=\(blocked) authorized=\(authorized) authorizationStatus=\(String(describing: authorization)) authorizationAction=\(authorizationAction) selectionCounts=\(selection?.applicationTokens.count ?? 0)/\(selection?.categoryTokens.count ?? 0)/\(selection?.webDomainTokens.count ?? 0)")

            var errors: [String] = []
            let plan = try timetable.map { try ActivityPlanner.plan($0, now: evaluation) } ?? []
            let canMonitor = permitted && timetable != nil && hasSelection
            let desired = canMonitor ? Set(plan.map { DeviceActivityName($0.name) } + [DeviceActivityName(ActivityPlanner.watchdogName)]) : []
            for item in plan { history[item.name] = ActivityRecord(start: item.interval.start, end: item.interval.end) }
            persist("activity-history.json") { try files.saveHistory(history) }
            let registeredZone = files.registrationTimeZone()
            let currentZone = TimeZone.current.identifier
            let zoneChanged = MonitoringPolicy.needsTimeZoneReset(registered: registeredZone, current: currentZone)
            if reset || zoneChanged {
                persist("registration-time-zone.json") { try files.saveRegistrationTimeZone(currentZone) }
                center.stopMonitoring()
            }
            if zoneChanged {
                files.record("timeZoneReset previous=\(registeredZone ?? "none") current=\(currentZone) action=stopAllAndRegister")
            }
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
            persist("registration-errors.json") { try files.saveErrors(errors) }
            let names = center.activities.map(\.rawValue).sorted()
            let blockNames = names.filter { $0 != ActivityPlanner.watchdogName }
            files.record("registered count=\(names.count) first=\(blockNames.first ?? "none") last=\(blockNames.last ?? "none") watchdog=\(names.contains(ActivityPlanner.watchdogName)) plannedCount=\(plan.count) plannedLastEnd=\(plan.last.map { GroupStore.timestamp($0.interval.end) } ?? "none")")
        } catch {
            let reason = "更新失敗: \(GroupStore.reason(error))"
            files.record("refreshFailure event=\(event) reason=\(GroupStore.reason(error))")
            persist("registration-errors.json") { try files.saveErrors([reason]) }
        }
    }
}
