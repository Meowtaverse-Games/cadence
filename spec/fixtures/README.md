# 時間割の共通テストケース

`docs/mvp-spec.md` の検証・展開規則を、C# と後続の Swift 実装で共有する。
データはすべて合成例。期待値は実装結果から生成せず、仕様から求めた値を記載する。
`description` に日本時間での区間や判定根拠を記載する。

## 形式

1ケース1ファイルの `*.json`。ファイル名（拡張子なし）と `name` は一致する。
テスト実装はディレクトリ内のすべての JSON を列挙し、ケース追加を自動的に取り込む。

```json
{
  "name": "example",
  "description": "空の時間割は有効で区間なし。",
  "timetable": {
    "schemaVersion": 1,
    "timeZone": "Asia/Tokyo",
    "patterns": []
  },
  "from": "2026-01-05T00:00:00Z",
  "to": "2026-01-06T00:00:00Z",
  "expected": { "valid": true, "intervals": [] }
}
```

- `timetable`: 仕様の文書そのもの。Firestore の typed JSON ではない。
  `updatedAt` は検証対象外なので省略でき、含む場合も未知フィールドとして無視する。
- `from` / `to`: UTC の ISO 8601（秒まで、`Z` 付き）。検索期間は半開区間 `[from, to)`。
- 有効: `expected` は `valid: true` と `intervals: [{"start": "…Z", "end": "…Z"}]`。
  区間は開始昇順で、窓の中で結合した後に15分未満を除外し、検索境界では切り詰めない。
- 無効: `expected` は `valid: false` と `error: "理由コード"`。
  文書全体を無効とし、区間を返さない。
- 整数は JSON の数値の**値**で判定する。`1.0`、`10.0` は受理し、真偽値・文字列を数値に変換しない。
- 文字数は Unicode コードポイント数。絵文字 `😀` は1、`e` と結合アクセントは合計2。
  `unicode-name-40` / `unicode-name-41` はこの区別を検証する。

## 無効理由コードと検証規則の対応

複数の規則に違反する文書で、どの理由を優先するかは共通仕様にしない。
ケースは対象の規則を切り分ける（必須フィールド欠落・型違いも同じコード）。

| 検証規則の箇条書き | コード | 代表ケース |
|---|---|---|
| schemaVersion が整数の1でない | `schema_version` | `schema-wrong`, `schema-boolean`, `schema-fraction` |
| timeZone が文字列 Asia/Tokyo でない | `time_zone` | `timezone-wrong` |
| patterns が配列でない、または21件以上 | `patterns` | `patterns-not-array`, `patterns-too-many` |
| id が空でない文字列でない、または重複 | `pattern_id` | `id-empty`, `duplicate-id` |
| name が1〜40文字の文字列でない | `pattern_name` | `name-empty`, `unicode-name-41` |
| enabled が真偽値でない | `enabled` | `enabled-one`, `enabled-zero` |
| weekdays が空、範囲外、非整数、重複 | `weekdays` | `weekdays-empty`, `weekdays-eight`, `weekdays-fraction`, `weekdays-duplicate` |
| start / end が正確な HH:mm でない | `window_time` | `time-short`, `time-24`, `time-newline` |
| segments が空、または21件以上 | `segments` | `segments-empty`, `segments-too-many` |
| kind が free / block 以外 | `segment_kind` | `kind-unknown` |
| minutes が整数でない、または1〜720の範囲外 | `segment_minutes` | `minutes-fraction`, `minutes-zero`, `minutes-over-max`, `minutes-boolean` |
| block の minutes が15未満 | `block_minutes` | `block-fourteen`, `disabled-invalid-block` |
| enabled: true なのに block がない | `missing_block` | `missing-block` |
| ほかの有効なパターンと窓が重なる（週の折り返しを含む） | `window_overlap` | `overlapping-windows`, `weekly-overlap`, `overlapping-free-part` |

`segments` / `weekdays` が配列でない場合もそれぞれのコードで無効とする。
不正 JSON は JSON オブジェクトとして格納できないため、C# 側の単体テストで
`invalid_json` を確認する。文書自体がオブジェクトでない場合は `invalid_document`。

## 展開と境界の代表ケース

- `basic`, `block-first`: free始まりとblock始まり。
- `truncated-fifteen`, `truncated-fourteen`: 窓末尾に残る15分と14分。
- `block-only`, `adjacent-blocks`, `cycle-boundary-merge`: 同じ窓内での結合と、結合後の長さ判定。
- `overnight`, `overnight-query-start`, `full-day`, `sunday-to-monday`: 日またぎ、前日の窓、24時間、週の境目。
- `query-inside-block`, `half-open-query`: 検索境界で切り詰めないこと、接触だけの区間を除外すること。
- `touching-patterns`, `weekly-touch`: 接するだけの窓は有効。パターンの入力順に依存せず開始昇順。
- `do-not-merge-patterns`, `do-not-merge-windows`: パターンや窓をまたぐ結合をしない。
- `disabled`, `disabled-overlap`, `disabled-free-only`: 無効化したパターンに対する例外。
- `integral-json-numbers`, `minimum-free`, `maximum-free`, `twenty-patterns`, `twenty-segments`: 数値・件数の有効境界。
- `unknown-fields`: 文書・パターン・セグメントの未知フィールドを無視。
- `missing-*`: 必須フィールドに既定値を補わない。
