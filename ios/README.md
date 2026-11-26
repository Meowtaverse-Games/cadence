# iOS

SwiftUI のアプリ本体と、Foundation だけに依存する Swift Package `CadenceCore`。
iOS 16 / macOS 13 以上、Swift 5 モード。Firebase Authentication / Firestore による時間割同期を本体だけで行う。

## ターゲットと端末内データ

- `Cadence`: 権限の取得、アプリ選択、時間割編集、現在状態、記録の共有。
- `DeviceActivityMonitor`: 開始/終了/毎日の見張りで判定し、終了済み監視を停止して次を補充。
- `ShieldConfiguration`: 「集中の時間です」と、連続するブロックの解除時刻を端末時間で表示。

3ターゲットは Family Controls と App Group `group.com.meowtaverse-games.cadence` を使う。
本体に2 extension を埋め込む。Firebase SDK 13.2.0（exactVersion）を SPM で導入し、
FirebaseAuth と FirebaseFirestore だけを本体にリンクする。extension と Shared は Firebase を import しない。
13.2.0 の Package.swift は iOS 15 以上・Xcode 26.2 以上を要求し、ここでは iOS 16 / Xcode 26.6 を使う。
Info.plist と entitlements は `project.yml` から生成する。署名と Team はビルド時だけ設定する。

`Shared/GroupStore.swift` は検証済み時間割（updatedAt なし）、FamilyActivitySelection、最終同期時刻を JSON 保存する。
編集用の `LocalTimetable` は CadenceCore の `SyncTimetable` の別名で、保存済み JSON の形は変えない。
同期の判断表とコードポイントを保つ比較・変換は CadenceCore、Firebase API と認証は `Cadence/FirebaseSync.swift` に置く。
初回はサーバーから確認し、違う時間割があれば利用者に選ばせる。選択中と初回アップロード確定待ちは編集を止める。
サインアウトは時間割・対象・監視・shield に触れない。詳細は [仕様の同期節](../docs/mvp-spec.md#同期) を参照。
ファイルの一時書き込み・置換とプロセス間ロックを使い、読み出せないファイルは欠損として記録する。
時間割がない/対象選択がない場合はブロックしない。本体は未認可なら監視を停止するが、
extension は権限状態の読み違いだけでは停止せず、時間割で判定する。無効な編集は保存せず直前の時間割を保つ。

`ActivityPlanner` は現在から14日を展開し、進行中を含む7区間までを計画する。
毎日00:00〜23:59の見張りと合わせて最大8件。登録失敗でも本体の shield 更新は続ける。
本体は内容が変わった時間割の保存・同期受信、選択変更・タイムゾーン変更で全監視を入れ替える。
起動・前面・BGAppRefreshTask は差分照合のみで、内容が同じ snapshot でも全解除しない。
本体は権限状態の publisher を監視し、起動・前面・権限変更の短時間の重複をまとめる。
extension は既存の必要な監視を維持して差分を補充する。保存した登録時のタイムゾーン identifier が
変わった場合は見張りを含め全再登録し、その前に identifier を保存して再入時の繰り返しを防ぐ。

記録は `os.Logger`（subsystem `com.meowtaverse-games.cadence`）と App Group に同じ内容を保存する。
カテゴリ app/monitor/shield、最新500件、オフセット付きISO 8601。
更新1回の記録を1件にまとめ、本文は4096文字まで、登録一覧は件数と区間名の先頭・末尾を残す。
shield の通常表示は記録せず、読み込み障害だけを記録する。選択の内容は件数だけを記録する。
「記録」画面で更新・テキスト共有・消去できる。実際の記録は公開リポジトリに貼らない。

開発用 IPA はルートの `codemagic.yaml` の `ios-development` を手動実行する。
Apple/Codemagicの設定、インストール、実機の確認表は [apple-setup.md](../docs/apple-setup.md) を参照。
CIではScreen Timeの実動作や署名を検証できない。

TestFlight の内部テストは [apple-setup.md の手順11](../docs/apple-setup.md#11-testflight-の前に必要なことpr-5)
の準備後、Codemagic の Start new build で対象ブランチと `ios-release` を選んで手動実行する。
App Store 用の署名、最新の TestFlight ビルド番号 + 1（初回は1）、3ターゲットの IPA 検査を経て、
Secret の `TESTFLIGHT_BETA_GROUP` で指定した内部グループに配る。マーケティング版は `project.yml` のまま。
内部テスト専用で export し、外部テスト・App Store の公開には使用しない。
同じ番号の並行取得を避けるため、`ios-release` は前の実行・TestFlight 処理の完了後に開始する。

## プロジェクトの生成とビルド

Mac に Xcode 26.6 と XcodeGen 2.44.1 を用意して、リポジトリのルートから実行する。
生成した `Cadence.xcodeproj` はコミットしない。Apple の Team や署名設定は含めない。

```sh
ios/scripts/generate-project.sh
xcodebuild build -project ios/Cadence.xcodeproj -scheme Cadence \
  -configuration Debug -destination 'generic/platform=iOS Simulator' \
  -onlyUsePackageVersionsFromResolvedFile CODE_SIGNING_ALLOWED=NO
```

`GoogleService-Info.plist` は任意のローカルファイルとして参照する。存在しなくても生成・ビルドでき、
Firebase を初期化せず端末だけで動く。Codemagic の実機用ビルドでは Secret からの生成を必須にする。
CI は plist 無し・合成 plist 有りを両方ビルドし、バンドルへの有無を確認する。
両 extension の実行ファイル（debug dylib を含む）の nm / otool と Frameworks の有無で Firebase 非混入も確認する。
CI は `macos-26` / Xcode 26.6、公式リリースを SHA-256 で照合した XcodeGen 2.44.1 を使用する。

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

リモート依存は `project.yml` の `exactVersion` とコミット済みの `ios/Package.resolved` で固定する。
生成スクリプトは lock をプロジェクトへコピーする。通常の CI / Codemagic は
`-onlyUsePackageVersionsFromResolvedFile` を使う。

Mac が無いときは CI の artifact から作り直す。

1. `project.yml` の exactVersion を更新し、`ios/Package.resolved` を削除して作業ブランチへコミット・push する。
2. iOS app ジョブは lock が無いときだけ `xcodebuild -resolvePackageDependencies` を実行し、
   `ios-package-resolved-absent` と `ios-package-resolved-synthetic` をアップロードする。
   その後「ios/Package.resolved がありません」と意図的に失敗する。
3. 片方の artifact を取得する（`<RUN_ID>` は自分の作業ブランチの run）。

   ```sh
   gh run download <RUN_ID> --name ios-package-resolved-absent --dir /tmp/astra-resolved-update
   cp /tmp/astra-resolved-update/Package.resolved ios/Package.resolved
   gh api repos/firebase/firebase-ios-sdk/git/ref/tags/<VERSION>
   ```

4. lock の `firebase-ios-sdk` の version が exactVersion と同じで、revision が公式タグの commit と
   一致することを確認する（annotated tag ならタグの先の commit まで確認する）。推移依存の差分も確認する。
5. lock を追加コミット・push し、最終 head の core と app の両構成が成功することを確認する。
   lock 不足時の意図的な失敗だけで完了としない。

Mac では生成後に `xcodebuild -resolvePackageDependencies -project ios/Cadence.xcodeproj -scheme Cadence` で解決し、
`ios/Cadence.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved` を `ios/Package.resolved` へ戻してもよい。

## デザインとプレビュー

配色と表示部品は `Cadence/Theme.swift`、本体と shield 共通のマーク描画は
`Shared/CadenceMark.swift` に置く。PNG は Pillow がある環境で再生成できる。

```sh
python3 -I ios/scripts/render-app-icon.py
```

Debug の起動引数 `-CadenceDesignPreview <画面名>` は実サービスを初期化せず、
通常画面と同じ表示ビューに合成データを渡す。画面名は `home-focus`、`home-free`、
`home-idle`、`editor`、`first-link`、`sync`、`shield`。
Release にはこの入口を含めない。shield のプレビューは OS の配置を再現するものではなく、
設定できる色・マーク・文言を確認するための SwiftUI 表示。
CI の `design-preview` は1種類の iPhone シミュレーターで起動し、本文の描画を確認して撮影する。
起動中の黒い画像は再撮影し、描画されなければジョブを失敗にする。
`ios-design-preview` artifact に11枚を保存する（14日間）。
`home-focus-large`、`editor-large`、`editor-cycle-large` はアクセシビリティ用の大きな文字で表示する。
`editor-cycle` と `editor-cycle-large` はセグメントとバーの部分へスクロールして撮影する。

時間帯バー用の `TimetableDisplay` は表示専用の純粋関数。
既存の `TimetableExpander` のブロック区間と、その補集合を使い、登録・保存の規則は変えない。
