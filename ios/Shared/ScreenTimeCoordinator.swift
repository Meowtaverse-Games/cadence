import Foundation
import CadenceCore
import DeviceActivity
import FamilyControls
import ManagedSettings

struct RefreshContext: Sendable {
    let event: String
    let activityName: String?
    let didStart: Bool
    let received: Date
    private let started: UInt64

    init(event: String, activityName: String?, didStart: Bool, batch: RefreshBatch = RefreshBatch()) {
        self.event = event
        self.activityName = activityName
        self.didStart = didStart
        received = batch.received
        started = batch.startedUptimeNanoseconds
    }

    var elapsedMilliseconds: UInt64 { (DispatchTime.now().uptimeNanoseconds - started) / 1_000_000 }
    var correlation: String {
        "event=\(event) activity=\(activityName ?? "none") received=\(GroupStore.timestamp(received))"
    }
}

struct ScreenTimeCoordinator {
    let category: String

    // Change only for the documented device experiment; default follows Calendar.current.
    static let useExplicitTokyoTimeZone = false
    private func components(_ date: Date) -> DateComponents {
        var calendar = Calendar.current
        if Self.useExplicitTokyoTimeZone { calendar.timeZone = TimeZone(identifier: "Asia/Tokyo")! }
        var value = ActivityPlanner.components(for: date, calendar: calendar)
        if Self.useExplicitTokyoTimeZone { value.timeZone = calendar.timeZone }
        return value
    }

    @discardableResult
    func refresh(event: String, reset: Bool = false, batch: RefreshBatch = RefreshBatch()) -> Int? {
        let context = RefreshContext(event: event, activityName: nil, didStart: false, batch: batch)
        GroupStore(category: category).record(applyShield(context) + " coalescedCount=\(batch.count)")
        return reconcile(context, reset: reset, coalescedCount: batch.count)
    }

    // No DeviceActivityCenter instance or API is touched on the callback path.
    func applyShield(_ context: RefreshContext) -> String {
        let received = context.received
        let event = context.event
        let activity = context.activityName.map { DeviceActivityName($0) }
        let didStart = context.didStart
        var diagnostics: [String] = []
        let files = GroupStore(category: category, diagnostics: { diagnostics.append($0) })
        let snapshot = files.selectionSnapshot()
        let timetableSnapshot = files.timetableSnapshot()
        let action = SelectionReadPolicy.action(for: snapshot.state, isExtension: category == "monitor")
        let timetableAction = TimetableReadPolicy.action(for: timetableSnapshot.state, isExtension: category == "monitor")
        guard case .evaluate(let hasSelection) = action, case .evaluate = timetableAction else {
            let reasons = [action == .preserve ? "selectionReadFailure" : nil,
                           timetableAction == .preserve ? "timetableReadFailure" : nil]
                .compactMap { $0 }.map { "\($0) action=preserveShieldAndMonitoring" }.joined(separator: " ")
            files.record("\(context.correlation) scheduledStart=unknown scheduledEnd=unknown evaluation=\(GroupStore.timestamp(received)) decision=変えない blocked=unknown authorized=unknown authorizationStatus=unknown authorizationAction=preserve selectionCounts=unknown \(reasons) shieldMs=\(context.elapsedMilliseconds)")
            return diagnostics.joined(separator: " | ")
        }
        let selection = snapshot.value
        let timetable = timetableSnapshot.value?.1
        let settings = ManagedSettingsStore(named: .init("cadence"))
        let start = activity.flatMap { ActivityPlanner.start(for: $0.rawValue) }
        let evaluation = ActivityPlanner.evaluationTime(now: category == "app" ? Date() : received, intervalDidStart: didStart, activityStart: start)
        let authorization = AuthorizationCenter.shared.authorizationStatus
        let authorized = authorization.isApprovedForCadence
        let permitted = MonitoringPolicy.permitsMonitoring(isApp: category == "app", authorized: authorized)
        let authorizationAction = permitted ? "evaluateTimetable" : "clearShieldAndStopMonitoring"
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

        let shieldMs = context.elapsedMilliseconds
        let history = files.activityHistory()
        var eventInterval = activity.flatMap { history[$0.rawValue] }
        if activity?.rawValue == ActivityPlanner.watchdogName {
            let midnight = Calendar.current.startOfDay(for: received)
            let end = Calendar.current.date(bySettingHour: 23, minute: 59, second: 0, of: received)!
            eventInterval = ActivityRecord(start: midnight, end: end)
        }
        let scheduleText = eventInterval.map { GroupStore.timestamp($0.start) } ?? start.map(GroupStore.timestamp) ?? "none"
        let endText = eventInterval.map { GroupStore.timestamp($0.end) } ?? "none"
        files.record("event=\(event) activity=\(activity?.rawValue ?? "none") scheduledStart=\(scheduleText) scheduledEnd=\(endText) received=\(GroupStore.timestamp(received)) evaluation=\(GroupStore.timestamp(evaluation)) decision=\(unchanged ? "変えない" : blocked ? "かけた" : "外した") blocked=\(blocked) authorized=\(authorized) authorizationStatus=\(String(describing: authorization)) authorizationAction=\(authorizationAction) selectionCounts=\(selection?.applicationTokens.count ?? 0)/\(selection?.categoryTokens.count ?? 0)/\(selection?.webDomainTokens.count ?? 0) shieldMs=\(shieldMs)")
        return diagnostics.joined(separator: " | ")
    }

    // Called only by the app worker or the process-local extension worker. Reload all inputs here.
    @discardableResult
    func reconcile(_ context: RefreshContext, reset: Bool = false, coalescedCount: Int = 1) -> Int? {
        let queuedMs = context.elapsedMilliseconds
        let started = DispatchTime.now().uptimeNanoseconds
        var diagnostics: [String] = []
        let files = GroupStore(category: category, diagnostics: { diagnostics.append($0) })
        defer {
            let reconcileMs = (DispatchTime.now().uptimeNanoseconds - started) / 1_000_000
            GroupStore(category: category).record("\(context.correlation) queuedMs=\(queuedMs) reconcileMs=\(reconcileMs) coalescedCount=\(coalescedCount) | " + diagnostics.joined(separator: " | "))
        }
        func persist(_ file: String, _ write: () throws -> Void) {
            do { try write() }
            catch { files.record("statusWriteFailure file=\(file) reason=\(GroupStore.reason(error))") }
        }
        let snapshot = files.selectionSnapshot()
        let timetableSnapshot = files.timetableSnapshot()
        let action = SelectionReadPolicy.action(for: snapshot.state, isExtension: category == "monitor")
        let timetableAction = TimetableReadPolicy.action(for: timetableSnapshot.state, isExtension: category == "monitor")
        guard case .evaluate(let hasSelection) = action, case .evaluate = timetableAction else {
            if action == .preserve { files.record("selectionReadFailure action=preserveShieldAndMonitoring reconcileSkipped=true") }
            if timetableAction == .preserve { files.record("timetableReadFailure action=preserveShieldAndMonitoring reconcileSkipped=true") }
            return nil
        }
        do {
            let timetable = timetableSnapshot.value?.1
            let start = context.activityName.flatMap { ActivityPlanner.start(for: $0) }
            let evaluation = ActivityPlanner.evaluationTime(now: Date(), intervalDidStart: context.didStart, activityStart: start)
            let permitted = MonitoringPolicy.permitsMonitoring(isApp: category == "app",
                authorized: AuthorizationCenter.shared.authorizationStatus.isApprovedForCadence)
            let plan = try timetable.map { try ActivityPlanner.plan($0, now: evaluation) } ?? []
            let canMonitor = permitted && timetable != nil && hasSelection
            var schedules: [String: DeviceActivitySchedule] = [:]
            var desired: [String] = []
            if canMonitor {
                desired = [ActivityPlanner.watchdogName] + plan.map(\.name)
                schedules[ActivityPlanner.watchdogName] = DeviceActivitySchedule(
                    intervalStart: DateComponents(hour: 0, minute: 0),
                    intervalEnd: DateComponents(hour: 23, minute: 59), repeats: true)
                for item in plan {
                    schedules[item.name] = DeviceActivitySchedule(intervalStart: components(item.interval.start),
                        intervalEnd: components(item.interval.end), repeats: false)
                }
            }
            var history: [String: ActivityRecord] = [:]
            for item in plan { history[item.name] = ActivityRecord(start: item.interval.start, end: item.interval.end) }
            persist("activity-history.json") { try files.saveHistory(history) }
            let registeredZone = files.registrationTimeZone()
            let currentZone = TimeZone.current.identifier
            let zoneChanged = MonitoringPolicy.needsTimeZoneReset(registered: registeredZone, current: currentZone)
            if reset || zoneChanged {
                persist("registration-time-zone.json") { try files.saveRegistrationTimeZone(currentZone) }
            }
            if zoneChanged {
                files.record("timeZoneReset previous=\(registeredZone ?? "none") current=\(currentZone) action=stopAllAndRegister")
            }
            let client = ScreenTimeMonitoringClient(schedules: schedules, files: files)
            if reset || zoneChanged { files.record("reset action=stopAllAndRegister") }
            MonitoringReconciler.reconcile(client, desired: desired, reset: reset || zoneChanged)
            persist("registration-errors.json") { try files.saveErrors(client.errors) }
            // The second and last snapshot is solely for diagnostics and the app's status display.
            let names = client.activities.sorted()
            let blockNames = names.filter { $0 != ActivityPlanner.watchdogName }
            files.record("registered count=\(names.count) first=\(blockNames.first ?? "none") last=\(blockNames.last ?? "none") watchdog=\(names.contains(ActivityPlanner.watchdogName)) plannedCount=\(plan.count) plannedLastEnd=\(plan.last.map { GroupStore.timestamp($0.interval.end) } ?? "none")")
            return names.count
        } catch {
            let reason = "更新失敗: \(GroupStore.reason(error))"
            files.record("refreshFailure event=\(context.event) reason=\(GroupStore.reason(error))")
            persist("registration-errors.json") { try files.saveErrors([reason]) }
            return nil
        }
    }
}

private final class ScreenTimeMonitoringClient: MonitoringClient {
    private let center = DeviceActivityCenter()
    private let schedules: [String: DeviceActivitySchedule]
    private let files: GroupStore
    private(set) var errors: [String] = []

    init(schedules: [String: DeviceActivitySchedule], files: GroupStore) {
        self.schedules = schedules
        self.files = files
    }

    var activities: [String] { center.activities.map(\.rawValue) }
    func stopAll() { center.stopMonitoring() }
    func stop(_ names: [String]) { center.stopMonitoring(names.map { DeviceActivityName($0) }) }
    func start(_ name: String) {
        guard let schedule = schedules[name] else { return }
        do { try center.startMonitoring(DeviceActivityName(name), during: schedule) }
        catch {
            errors.append("\(name): \(GroupStore.reason(error))")
            files.record("startMonitoringFailure activity=\(name) reason=\(GroupStore.reason(error))")
        }
    }
}
