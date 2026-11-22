import SwiftUI
import Combine
import FamilyControls
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
    @Published var isRefreshing = false
    let sync = FirebaseSync()
    let files = GroupStore(category: "app")
    private var authorizationSubscription: AnyCancellable?
    private var refreshSubscriptions = Set<AnyCancellable>()

    init() {
        document = files.timetable()?.0 ?? LocalTimetable()
        selection = files.selection() ?? FamilyActivitySelection()
        updateStatus()
        AppRefreshService.shared.$isRefreshing
            .sink { [weak self] in self?.isRefreshing = $0 }
            .store(in: &refreshSubscriptions)
        AppRefreshService.shared.$result
            .compactMap { $0 }
            .sink { [weak self] result in
                // A failed reconciliation does not mean the OS has zero activities.
                if let count = result.activityCount { self?.activityCount = count }
                self?.registrationErrors = result.errors
                self?.updateStatus()
            }
            .store(in: &refreshSubscriptions)
        sync.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }
            .store(in: &refreshSubscriptions)
        sync.start(read: { [weak self] in self?.files.timetable()?.0 }, adopt: { [weak self] document in
            guard let self else { throw CocoaError(.fileWriteUnknown) }
            let previous = self.files.timetable()?.0
            try self.files.save(document)
            self.document = document
            let changed = try previous.map { try !TimetableContent.same($0, document) } ?? true
            if changed { self.refresh("本体の再登録/同期受信") }
        })
        authorizationSubscription = AuthorizationCenter.shared.$authorizationStatus
            .removeDuplicates()
            .dropFirst()
            .receive(on: RunLoop.main)
            .sink { [weak self] status in
                // Publish the emitted value immediately, independently of a slow registration.
                self?.authorization = status
                self?.scheduleRefresh("本体の再登録/権限状態変更")
            }
    }

    func scheduleRefresh(_ event: String, reset: Bool = false) {
        // Refresh authorization/status before enqueueing; never wait for DeviceActivityCenter.
        updateStatus()
        AppRefreshService.shared.request(event, reset: reset)
    }

    func refresh(_ event: String) {
        scheduleRefresh(event, reset: true)
    }

    func updateStatus() {
        authorization = AuthorizationCenter.shared.authorizationStatus
        let timetable = files.timetable()?.1
        let saved = files.selection()
        let hasSelection = saved.map {
            !$0.applicationTokens.isEmpty || !$0.categoryTokens.isEmpty || !$0.webDomainTokens.isEmpty
        } ?? false
        if !authorization.isApprovedForCadence {
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
        guard !sync.editingBlocked else { error = sync.editingReason; return false }
        do {
            let previous = files.timetable()?.0
            let changed = try previous.map { try !TimetableContent.same($0, candidate) } ?? true
            try files.save(candidate)
            document = candidate
            error = nil
            if changed { refresh("本体の再登録/時間割保存") }
            sync.localSaved(candidate)
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
