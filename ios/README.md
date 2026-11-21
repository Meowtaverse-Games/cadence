# iOS

SwiftUI のアプリ本体と、Foundation だけに依存する Swift Package `CadenceCore`。
iOS 16 / macOS 13 以上、Swift 5 モード。Firebase は PR 4b で追加する。

## ターゲットと端末内データ

- `Cadence`: 権限の取得、アプリ選択、時間割編集、現在状態、記録の共有。
- `DeviceActivityMonitor`: 開始/終了/毎日の見張りで判定し、終了済み監視を停止して次を補充。
- `ShieldConfiguration`: 「集中の時間です」と、連続するブロックの解除時刻を端末時間で表示。

3ターゲットは Family Controls と App Group `group.com.meowtaverse-games.cadence` を使う。
本体に2 extension を埋め込み、Firebase や追加の外部パッケージは使わない。
Info.plist と entitlements は `project.yml` から生成する。署名と Team はビルド時だけ設定する。

`Shared/GroupStore.swift` は検証済み時間割（updatedAt なし）と FamilyActivitySelection を JSON 保存する。
ファイルの一時書き込み・置換とプロセス間ロックを使い、読み出せないファイルは欠損として記録する。
時間割がない/対象選択がない/未認可ならブロックしない。無効な編集は保存せず直前の時間割を保つ。

`ActivityPlanner` は現在から14日を展開し、進行中を含む7区間までを計画する。
毎日00:00〜23:59の見張りと合わせて最大8件。登録失敗でも本体の shield 更新は続ける。
本体は起動・前面・保存・選択変更・タイムゾーン変更で全監視を入れ替え、BGAppRefreshTaskでも更新する。
extension は既存の必要な監視を維持して差分を補充し、stopMonitoringによる再入イベントの連鎖を避ける。

記録は `os.Logger`（subsystem `com.meowtaverse-games.cadence`）と App Group に同じ内容を保存する。
カテゴリ app/monitor/shield、最新500件、オフセット付きISO 8601。選択の内容は件数だけを記録する。
「記録」画面で更新・テキスト共有・消去できる。実際の記録は公開リポジトリに貼らない。

開発用 IPA はルートの `codemagic.yaml` の `ios-development` を手動実行する。
Apple/Codemagicの設定、インストール、実機の確認表は [apple-setup.md](../docs/apple-setup.md) を参照。
CIではScreen Timeの実動作や署名を検証できない。

## プロジェクトの生成とビルド

Mac に Xcode 16.4 と XcodeGen 2.44.1 を用意して、リポジトリのルートから実行する。
生成した `Cadence.xcodeproj` はコミットしない。Apple の Team や署名設定は含めない。

```sh
ios/scripts/generate-project.sh
xcodebuild build -project ios/Cadence.xcodeproj -scheme Cadence \
  -configuration Debug -destination 'generic/platform=iOS Simulator' \
  -onlyUsePackageVersionsFromResolvedFile CODE_SIGNING_ALLOWED=NO
```

`GoogleService-Info.plist` は任意のローカルファイルとして参照する。存在しなくても生成・ビルドできる。
CI は `macos-15` / Xcode 16.4、公式リリースを SHA-256 で照合した XcodeGen 2.44.1 を使用する。

## CadenceCore のテストと API

```sh
cd ios/CadenceCore
swift test
```

XCTest が `#filePath` から `spec/fixtures/*.json` を列挙する。リソースへのコピーは不要。
共通ケースは `parse(data:)` / `parse(object:)` の両方で検証する。
負荷テストは debug ビルドで20パターン×20セグメントを14日分展開する。
解析を含めず、ウォームアップ後の展開時間を出力する（目標50 ms、CIの失敗判定500 ms）。

```swift
let result = TimetableParser.parse(data: jsonData)
// Firestore の辞書は未知の updatedAt 型も含めて、そのまま parse(object:) に渡せる。
if let timetable = result.timetable {
    let intervals = try TimetableExpander.expand(timetable, from: from, to: to)
    let blocked = TimetableExpander.isBlockedAt(timetable, now: instant)
} else {
    // 呼び出し元が直前の有効な時間割を保持し、result.errorCode を表示する。
}
```

`Timetable` は検証成功時だけ作成できる不変データ。未知フィールドは無視する。
整数・真偽値を区別し、文字数は Unicode スカラー数、文字列は正規化せずコードポイント列で比較する。
理由コードは `spec/fixtures/README.md` と同じ。JSON の構文エラーや対になっていないサロゲートは
`invalid_json`、ルートがオブジェクト以外なら `invalid_document`。

展開は固定 UTC+09:00 で計算する。返す区間は開始昇順、半開区間で、検索期間の境界では切り詰めない。
空の検索期間は空配列、逆順・非有限・表現不能な期間は `ExpansionError.invalidRange` を throw する。
`isBlockedAt` と `BlockInterval.contains` は開始を含み終了を含まず、システム時計を読まない。

## リモート依存を追加・更新するとき

現在はローカルパッケージだけなので `ios/Package.resolved` は不要。
リモート依存は `project.yml` の `exactVersion` で固定し、次の手順で推移依存も固定する。

1. `ios/scripts/generate-project.sh` でプロジェクトを生成する。
2. Mac で明示的に依存解決する（この更新操作だけは版の取得を許す）。

   ```sh
   xcodebuild -resolvePackageDependencies -project ios/Cadence.xcodeproj -scheme Cadence
   cp ios/Cadence.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved ios/Package.resolved
   ```

3. 更新の場合も `exactVersion` の変更後に同じ手順を行い、差分を確認して
   `ios/project.yml` と `ios/Package.resolved` を一緒にコミットする。
4. 生成スクリプトをもう一度実行し、通常ビルドの固定オプションで成功することを確認する。

生成スクリプトは `ios/Package.resolved` があれば生成プロジェクト内の同じファイルへコピーする。
通常ビルドと CI は `-onlyUsePackageVersionsFromResolvedFile` を使い、固定ファイルにない版の取得を防ぐ。
