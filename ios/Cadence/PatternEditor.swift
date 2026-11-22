import SwiftUI

private struct SegmentRow: Identifiable {
    let id = UUID()
    var kind: String
    var minutes: String
}

struct PatternEditor: View {
    @ObservedObject var model: AppModel
    @State var pattern: LocalPattern
    @Environment(\.dismiss) private var dismiss
    @State private var error: String?
    @State private var rows: [SegmentRow]

    init(model: AppModel, pattern: LocalPattern) {
        self.model = model
        _pattern = State(initialValue: pattern)
        _rows = State(initialValue: pattern.segments.map { SegmentRow(kind: $0.kind, minutes: String($0.minutes)) })
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("パターン") {
                    TextField("名前", text: $pattern.name)
                    Toggle("有効", isOn: $pattern.enabled)
                    ForEach(1...7, id: \.self) { day in
                        Toggle(dayNames([day]), isOn: Binding(
                            get: { pattern.weekdays.contains(day) },
                            set: { enabled in
                                pattern.weekdays.removeAll { $0 == day }
                                if enabled { pattern.weekdays.append(day) }
                            }))
                    }
                    TextField("開始 HH:mm", text: $pattern.start).keyboardType(.numbersAndPunctuation)
                    TextField("終了 HH:mm", text: $pattern.end).keyboardType(.numbersAndPunctuation)
                    Text("日本時間。終了が開始より前なら翌日、同じなら24時間です。")
                        .font(.footnote)
                }
                Section("テンプレート") {
                    Button("ブロック25 / 自由5") { template("block", 25, "free", 5) }
                    Button("ブロック50 / 自由10") { template("block", 50, "free", 10) }
                    Button("自由10 / ブロック20") { template("free", 10, "block", 20) }
                }
                Section("セグメント（順番に繰り返す）") {
                    ForEach($rows) { $row in
                        HStack {
                            Picker("種類", selection: $row.kind) {
                                Text("ブロック").tag("block")
                                Text("自由").tag("free")
                            }
                            TextField("分", text: $row.minutes)
                                .keyboardType(.numberPad).frame(width: 70)
                            Text("分")
                        }
                    }
                    .onDelete { rows.remove(atOffsets: $0) }
                    .onMove { rows.move(fromOffsets: $0, toOffset: $1) }
                    Button("セグメントを追加") { rows.append(SegmentRow(kind: "block", minutes: "15")) }
                        .disabled(rows.count >= 20)
                }
                if let error { Text(error).foregroundStyle(.red) }
                if model.document.patterns.contains(where: { $0.id.unicodeScalars.elementsEqual(pattern.id.unicodeScalars) }) {
                    Button("パターンを削除", role: .destructive) {
                        var candidate = model.document
                        candidate.patterns.removeAll { $0.id.unicodeScalars.elementsEqual(pattern.id.unicodeScalars) }
                        if model.save(candidate) { dismiss() } else { error = model.error }
                    }.disabled(model.sync.editingBlocked)
                }
            }
            .disabled(model.sync.editingBlocked)
            .safeAreaInset(edge: .bottom) {
                if model.sync.editingBlocked { Text(model.sync.editingReason).font(.footnote).padding() }
            }
            .navigationTitle("時間割の編集")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("キャンセル") { dismiss() } }
                ToolbarItem(placement: .primaryAction) { EditButton() }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        var segments: [LocalSegment] = []
                        for row in rows {
                            guard let minutes = Int(row.minutes) else {
                                error = ValidationFailure(code: "segment_minutes").errorDescription
                                return
                            }
                            segments.append(LocalSegment(kind: row.kind, minutes: minutes))
                        }
                        pattern.segments = segments
                        var candidate = model.document
                        if let index = candidate.patterns.firstIndex(where: { $0.id.unicodeScalars.elementsEqual(pattern.id.unicodeScalars) }) {
                            candidate.patterns[index] = pattern
                        } else { candidate.patterns.append(pattern) }
                        if model.save(candidate) { dismiss() } else { error = model.error }
                    }.disabled(model.sync.editingBlocked)
                }
            }
        }
    }

    private func template(_ first: String, _ firstMinutes: Int, _ second: String, _ secondMinutes: Int) {
        rows = [SegmentRow(kind: first, minutes: String(firstMinutes)), SegmentRow(kind: second, minutes: String(secondMinutes))]
    }
}
