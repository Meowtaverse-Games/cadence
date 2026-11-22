import SwiftUI
import FamilyControls
import CadenceCore

struct ContentView: View {
    @ObservedObject var model: AppModel
    @State private var picker = false
    @State private var draftSelection = FamilyActivitySelection()
    @State private var editing: LocalPattern?
    @State private var syncSheet = false
    private let timer = Timer.publish(every: 30, on: .main, in: .common).autoconnect()

    var body: some View {
        NavigationStack {
            HomeScreen(state: HomeState(model: model),
                       sync: { syncSheet = true }, authorize: { Task { await model.authorize() } },
                       select: { draftSelection = model.selection; picker = true },
                       edit: { editing = $0 }, delete: { index in
                           var candidate = model.document
                           candidate.patterns.remove(at: index)
                           model.save(candidate)
                       }, refresh: { model.refresh("本体の再登録/手動") }) {
                if let remote = model.sync.conflict {
                    FirstLinkView(local: model.document, remote: remote, uploading: model.sync.initialUpload,
                                  useLocal: { model.sync.useLocal() }, useServer: { model.sync.useServer() })
                }
            }
            .sheet(isPresented: $syncSheet) {
                NavigationStack {
                    ScrollView { SyncSection(sync: model.sync, local: model.document).padding(Theme.spacing) }
                        .navigationTitle("同期").navigationBarTitleDisplayMode(.inline)
                        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("閉じる") { syncSheet = false }.frame(minHeight: 44) } }
                        .cadenceScreen()
                }.preferredColorScheme(.dark)
            }
            .sheet(isPresented: $picker) {
                NavigationStack {
                    FamilyActivityPicker(selection: $draftSelection)
                        .navigationTitle("ブロック対象")
                        .toolbar {
                            ToolbarItem(placement: .cancellationAction) { Button("キャンセル") { picker = false }.frame(minHeight: 44) }
                            ToolbarItem(placement: .confirmationAction) {
                                Button("保存") { model.saveSelection(draftSelection); picker = false }.frame(minHeight: 44)
                            }
                        }.cadenceScreen()
                }.preferredColorScheme(.dark)
            }
            .sheet(item: $editing) { item in PatternEditor(model: model, pattern: item) }
            .onReceive(timer) { _ in model.updateStatus() }
        }.preferredColorScheme(.dark)
    }
}

/// Value-only presentation snapshot. It never accesses services while a TimelineView ticks.
struct HomeState {
    var document: LocalTimetable
    var authorized: Bool
    var inactiveReason: String?
    var activityCount: Int
    var counts: String
    var refreshing = false
    var errors: [String] = []
    var editingBlocked = false
    var editingReason = ""
    var syncTime: Date?
    var syncColor = Theme.muted
    var syncStatus = ""
    var horizonEnd: Date
    var intervals: [BlockInterval] = []
    var window: DisplayWindow?
    var reference: Date? // Debug previews can freeze the display clock without a service.

    init(document: LocalTimetable, authorized: Bool, inactiveReason: String?, activityCount: Int,
         counts: String, now: Date = Date()) {
        self.document = document; self.authorized = authorized; self.inactiveReason = inactiveReason
        self.activityCount = activityCount; self.counts = counts
        horizonEnd = now.addingTimeInterval(ActivityPlanner.horizon)
        if let table = TimetableParser.parse(object: document.fields).timetable {
            intervals = (try? TimetableExpander.expand(table, from: now, to: now.addingTimeInterval(ActivityPlanner.horizon))) ?? []
            window = try? TimetableDisplay.window(table, at: now)
        }
    }

    @MainActor init(model: AppModel) {
        self.init(document: model.document, authorized: model.authorization.isApprovedForCadence,
                  inactiveReason: model.inactiveReason, activityCount: model.activityCount,
                  counts: "APP \(model.selection.applicationTokens.count) · CAT \(model.selection.categoryTokens.count) · WEB \(model.selection.webDomainTokens.count)")
        refreshing = model.isRefreshing
        errors = model.registrationErrors.map { "監視を登録できません: \($0)。権限と記録を確認し、再登録してください。" }
        if let error = model.error { errors.append(error) }
        editingBlocked = model.sync.editingBlocked; editingReason = model.sync.editingReason
        syncTime = model.sync.lastSync
        syncStatus = model.sync.status
        syncColor = model.sync.error != nil || model.sync.hasWriteFailure ? Theme.alert :
            model.sync.configured && model.sync.signedIn && model.sync.lastSync != nil && model.sync.status == "同期済みです。" ? Theme.free : Theme.muted
    }

    func transition(at now: Date) -> (blocked: Bool, next: Date?) {
        guard inactiveReason == nil else { return (false, nil) }
        guard let first = intervals.first(where: { $0.end > now }) else { return (false, nil) }
        guard first.contains(now) else { return (false, first.start) }
        var end = first.end
        for interval in intervals where interval.start >= first.start {
            if interval.start > end { return (true, end) }
            end = max(end, interval.end)
        }
        return (true, end >= horizonEnd ? nil : end)
    }
}

struct HomeScreen<Conflict: View>: View {
    let state: HomeState
    let sync: () -> Void
    let authorize: () -> Void
    let select: () -> Void
    let edit: (LocalPattern) -> Void
    let delete: (Int) -> Void
    let refresh: () -> Void
    @ViewBuilder let conflict: () -> Conflict

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Theme.spacing) {
                ViewThatFits(in: .horizontal) {
                    HStack { heading; Spacer(); syncButton }
                    VStack(alignment: .leading) { heading; syncButton }
                }
                conflict()
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    StatusCard(state: state, now: state.reference ?? context.date, authorize: authorize)
                }
                HStack {
                    Text("時間割").font(Theme.headline); Text("JST").font(Theme.mono).foregroundStyle(Theme.muted)
                    Spacer()
                    Button("＋ 追加") { edit(LocalPattern()) }.frame(minHeight: 44)
                        .disabled(state.document.patterns.count >= 20 || state.editingBlocked)
                }
                if state.editingBlocked { Text(state.editingReason).font(Theme.footnote).foregroundStyle(Theme.muted) }
                ForEach(Array(state.document.patterns.enumerated()), id: \.offset) { index, pattern in
                    Button { edit(pattern) } label: { PatternSummary(pattern: pattern).cadenceCard() }
                        .buttonStyle(.plain)
                        .contextMenu {
                            Button("パターンを削除", role: .destructive) { delete(index) }
                                .disabled(state.editingBlocked)
                        }.disabled(state.editingBlocked)
                }
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("ブロックするアプリ").font(.subheadline.weight(.medium))
                        Text(state.counts).font(Theme.mono).foregroundStyle(Theme.muted)
                    }
                    Spacer(minLength: 0)
                    Button("選択", action: select).buttonStyle(CadenceButtonStyle()).disabled(!state.authorized)
                }.cadenceCard()
                ViewThatFits(in: .horizontal) {
                    HStack { monitor; Spacer(); permission; Spacer(); logLink }
                    VStack(alignment: .leading) { monitor; permission; logLink }
                }.font(Theme.mono).foregroundStyle(Theme.muted)
                HStack {
                    Button("再登録", action: refresh).buttonStyle(CadenceButtonStyle())
                    if state.refreshing { Text("登録中…").font(Theme.footnote).foregroundStyle(Theme.muted) }
                }
                ForEach(Array(state.errors.enumerated()), id: \.offset) { _, error in Text(error).font(Theme.footnote).foregroundStyle(Theme.alert) }
            }.padding(Theme.spacing)
        }.cadenceScreen().toolbar(.hidden, for: .navigationBar)
    }
    private var heading: some View { Text("CADENCE").font(Theme.title).tracking(4) }
    private var syncButton: some View {
        Button(action: sync) {
            HStack(spacing: 6) {
                Circle().fill(state.syncColor).frame(width: 7, height: 7)
                Text("SYNC \(state.syncTime.map(Theme.time) ?? "—")").font(Theme.mono)
            }
        }.buttonStyle(CadenceButtonStyle()).accessibilityLabel("同期の状態: \(state.syncStatus)")
    }
    private var monitor: some View { Text("MONITOR \(state.activityCount)/8") }
    private var permission: some View { Text(state.authorized ? "SCREEN TIME OK" : "NOT ALLOWED") }
    private var logLink: some View { NavigationLink("LOG") { LogView() }.frame(minWidth: 44, minHeight: 44).accessibilityLabel("記録") }
}

struct StatusCard: View {
    let state: HomeState
    let now: Date
    let authorize: () -> Void
    var body: some View {
        let status = state.transition(at: now)
        let idle = state.inactiveReason != nil
        VStack(alignment: .leading, spacing: 16) {
            ViewThatFits(in: .horizontal) {
                HStack(alignment: .top, spacing: 12) { title(idle, status.blocked); Spacer(minLength: 0); countdown(status) }
                VStack(alignment: .leading, spacing: 12) { title(idle, status.blocked); countdown(status) }
            }
            if let reason = state.inactiveReason { Text(reason).font(Theme.body).foregroundStyle(Theme.muted) }
            if !state.authorized {
                Button("Screen Time を許可", action: authorize).buttonStyle(CadenceButtonStyle(primary: true))
                Text("拒否した場合は、設定アプリの「スクリーンタイム」で Cadence の許可を確認してください。")
                    .font(Theme.footnote).foregroundStyle(Theme.muted)
            }
            if let window = state.window {
                WindowBar(window: window, name: state.document.patterns[window.patternIndex].name, now: now)
            } else { Text("今日の予定はありません").font(Theme.footnote).foregroundStyle(Theme.muted) }
            Rectangle().fill(Theme.line).frame(height: 1)
            HStack {
                Text("次の切り替え").font(Theme.footnote).foregroundStyle(Theme.muted)
                Spacer()
                if let next = status.next {
                    Text("\(Theme.time(next)) →").font(Theme.mono)
                    StateTag(text: status.blocked ? "FREE" : "FOCUS", color: status.blocked ? Theme.free : Theme.focus)
                } else { Text("—").font(Theme.mono) }
            }
        }.cadenceCard().shadow(color: !idle && status.blocked ? Theme.focus.opacity(0.13) : .clear, radius: 20)
    }
    private func title(_ idle: Bool, _ blocked: Bool) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            StateTag(text: idle ? "IDLE" : blocked ? "FOCUS" : "FREE", color: idle ? Theme.muted : blocked ? Theme.focus : Theme.free)
            Text(idle ? "ブロックしていません" : blocked ? "集中の時間" : "自由時間").font(Theme.statusTitle).fixedSize(horizontal: false, vertical: true)
        }
    }
    @ViewBuilder private func countdown(_ status: (blocked: Bool, next: Date?)) -> some View {
        if let next = status.next {
            VStack(alignment: .trailing, spacing: 4) {
                Text(Theme.remaining(until: next, now: now)).font(Theme.clock).monospacedDigit().fixedSize()
                    .accessibilityLabel("残り \(Theme.remaining(until: next, now: now))")
                Text("\(Theme.time(next)) \(status.blocked ? "まで" : "から")").font(Theme.caption).foregroundStyle(Theme.muted)
            }
        }
    }
}

struct PatternSummary: View {
    let pattern: LocalPattern
    var body: some View {
        HStack(spacing: 12) {
            RoundedRectangle(cornerRadius: 1.5).fill(pattern.enabled ? Theme.focus : Theme.line)
                .frame(width: 10, height: 10).rotationEffect(.degrees(45)).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 5) {
                Text(pattern.name).font(.subheadline.weight(.medium))
                Text("\(pattern.start)–\(pattern.end) · \(compactDayNames(pattern.weekdays)) · \(pattern.segments.map { "\($0.kind == "block" ? "集" : "自")\($0.minutes)" }.joined(separator: "/"))")
                    .font(Theme.mono).foregroundStyle(Theme.muted).fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
            Text(pattern.enabled ? "有効" : "無効").font(Theme.caption).foregroundStyle(Theme.muted).fixedSize()
        }.frame(minHeight: 44).foregroundStyle(Theme.text).contentShape(Rectangle())
    }
}

func dayNames(_ values: [Int]) -> String {
    let names = ["月", "火", "水", "木", "金", "土", "日"]
    return values.sorted().filter { (1...7).contains($0) }.map { names[$0 - 1] }.joined(separator: "・")
}

func compactDayNames(_ values: [Int]) -> String {
    if values.sorted() == Array(1...7) { return "毎日" }
    return dayNames(values).replacingOccurrences(of: "・", with: "")
}

struct LogView: View {
    @State private var entries: [String] = []
    @State private var error: String?
    private let files = GroupStore(category: "app")
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("記録には動作時刻が含まれます。共有先を確認してください。アプリ名やトークンは含みません。")
                    .font(Theme.footnote).foregroundStyle(Theme.muted)
                ShareLink(item: entries.joined(separator: "\n")) { Label("テキストを共有", systemImage: "square.and.arrow.up") }.buttonStyle(CadenceButtonStyle())
                HStack {
                    Button("更新") { entries = files.logs() }.buttonStyle(CadenceButtonStyle())
                    Button("記録を消す", role: .destructive) {
                        do { try files.clearLogs(); entries = [] }
                        catch { self.error = "削除できませんでした（\(GroupStore.reason(error))）。" }
                    }.buttonStyle(CadenceButtonStyle())
                }
                if let error { Text(error).foregroundStyle(Theme.alert) }
                ForEach(Array(entries.enumerated()), id: \.offset) { entry in
                    Text(entry.element).font(Theme.mono).textSelection(.enabled).cadenceCard()
                }
            }.padding(Theme.spacing)
        }.navigationTitle("記録").toolbar(.visible, for: .navigationBar).cadenceScreen().onAppear { entries = files.logs() }
    }
}
