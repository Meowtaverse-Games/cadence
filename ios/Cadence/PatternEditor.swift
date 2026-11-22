import SwiftUI
import CadenceCore

private struct SegmentRow: Identifiable {
    let id = UUID()
    var kind: String
    var minutes: String
}

struct PatternEditor: View {
    @ObservedObject var model: AppModel
    let pattern: LocalPattern
    var body: some View {
        PatternEditorScreen(document: model.document, pattern: pattern, editingBlocked: model.sync.editingBlocked,
                            editingReason: model.sync.editingReason) { candidate in
            model.save(candidate) ? nil : model.error ?? "保存できませんでした。"
        }
    }
}

struct PatternEditorScreen: View {
    let document: LocalTimetable
    @State var pattern: LocalPattern
    let editingBlocked: Bool
    let editingReason: String
    let initialScrollTarget: String?
    let save: (LocalTimetable) -> String?
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var typeSize
    @State private var error: String?
    @State private var rows: [SegmentRow]

    init(document: LocalTimetable, pattern: LocalPattern, editingBlocked: Bool = false, editingReason: String = "",
         initialScrollTarget: String? = nil,
         save: @escaping (LocalTimetable) -> String?) {
        self.initialScrollTarget = initialScrollTarget
        self.document = document; self.editingBlocked = editingBlocked; self.editingReason = editingReason; self.save = save
        _pattern = State(initialValue: pattern)
        _rows = State(initialValue: pattern.segments.map { SegmentRow(kind: $0.kind, minutes: String($0.minutes)) })
    }

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: Theme.spacing) {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("パターン名").font(Theme.caption).foregroundStyle(Theme.muted)
                            TextField("名前", text: $pattern.name, prompt: Text("名前").foregroundColor(Theme.muted)).cadenceField()
                            Toggle("有効", isOn: $pattern.enabled).tint(Theme.focus).frame(minHeight: 44)
                        }.cadenceCard()
                        VStack(alignment: .leading, spacing: 10) {
                            Text("曜日").font(Theme.headline)
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 4) {
                                    ForEach(1...7, id: \.self) { day in
                                        Button {
                                            if pattern.weekdays.contains(day) { pattern.weekdays.removeAll { $0 == day } }
                                            else { pattern.weekdays.append(day) }
                                        } label: { Text(dayNames([day])).frame(minWidth: 16) }
                                            .buttonStyle(CadenceButtonStyle(primary: pattern.weekdays.contains(day)))
                                            .accessibilityLabel("\(dayNames([day]))曜日")
                                            .accessibilityAddTraits(pattern.weekdays.contains(day) ? .isSelected : [])
                                    }
                                }
                            }
                        }
                        ViewThatFits(in: .horizontal) {
                            HStack { timeField("開始", text: $pattern.start); Text("→").foregroundStyle(Theme.muted); timeField("終了", text: $pattern.end) }
                            VStack { timeField("開始", text: $pattern.start); timeField("終了", text: $pattern.end) }
                        }
                        Text("日本時間。終了が開始より前なら翌日、同じなら24時間です。")
                            .font(Theme.footnote).foregroundStyle(Theme.muted)
                        Text("テンプレート").font(Theme.headline)
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack {
                                Button("集25 / 自5") { template("block", 25, "free", 5) }.accessibilityLabel("ブロック25分、自由5分")
                                Button("集50 / 自10") { template("block", 50, "free", 10) }.accessibilityLabel("ブロック50分、自由10分")
                                Button("自10 / 集20") { template("free", 10, "block", 20) }.accessibilityLabel("自由10分、ブロック20分")
                            }.buttonStyle(CadenceButtonStyle())
                        }
                        Text("1サイクル（くり返し）").font(Theme.headline).id("cycle")
                        ForEach($rows) { row in segmentRow(row) }
                        Button("＋ セグメントを追加") { rows.append(SegmentRow(kind: "block", minutes: "15")) }
                            .buttonStyle(CadenceButtonStyle()).disabled(rows.count >= 20)
                        preview
                        if let error { Text(error).font(Theme.footnote).foregroundStyle(Theme.alert) }
                        if document.patterns.contains(where: { sameID($0) }) {
                            Button("パターンを削除", role: .destructive) {
                                var candidate = document
                                candidate.patterns.removeAll { sameID($0) }
                                finish(candidate)
                            }.buttonStyle(CadenceButtonStyle())
                        }
                    }.padding(Theme.spacing).disabled(editingBlocked)
                }
                .onAppear {
                    if let initialScrollTarget {
                        DispatchQueue.main.async { proxy.scrollTo(initialScrollTarget, anchor: .top) }
                    }
                }
                .safeAreaInset(edge: .bottom) {
                    if editingBlocked { Text(editingReason).font(Theme.footnote).foregroundStyle(Theme.muted).padding().background(Theme.ground) }
                }
                .navigationTitle("PATTERN").navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("キャンセル") { dismiss() }.frame(minHeight: 44) }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("保存") { savePattern() }.buttonStyle(CadenceButtonStyle(primary: true)).disabled(editingBlocked)
                    }
                }.cadenceScreen()
            }
        }.preferredColorScheme(.dark)
    }

    private func timeField(_ title: String, text: Binding<String>) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(Theme.caption).foregroundStyle(Theme.muted)
            TextField("HH:mm", text: text, prompt: Text("HH:mm").foregroundColor(Theme.muted)).font(Theme.clock).keyboardType(.numbersAndPunctuation)
                .accessibilityLabel("\(title)時刻、日本時間").cadenceField()
        }
    }

    private func segmentRow(_ row: Binding<SegmentRow>) -> some View {
        HStack(spacing: 10) {
            RoundedRectangle(cornerRadius: 3).fill(row.wrappedValue.kind == "block" ? Theme.focus : Theme.free).frame(width: 5)
            if typeSize.isAccessibilitySize {
                VStack(alignment: .leading, spacing: 12) {
                    HStack { segmentKind(row); Spacer(minLength: 0); segmentMenu(row.wrappedValue.id) }
                    segmentMinutes(row)
                }
            } else {
                segmentKind(row)
                Spacer(minLength: 0)
                segmentMinutes(row)
                segmentMenu(row.wrappedValue.id)
            }
        }.fixedSize(horizontal: false, vertical: true).cadenceCard()
    }
    private func segmentKind(_ row: Binding<SegmentRow>) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            StateTag(text: row.wrappedValue.kind == "block" ? "FOCUS" : "FREE", color: row.wrappedValue.kind == "block" ? Theme.focus : Theme.free)
            Picker("種類", selection: row.kind) {
                Text("ブロック").tag("block"); Text("自由").tag("free")
            }.pickerStyle(.menu).tint(row.wrappedValue.kind == "block" ? Theme.focus : Theme.free).labelsHidden().frame(minHeight: 44).accessibilityLabel("セグメントの種類")
        }.fixedSize(horizontal: true, vertical: true)
    }
    private func segmentMinutes(_ row: Binding<SegmentRow>) -> some View {
        HStack(spacing: 8) {
            TextField("分", text: row.minutes, prompt: Text("分").foregroundColor(Theme.muted)).keyboardType(.numberPad)
                .font(Theme.inputNumber).frame(minWidth: 44, maxWidth: 60).cadenceField()
                .accessibilityLabel("セグメントの分数")
            Text("分").font(Theme.caption).foregroundStyle(Theme.muted)
        }
    }
    private func segmentMenu(_ id: UUID) -> some View {
        let index = rows.firstIndex { $0.id == id }
        return Menu {
            Button { move(id, by: -1) } label: { Label("上へ移動", systemImage: "arrow.up") }.disabled(index == nil || index == 0)
            Button { move(id, by: 1) } label: { Label("下へ移動", systemImage: "arrow.down") }.disabled(index == nil || index == rows.count - 1)
            Button(role: .destructive) { rows.removeAll { $0.id == id } } label: { Label("削除", systemImage: "trash") }
        } label: {
            Image(systemName: "ellipsis").frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
        }.accessibilityLabel("セグメントの並べ替えと削除")
    }
    private func move(_ id: UUID, by offset: Int) {
        guard let index = rows.firstIndex(where: { $0.id == id }), rows.indices.contains(index + offset) else { return }
        rows.swapAt(index, index + offset)
    }

    private var preview: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("プレビュー · FOCUS / FREE").font(Theme.mono).foregroundStyle(Theme.muted)
            if let candidate = candidatePattern() {
                let validation = TimetableParser.parse(object: replacingPattern(candidate).fields)
                if let code = validation.errorCode {
                    Text(ValidationFailure(code: code).errorDescription ?? "入力を確認してください。")
                        .font(Theme.footnote).foregroundStyle(Theme.alert)
                } else if let window = previewWindow(candidate) {
                    WindowBar(window: window, name: candidate.name)
                } else {
                    Text("無効なパターンです。ブロックを含むサイクルを設定するとプレビューできます。")
                        .font(Theme.footnote).foregroundStyle(Theme.muted)
                }
            } else { Text(ValidationFailure(code: "segment_minutes").errorDescription ?? "分数を確認してください。").font(Theme.footnote).foregroundStyle(Theme.alert) }
        }.cadenceCard()
    }
    private func previewWindow(_ candidate: LocalPattern) -> DisplayWindow? {
        var standalone = LocalTimetable()
        var value = candidate
        value.enabled = true
        value.weekdays = [1, 2, 3, 4, 5, 6, 7]
        standalone.patterns = [value]
        guard let table = TimetableParser.parse(object: standalone.fields).timetable else { return nil }
        return try? TimetableDisplay.window(table, at: previewMidnight)
    }
    private func replacingPattern(_ updated: LocalPattern) -> LocalTimetable {
        var candidate = document
        if let index = candidate.patterns.firstIndex(where: { sameID($0) }) { candidate.patterns[index] = updated }
        else { candidate.patterns.append(updated) }
        return candidate
    }
    private var previewMidnight: Date {
        // Fixed synthetic Monday; only the pattern's time of day matters here.
        Date(timeIntervalSince1970: 1767538800)
    }
    private func candidatePattern() -> LocalPattern? {
        var candidate = pattern
        var segments: [LocalSegment] = []
        for row in rows {
            guard let minutes = Int(row.minutes) else { return nil }
            segments.append(LocalSegment(kind: row.kind, minutes: minutes))
        }
        candidate.segments = segments
        return candidate
    }
    private func sameID(_ other: LocalPattern) -> Bool { other.id.unicodeScalars.elementsEqual(pattern.id.unicodeScalars) }
    private func savePattern() {
        guard let updated = candidatePattern() else { error = ValidationFailure(code: "segment_minutes").errorDescription; return }
        pattern = updated
        finish(replacingPattern(updated))
    }
    private func finish(_ candidate: LocalTimetable) {
        if let failure = save(candidate) { error = failure } else { dismiss() }
    }
    private func template(_ first: String, _ firstMinutes: Int, _ second: String, _ secondMinutes: Int) {
        rows = [SegmentRow(kind: first, minutes: String(firstMinutes)), SegmentRow(kind: second, minutes: String(secondMinutes))]
    }
}
