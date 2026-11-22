import SwiftUI
import Combine
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
    @Published var inactiveReason: String?
    @Published var activityCount = 0
    @Published var error: String?
    @Published var registrationErrors: [String] = []
    let files = GroupStore(category: "app")
    private var authorizationSubscription: AnyCancellable?
    private var pendingRefresh: Task<Void, Never>?
    private var pendingEvents = Set<String>()

    init() {
        document = files.timetable()?.0 ?? LocalTimetable()
        selection = files.selection() ?? FamilyActivitySelection()
        authorizationSubscription = AuthorizationCenter.shared.$authorizationStatus
            .removeDuplicates()
            .dropFirst()
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in
                // Published emits before its property changes; defer reading the center to the refresh.
                self?.scheduleRefresh("本体の再登録/権限状態変更")
            }
    }

    /// Coalesce launch, foreground and authorization notifications without dropping the latest state.
    func scheduleRefresh(_ event: String) {
        pendingEvents.insert(event)
        pendingRefresh?.cancel()
        pendingRefresh = Task { [weak self] in
            do { try await Task.sleep(nanoseconds: 200_000_000) } catch { return }
            guard let self else { return }
            self.refresh(self.pendingEvents.sorted().joined(separator: ","))
        }
    }

    func refresh(_ event: String) {
        pendingRefresh?.cancel()
        pendingRefresh = nil
        pendingEvents.removeAll()
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
        if authorization != .approved {
            inactiveReason = "Screen Time が許可されていないため、ブロックしていません。"
        } else if !hasSelection {
            inactiveReason = "ブロックするアプリ・カテゴリ・Web ドメインが選択されていません。"
        } else if timetable == nil || !document.patterns.contains(where: \.enabled) {
            inactiveReason = "有効な時間割がないため、ブロックしていません。"
        } else {
            inactiveReason = nil
        }
        let now = Date()
        blocked = inactiveReason == nil && ActivityPlanner.isBlocked(timetable, at: now)
        next = inactiveReason == nil ? timetable.flatMap { try? ActivityPlanner.nextTransition($0, now: now) } : nil
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
        scheduleRefresh("本体の再登録/権限要求完了")
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
