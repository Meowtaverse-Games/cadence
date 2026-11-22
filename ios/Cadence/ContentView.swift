import SwiftUI
import FamilyControls

struct ContentView: View {
    @ObservedObject var model: AppModel
    @State private var picker = false
    @State private var draftSelection = FamilyActivitySelection()
    @State private var editing: LocalPattern?
    private let timer = Timer.publish(every: 30, on: .main, in: .common).autoconnect()

    var body: some View {
        NavigationStack {
            List {
                Section("今の状態") {
                    if model.isRefreshing { Text("登録中…") }
                    Text(model.inactiveReason != nil ? "ブロックしていません" : model.blocked ? "ブロック中" : "自由時間")
                    if let reason = model.inactiveReason { Text(reason).font(.footnote) }
                    if let next = model.next {
                        Text("次の切り替え: \(next.formatted(date: .abbreviated, time: .shortened))")
                    } else if model.inactiveReason == nil { Text("次の切り替えはありません") }
                    Text("登録中の activity: \(model.activityCount) / 8")
                    Text("Screen Time: \(authorizationText)")
                    if !model.authorization.isApprovedForCadence {
                        Button("Screen Time を許可") { Task { await model.authorize() } }
                        Text("拒否した場合は、設定アプリの「スクリーンタイム」で Cadence の許可を確認してください。")
                            .font(.footnote)
                    }
                    ForEach(model.registrationErrors, id: \.self) { reason in
                        Text("監視を登録できません: \(reason)。権限と記録を確認し、再登録してください。")
                            .foregroundStyle(.red)
                    }
                    Button("再登録") { model.refresh("本体の再登録/手動") }
                }
                Section("ブロックするアプリ") {
                    Button("アプリを選択") {
                        draftSelection = model.selection
                        picker = true
                    }.disabled(!model.authorization.isApprovedForCadence)
                    Text("アプリ \(model.selection.applicationTokens.count)・カテゴリ \(model.selection.categoryTokens.count)・Web ドメイン \(model.selection.webDomainTokens.count)")
                }
                Section("時間割（日本時間）") {
                    ForEach(model.document.patterns) { item in
                        Button { editing = item } label: {
                            VStack(alignment: .leading) {
                                Text(item.name)
                                Text("\(item.enabled ? "有効" : "無効")・\(item.start)〜\(item.end)・\(dayNames(item.weekdays))")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                    .onDelete { offsets in
                        var candidate = model.document
                        candidate.patterns.remove(atOffsets: offsets)
                        model.save(candidate)
                    }
                    Button("パターンを追加") { editing = LocalPattern() }
                        .disabled(model.document.patterns.count >= 20)
                }
                if let error = model.error { Text(error).foregroundStyle(.red) }
                NavigationLink("記録") { LogView() }
            }
            .navigationTitle("Cadence")
            .sheet(isPresented: $picker) {
                NavigationStack {
                    FamilyActivityPicker(selection: $draftSelection)
                        .navigationTitle("ブロック対象")
                        .toolbar {
                            ToolbarItem(placement: .cancellationAction) { Button("キャンセル") { picker = false } }
                            ToolbarItem(placement: .confirmationAction) {
                                Button("保存") { model.saveSelection(draftSelection); picker = false }
                            }
                        }
                }
            }
            .sheet(item: $editing) { item in PatternEditor(model: model, pattern: item) }
            .onReceive(timer) { _ in model.updateStatus() }
        }
    }

    private var authorizationText: String {
        if model.authorization.isApprovedForCadence { return "許可済み" }
        if model.authorization == .denied { return "拒否されています" }
        if model.authorization == .notDetermined { return "未確認" }
        return "不明"
    }
}

func dayNames(_ values: [Int]) -> String {
    let names = ["月", "火", "水", "木", "金", "土", "日"]
    return values.sorted().filter { (1...7).contains($0) }.map { names[$0 - 1] }.joined(separator: "・")
}

struct LogView: View {
    @State private var entries: [String] = []
    @State private var error: String?
    private let files = GroupStore(category: "app")
    var body: some View {
        List {
            Text("記録には動作時刻が含まれます。共有先を確認してください。アプリ名やトークンは含みません。")
                .font(.footnote)
            ShareLink(item: entries.joined(separator: "\n")) { Label("テキストを共有", systemImage: "square.and.arrow.up") }
            Button("更新") { entries = files.logs() }
            Button("記録を消す", role: .destructive) {
                do { try files.clearLogs(); entries = [] }
                catch { self.error = "削除できませんでした（\(GroupStore.reason(error))）。" }
            }
            if let error { Text(error).foregroundStyle(.red) }
            ForEach(Array(entries.enumerated()), id: \.offset) { entry in
                Text(entry.element).font(.caption.monospaced()).textSelection(.enabled)
            }
        }
        .navigationTitle("記録")
        .onAppear { entries = files.logs() }
    }
}
