import Foundation
import CadenceCore

struct LocalTimetable: Codable {
    var schemaVersion = 1
    var timeZone = "Asia/Tokyo"
    var patterns: [LocalPattern] = []
}

struct LocalPattern: Codable, Identifiable {
    var id = UUID().uuidString
    var name = "新しいパターン"
    var enabled = true
    var weekdays = [1, 2, 3, 4, 5]
    var start = "19:00"
    var end = "20:00"
    var segments = [LocalSegment(kind: "block", minutes: 25), LocalSegment(kind: "free", minutes: 5)]
}

struct LocalSegment: Codable {
    var kind: String
    var minutes: Int
}

struct ValidationFailure: LocalizedError {
    let code: String
    var errorDescription: String? {
        let messages = [
            "invalid_json": "JSON を読み込めません。", "invalid_document": "時間割の形式が正しくありません。",
            "schema_version": "時間割のバージョンが対応していません。", "time_zone": "時間割は Asia/Tokyo を指定してください。",
            "patterns": "パターンは20件までです。", "pattern_id": "パターンの ID が空か重複しています。",
            "pattern_name": "名前は1〜40文字にしてください。", "enabled": "有効・無効の指定が正しくありません。",
            "weekdays": "曜日を1つ以上、重複せずに選んでください。", "window_time": "時刻は HH:mm（00:00〜23:59）で入力してください。",
            "segments": "セグメントは1〜20件にしてください。", "segment_kind": "ブロックか自由を選んでください。",
            "segment_minutes": "時間は1〜720分の整数にしてください。", "block_minutes": "ブロックは15分以上にしてください。",
            "missing_block": "有効なパターンにはブロックが必要です。", "window_overlap": "有効なパターンの曜日・時間帯が重なっています。"
        ]
        return "\(messages[code] ?? "時間割を確認してください。")（\(code)）"
    }
}
