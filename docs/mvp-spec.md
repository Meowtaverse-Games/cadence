# Cadence MVP 仕様

## 目的

先に決めた時間割（例: 19:00〜22:00 の間、10分は自由・20分はブロックを繰り返す）に従い、
iPhone と Windows PC の指定アプリを、利用者が何も操作しなくても自動でブロック・解除する。
ポモドーロの「集中」と「休憩」を、本人の意思ではなく時間割で強制する。

## 決定済みの前提

| 項目 | 決定 |
|---|---|
| 利用形態 | 利用者本人が自分の端末に設定する（保護者による管理は MVP の対象外） |
| iOS | Swift / SwiftUI、iOS 16 以上、`FamilyControls` の `.individual` 認可 |
| Windows | C# / .NET 8 のタスクトレイ常駐アプリ（Windows サービスにはしない） |
| 同期 | Firebase（Authentication のメール・パスワード、Cloud Firestore）。同期するのは時間割だけ |
| 時間割の編集 | iPhone アプリだけで行う。Windows は読み取り専用 |
| ブロック対象 | 端末ごとに選び、その端末にだけ保存する（同期しない）。iPhone はアプリ選択画面、Windows は exe 名 |
| タイムゾーン | MVP は `Asia/Tokyo`（UTC+9 固定、夏時間なし）だけを扱う |
| 配布 | iOS は Codemagic でビルドし TestFlight で配布。Windows は GitHub Actions の成果物 |
| 実機確認 | 配布用 entitlement の承認前は、Codemagic で開発用の署名のビルドを作り、登録した端末に入れて確かめる |
| bundle ID | `com.meowtaverse-games.cadence` |

## 時間割のデータ

Firestore の `users/{uid}/config/timetable` の1文書に、時間割全体を保存する。

```json
{
  "schemaVersion": 1,
  "timeZone": "Asia/Tokyo",
  "patterns": [
    {
      "id": "p1",
      "name": "平日の夜",
      "enabled": true,
      "weekdays": [1, 2, 3, 4, 5],
      "start": "19:00",
      "end": "22:00",
      "segments": [
        { "kind": "free", "minutes": 10 },
        { "kind": "block", "minutes": 20 }
      ]
    }
  ],
  "updatedAt": "<server timestamp>"
}
```

- `weekdays` は ISO 8601 の曜日（1 = 月曜 … 7 = 日曜）。窓の `start` が属する日の曜日で判定する。
- `start` と `end` は `timeZone` での時刻（`HH:mm`）。`end < start` は翌日の `end` まで、
  `end == start` は翌日の同じ時刻までの 24 時間を表す。
- `timeZone` は MVP では `Asia/Tokyo` だけを許す。実装は、IANA のタイムゾーンデータベースを
  引かずに UTC+09:00 の固定オフセットで計算してよい（Windows で ICU に依存しないため）。
  将来ほかのゾーンを許すときは、夏時間の扱いを決めてから `schemaVersion` を上げる。
- 時刻の計算は端末のタイムゾーンではなく `timeZone` で行う。iPhone と PC のタイムゾーンの
  設定が違っても、同じ瞬間にブロックが始まるようにするため。
- 分はすべて経過時間で数える（UTC+9 固定なので壁時計と一致する）。
- 上の例の値は合成データである。

### 検証規則

文書は全体として「有効」か「無効」のどちらかになる。一部のパターンだけを使うことはしない。
次のどれかに当たれば無効とする。未知の追加フィールドは無視する。

型の定義（すべてのフィールドに共通）:

- 整数: JSON の数値で、値が整数であるもの。`10.0` は `10` と同じ整数として扱い、`10.5` は整数でない。
  真偽値や文字列は整数ではない。Firestore には integerValue で保存する。
- 真偽値: JSON の `true` / `false` だけ。`0` / `1` は真偽値ではない。
- 文字数: Unicode のコードポイントの数（Swift は `unicodeScalars.count`、C# は
  `EnumerateRunes().Count()`）。
- 文字列の比較: コードポイント列が同じときだけ等しいとする（Unicode の正規化をしない）。`id` の重複、
  `timeZone`、`kind` の判定に使う。Swift の `String ==` は正準等価で比べるので使わない
  （`"é"` U+00E9 と `"é"` は別の `id`）。C# の `StringComparer.Ordinal` と同じ結果になる。
- iOS では Firestore の SDK と `JSONSerialization` が整数・小数・真偽値をどれも `NSNumber` で返すので、
  型の判定には注意する（PR 2 と PR 4）。

- 文書
  - `schemaVersion` が整数の `1` でない
  - `timeZone` が文字列 `"Asia/Tokyo"` でない
  - `patterns` が配列でない、または 21 件以上ある（0 件は有効で、ブロックが無い状態を表す）
- パターン（有効・無効を問わず全件）
  - `id` が空でない文字列でない、またはほかのパターンの `id` と重複する
  - `name` が 1〜40 文字の文字列でない
  - `enabled` が真偽値でない
  - `weekdays` が空、1〜7 の範囲外の値や整数でない値を含む、または重複を含む
  - `start` か `end` が正規表現 `^([01][0-9]|2[0-3]):[0-5][0-9]$` に一致しない
    （`9:00` と `24:00` は無効）
  - `segments` が空、または 21 件以上ある
  - セグメントの `kind` が `"free"` と `"block"` 以外
  - セグメントの `minutes` が整数でない、または 1〜720 の範囲外
  - `kind: "block"` の `minutes` が 15 未満（iOS の `DeviceActivity` は 15 分未満の区間を監視できないため）
- 有効なパターン（`enabled: true`）だけに課す規則
  - `block` のセグメントが1つも無い
  - ほかの有効なパターンと窓が重なる。窓は半開区間 `[開始, 終了)` として、1週間の
    くり返しの上で比べる（日曜から月曜への折り返しを含む）。終わりと始まりが接するだけ
    （終了 == 開始）は重なりとしない

iPhone は保存の前に検証し、無効なら保存せずに理由を画面に示す。両方の端末は読み込み時にも検証し、
無効な文書は使わずに、直前に有効だった時間割を使い続ける。そのとき Windows はトレイの表示で
「時間割を読み込めません」と知らせる。

### 展開規則（時間割 → ブロック区間）

入力は有効な時間割と期間 `[from, to)`、出力はブロック区間（半開区間 `[start, end)`）の一覧で、
開始時刻の昇順に並べる。区間の時刻は UTC の ISO 8601（秒まで、`Z` 付き。例:
`2026-01-05T10:10:00Z`）で表す。

1. 有効なパターンごとに、`from` の日付（`timeZone` で）の前日から `to` の日付までの各日について、
   曜日が `weekdays` に含まれる日の窓を求める。前日を含めるのは、日をまたぐ窓を拾うためである。
2. 窓の開始から `segments` を順に並べ、窓の終了まで繰り返す。窓の終了を越えるセグメントは
   終了で打ち切る。
3. 同じ窓の中で、終了と開始が一致して隣り合う `block` 区間を1つにまとめる。窓やパターンを
   またいで結合はしない（したがって区間は最長 24 時間）。
4. まとめたあとで 15 分未満の `block` 区間を捨てる（自由時間として扱う）。
5. 期間 `[from, to)` と重なる区間を、期間の境界で切り詰めずにそのまま返す。

例: `block` 25 分だけのパターンを 19:00〜22:00 に置くと、2. で 21:55〜22:00 の 5 分が
できるが、3. で前の区間とまとまるので、結果は 19:00〜22:00 の1区間になる。

この規則は Swift（`ios/CadenceCore`）と C#（`windows/Cadence.Core`）に二重に実装する。
両者が一致することを、`spec/fixtures/*.json` の共通テストケースで確かめる。
各ケースは、入力の時間割、期間、期待する結果（ブロック区間の一覧、または「無効」）を持つ。
少なくとも次を含める。

- 基本形（自由10分・ブロック20分）、ブロックから始まる形（ブロック25分・自由5分）
- 窓の終わりで切れて 15 分以上残るブロック、15 分未満で捨てられるブロック
- `block` だけのパターン（3. で1区間にまとまる）
- 日をまたぐ窓（23:00〜01:00）と 24 時間の窓、期間の開始が日をまたぐ窓の途中にある場合
- 曜日の境目（日曜から月曜）、期間の境界をまたぐ区間（切り詰めない）
- 検証規則の各項目について、無効になる入力を1つずつ（14 分のブロック、窓の重なり、
  日曜から月曜への折り返しでの重なり、接するだけの窓は有効、空の `weekdays`、`24:00`、
  未知の `kind`、`minutes: 10.5`、`weekdays: [1.5]`、`enabled: 1`、重複した `id`、
  `block` の無い有効なパターンなど）
- 型の定義の境目（`minutes: 10.0` と `schemaVersion: 1.0` は有効、絵文字や結合文字を含む
  40 コードポイントの `name` は有効で 41 コードポイントは無効）

## iOS アプリ

### 構成

- アプリ本体（SwiftUI）
- `DeviceActivityMonitor` extension: 区間の開始と終了で shield をかけ外しし、次の区間を登録する
- `ShieldConfiguration` extension: ブロック画面に「集中の時間です」と解除時刻を表示する
- App Group `group.com.meowtaverse-games.cadence` で、本体と extension が次を共有する:
  検証済みの時間割（JSON）、`FamilyActivitySelection`、最終同期時刻
- 本体と extension は、`CadenceCore` で時間割から区間を展開する。展開済みの区間を保存して
  使い切ることはしない（アプリを開かなくてもブロックが続くようにするため）
- Firebase は本体だけが使う。extension は Firebase を読み込まない（extension の
  メモリ上限が小さいため）。`CadenceCore` は Foundation だけに依存する

### スケジュールの登録

- ブロック区間ごとに、繰り返しのない `DeviceActivitySchedule` を1つ登録する
  （名前は区間の開始時刻から作る）。開始と終了の `DateComponents` は、区間の瞬間を
  登録する時点の `Calendar.current`（端末のタイムゾーン）で年・月・日・時・分に変換して作る。
- 同時に登録するのは、これから始まる区間（ブロック中の区間を含む）の先頭から最大 7 件と、
  見張り用の activity 1 件の計 8 件までとする（OS の同時監視数の上限を避けるため）。
  区間は現在から 14 日先までを展開して選ぶ。
- 見張り用の activity は、毎日くり返す schedule（例: 毎日 00:00〜23:59）とし、そのイベントでも
  判定と登録のやり直しを行う。電源が切れていた間に登録済みの区間をすべて過ぎても、
  利用者の操作なしで連鎖を再開するためである。さらに本体は `BGAppRefreshTask` でも登録をやり直す
  （実行の時刻は OS が決める）。
- shield をかけるか外すかは、イベントの種類で決めず、毎回「時間割と時刻から、その時点が
  ブロック区間の中か」で決める（冪等にする）。判定する時点は、`intervalDidStart` では
  `max(現在時刻, その activity の開始時刻)`、それ以外では現在時刻とする（イベントが開始より
  わずかに早く届いても、区間を取り逃さないため）。extension は `intervalDidStart` と
  `intervalDidEnd` のどちらでもこの判定を行い、終わった activity を `stopMonitoring` し、
  件数を保つように次の区間を登録する。
- アプリの起動時、前面に戻ったとき、時間割を保存したとき、Firestore から変更を受け取ったとき、
  端末のタイムゾーンが変わったときは、登録をすべて解除してから登録し直し、本体からも
  同じ判定で `ManagedSettingsStore` の shield を直接かけ外しする。
- ブロック中の区間は元の開始時刻のまま登録する。

### 実機で確かめること（PR 4）

次は資料だけでは確かめきれないので、実機で確かめ、違っていればこの節を直す。

- 15 分の下限と、24 時間の区間を登録できること
- 同時監視数の上限
- 開始時刻が過去の区間を登録したとき、すぐに `intervalDidStart` が呼ばれるか
- `stopMonitoring` が `intervalDidEnd` を呼ぶか。呼ぶ場合も、冪等な判定によってブロック中の
  shield が外れたままにならないこと
- 区間の境目（前の区間の終了と次の区間の開始が同時）で shield が外れたままにならないこと
- `intervalDidStart` が区間の開始より早く届くことがあるか
- 電源を切る、または再起動して、登録済みの区間をすべて過ぎたあと、操作なしで次のブロックが
  かかるか（見張り用の activity と `BGAppRefreshTask` で連鎖が再開するか）
- 端末のタイムゾーンを、アプリを開いたまま変えた場合と、アプリを開かないまま変えた場合の
  それぞれで、同じ瞬間にブロックが始まるか
- `DateComponents` に `timeZone`（Asia/Tokyo）を入れた場合に、DeviceActivity がそれを尊重するか。
  尊重するなら、端末のタイムゾーンで変換する方式より堅いので、そちらに切り替える

### 画面

- サインイン（メールアドレスとパスワード）
- Screen Time の権限の取得
- 今の状態（ブロック中か自由時間か、次の切り替え時刻）
- 時間割の一覧と編集: 名前、曜日、開始・終了、セグメント（テンプレート: ブロック25/自由5、
  ブロック50/自由10、自由10/ブロック20、および手入力）。保存時に検証し、エラーを画面に示す
- ブロックするアプリの選択（`FamilyActivityPicker`）

## Windows アプリ

- タスクトレイに常駐する。アイコンのツールチップに、今の状態と次の切り替え時刻を出す。
- サインイン状態を保つ。Firebase Authentication の REST API でサインインし、refresh token を
  DPAPI（CurrentUser）で暗号化して `%APPDATA%\Cadence\` に保存する。
- 60 秒ごとに、Firestore の REST API で時間割の文書を取得する。取得に成功した有効な時間割を
  `%APPDATA%\Cadence\` にキャッシュし、ネットワークが無いときはキャッシュで動き続ける。
  Firestore の typed JSON は普通の JSON に直してから検証する（`integerValue` は整数、`doubleValue` は
  丸めずに数値のまま）。
- 取得の結果ごとの扱い（ブロックが外れる方向に倒さない）:
  - 文書が無い（404）: キャッシュがあれば使い続け、「サーバーに時間割がありません」と示す。
  - ネットワークの失敗、タイムアウト、5xx、429: キャッシュで動き続け、次の周期で再試行する。
  - 認証の失敗（401 / 403、refresh token の失効）: サインインが必要な状態にして一度だけ通知する。
    キャッシュの時間割でのブロックは続ける（サインアウトしてもブロックは外れない）。
- ブロック中は 2 秒ごとにプロセスを調べ、ブロック対象に一致するプロセスを、子プロセスも含めて終了し、通知を出す
  （同じ exe の通知は1分に1回まで）。終了できなかったプロセス（管理者権限で動くゲームなど）は、
  ログに残して通知で知らせる。
- exe 名の扱い: 保存する名前は拡張子 `.exe` を含む形（例: `steam.exe`）にそろえる。入力に
  `.exe` が無ければ付け、前後の空白を除く。比べるときは `Process.ProcessName` に `.exe` を
  付けた名前と、大文字小文字を区別せずに比べる。
- 設定画面: サインイン、ブロックする exe 名の一覧（実行中のプロセスから選んで追加できる）、
  Windows のログオン時に起動する（`HKCU\...\Run`）のオン・オフ。ブロックする exe 名は
  `%APPDATA%\Cadence\` にだけ保存する。
- Firebase の接続情報（Web API キー、プロジェクト ID）は `%APPDATA%\Cadence\cadence-firebase.json`
  （`{ "apiKey": "...", "projectId": "..." }`）から読む。リポジトリには含めない
  （Firebase CLI の `firebase/firebase.json` とは別物）。
- 時間割もキャッシュも無いときはブロックせず、トレイの表示で知らせる。

## Firebase

- Authentication: メール・パスワードだけを有効にし、利用者のアカウントを1件だけ手動で作る。
  第三者が新しいアカウントを作れないよう、サインアップを無効にする
  （Identity Platform へのアップグレードが要るかは要確認）。
- Firestore のルール（`firebase/firestore.rules`）: サインインした本人が、自分の
  `users/{uid}/config/timetable` の1文書だけを読み（get）、作成・更新できる。削除と一覧は
  できない。それ以外はすべて拒否する。書き込みでは形を最小限だけ確かめる（キーは `schemaVersion`、
  `timeZone`、`patterns`、`updatedAt` だけ、`schemaVersion == 1`、`timeZone == 'Asia/Tokyo'`、
  `patterns` は 20 件以下のリスト、`updatedAt` はサーバー時刻）。パターンの中身はアプリが検証する。
  ルールはエミュレーターで自動テストする（`firebase/test/`、CI は `.github/workflows/firebase.yml`）。
- リージョンは `asia-northeast1`。
- 準備の手順（プロジェクトの作成、メール・パスワードの有効化、アカウントの作成、
  サインアップの無効化、ルールのデプロイ、各端末への接続情報の配置）は、PR 3a で
  `docs/firebase-setup.md` に書く。プロジェクトの作成とアカウントの作成は人が行い、
  ルールのデプロイは人の承認を得てから行う。

## 配布

- iOS: Codemagic の `ios-release` ワークフローで App Store 配布の署名を行い、
  App Store Connect にアップロードして TestFlight の内部テストで配る。
  Family Controls の配布用 entitlement が Apple に承認されるまで、TestFlight には出せない。
  アプリ本体と2つの extension の bundle ID のそれぞれに承認が要る見込み（要確認）なので、
  3つとも申請する。
- iOS の SDK の版: Firebase の SDK は XcodeGen の `project.yml` で `exactVersion` を指定して固定する。
  生成した Xcode プロジェクトはコミットしないので、`ios/Package.resolved` をコミットし、
  `ios/scripts/generate-project.sh` がプロジェクトの生成後にそれを
  `Cadence.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved` にコピーする。
  CI と Codemagic のビルドは `-onlyUsePackageVersionsFromResolvedFile` を付け、Resolved ファイルに無い版を
  取りに行かない。依存を足したり上げたりするときは、手元で解決し直した `Package.resolved` を
  `ios/` に戻してコミットする（仕組みは PR 2 で作り、ファイルは Firebase を入れる PR 4 で初めてできる）。
- `GoogleService-Info.plist` はリポジトリに無いので、`project.yml` では `optional: true` を付けて
  参照するか、CI ではダミーを置く。ファイルが無くてもプロジェクトの生成と CI のビルドが通るようにする。
- 実機確認用: Codemagic に開発用の署名のワークフローを置き、登録した端末向けのビルドを作って入れる
  （PR 4。Family Controls の開発用 entitlement は承認なしで使える）。
- Windows: GitHub Actions で `dotnet publish -r win-x64 --self-contained -p:PublishSingleFile=true`
  の成果物を作る。

## MVP の対象外

- 集中時間の記録、カレンダー、ヒートマップ
- Live Activity、ウィジェット
- 解除のしにくさの強化（待ち時間、アプリ削除の防止、Windows サービス化）
- 保護者による管理（`.child` 認可）
- Windows からの時間割の編集、Mac・Android 版
- `Asia/Tokyo` 以外のタイムゾーン

## 実装の順序

| PR | 内容 | 依存 |
|---|---|---|
| 1 | 共通テストケースと C# の `Cadence.Core`、Windows の CI | なし |
| 2 | Swift の `CadenceCore`、XcodeGen のプロジェクト、iOS の CI | 1 の共通テストケース |
| 3a | Firestore のルールとエミュレーターでのテスト、`docs/firebase-setup.md` | なし |
| 3b | Windows のトレイアプリ（同期、ブロック、設定画面） | 1 |
| 4 | iOS の Screen Time 連携（extension、スケジュール、shield、画面、同期）と、Codemagic の開発用署名のワークフロー | 2、3a のルールと Firebase の準備 |

PR 2、3a、3b は互いに依存しないので並行して進める。
| 5 | Codemagic の設定と TestFlight 配布 | 4、entitlement の承認 |
