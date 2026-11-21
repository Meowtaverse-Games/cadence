import SwiftUI
import FamilyControls
import DeviceActivity
import CadenceCore

@MainActor
final class AppModel: ObservableObject {
    @Published var document = LocalTimetable()
    @Published var selection = FamilyActivitySelection()
    @Published var authorization = AuthorizationCenter.shared.authorizationStatus
    @Published var blocked = false
    @Published var next: Date?
    @Published var activityCount = 0
    @Published var error: String?
    @Published var registrationErrors: [String] = []
    let files = GroupStore(category: "app")

    init() {
        document = files.timetable()?.0 ?? LocalTimetable()
        selection = files.selection() ?? FamilyActivitySelection()
    }

    func refresh(_ event: String) {
        ScreenTimeCoordinator(category: "app").refresh(event: event, reset: true)
        updateStatus()
    }

    func updateStatus() {
        authorization = AuthorizationCenter.shared.authorizationStatus
        let timetable = files.timetable()?.1
        let saved = files.selection()
        let hasSelection = saved.map {
            !$0.applicationTokens.isEmpty || !$0.categoryTokens.isEmpty || !$0.webDomainTokens.isEmpty
        } ?? false
        blocked = authorization == .approved && hasSelection && ActivityPlanner.isBlocked(timetable, at: Date())
        next = timetable.flatMap { try? ActivityPlanner.nextTransition($0, now: Date()) }
        activityCount = DeviceActivityCenter().activities.count
        registrationErrors = files.registrationErrors()
    }

    func authorize() async {
        do {
            try await AuthorizationCenter.shared.requestAuthorization(for: .individual)
            error = nil
        } catch {
            self.error = "Screen Time の許可を取得できませんでした（\(GroupStore.reason(error))）。設定アプリの「スクリーンタイム」で Cadence の許可を確認してください。"
            files.record("authorizationFailure reason=\(GroupStore.reason(error))")
        }
        refresh("本体の再登録/権限")
    }

    @discardableResult
    func save(_ candidate: LocalTimetable) -> Bool {
        do {
            try files.save(candidate)
            document = candidate
            error = nil
            refresh("本体の再登録/時間割保存")
            return true
        } catch {
            self.error = (error as? ValidationFailure)?.errorDescription ?? "保存できませんでした（\(GroupStore.reason(error))）。"
            return false
        }
    }

    func saveSelection(_ value: FamilyActivitySelection) {
        do {
            try files.save(value)
            selection = value
            error = nil
            refresh("本体の再登録/選択変更")
        } catch { self.error = "選択を保存できませんでした（\(GroupStore.reason(error))）。" }
    }
}
