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
| 同期 | Firebase（Authentication のメール・パスワード、Cloud Firestore） |
| 時間割の編集 | iPhone アプリだけで行う。Windows は読み取り専用 |
| ブロック対象 | 端末ごとに選ぶ（iPhone はアプリ選択画面、Windows は exe 名） |
| 配布 | iOS は Codemagic でビルドし TestFlight で配布。Windows は GitHub Actions の成果物 |
| bundle ID | `com.meowtaverse-games.cadence` |

## 時間割のデータ

Firestore の `users/{uid}/config/timetable` の1文書に、時間割全体を保存する。

```json
{
  "schemaVersion": 1,
  "timeZone": "Asia/Tokyo",
  "patterns": [
    {
      "id": "6f1c…",
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

- `weekdays` は ISO 8601 の曜日（1 = 月曜 … 7 = 日曜）。`start` が属する日の曜日で判定する。
- `start` と `end` は `timeZone` での壁時計の時刻（`HH:mm`）。`end <= start` は翌日の `end` までを表す
  （例: 23:00〜01:00）。
- 時刻の計算は、端末のタイムゾーンではなく、文書の `timeZone` で行う。iPhone と PC で
  タイムゾーンの設定が違っても、同じ瞬間にブロックが始まるようにするため。

### 検証規則（保存時に iPhone 側で検査し、読み込み時に両方で検査する）

- `segments` は1個以上。各 `minutes` は 1〜720 の整数。
- `kind: "block"` の `minutes` は 15 以上（iOS の `DeviceActivity` は15分未満の区間を監視できないため）。
- 1パターンの窓（`start` から `end`）は 0 分より長く、24 時間以下。
- 有効なパターン同士の窓が、どの日でも重ならない（日をまたぐ窓も含む）。
- 読み込み時に検証に失敗した文書は使わず、直前に有効だった時間割を使い続ける。

### 展開規則（時間割 → ブロック区間）

指定した期間について、ブロックする区間の一覧（開始と終了の瞬間）を求める。

1. 有効なパターンごとに、各日の窓を求める。
2. 窓の開始から `segments` を順に並べ、窓の終了まで繰り返す。
3. 窓の終了で切れたセグメントは、終了で打ち切る。打ち切られて 15 分未満になった
   `block` は捨てる（自由時間として扱う）。
4. 隣り合う `block` 区間は1つにまとめる。

この規則は Swift（`ios/CadenceCore`）と C#（`windows/Cadence.Core`）に二重に実装する。
両者が一致することを、`spec/fixtures/*.json` の共通テストケース（入力の時間割、期間、
期待するブロック区間）で確かめる。共通テストケースには少なくとも次を含める。

- 基本形（10分自由・20分ブロック）、ブロックから始まる形（25分ブロック・5分自由）
- 窓の終わりで切れて 15 分以上残るブロック、15 分未満で捨てられるブロック
- 日をまたぐ窓（23:00〜01:00）と、曜日の境目
- 隣り合うブロックの結合（`block` だけのパターン）
- 検証エラーになる入力（14 分のブロック、窓の重なり、空の `segments`）

## iOS アプリ

### 構成

- アプリ本体（SwiftUI）
- `DeviceActivityMonitor` extension: 区間の開始で shield をかけ、終了で外し、次の区間を登録する
- `ShieldConfiguration` extension: ブロック画面に「Cadence: 集中の時間です」と解除時刻を表示する
- App Group `group.com.meowtaverse-games.cadence` で、本体と extension が次を共有する:
  展開済みのブロック区間、`FamilyActivitySelection`、最終同期時刻
- Firebase は本体だけが使う。extension は Firebase を読み込まない（extension の
  メモリ上限が小さいため）

### スケジュールの登録

- ブロック区間ごとに、繰り返しのない `DeviceActivitySchedule` を1つ登録する
  （名前は区間の開始時刻から作る）。
- 同時に登録するのは、これから始まる区間の先頭から最大 8 件までとする
  （OS の同時監視数の上限を避けるため）。
- `intervalDidEnd` で shield を外し、App Group の区間一覧から次の区間を登録して、8 件を保つ。
- アプリの起動時、前面に戻ったとき、時間割を保存したとき、Firestore から変更を受け取ったときは、
  登録をすべて解除してから登録し直す。
- ブロック中の区間がある場合は、その区間を元の開始時刻のまま登録し、すぐに shield がかかる
  ようにする。時間割の変更でブロック中の区間が無くなった場合は、その場で shield を外す。
- 15 分の下限、同時監視数の上限、開始時刻が過去の区間を登録したときの動きは、
  PR 4 の実機確認で確かめ、違っていればこの節を直す。

### 画面

- サインイン（メールアドレスとパスワード）
- Screen Time の権限の取得
- 今の状態（ブロック中か自由時間か、次の切り替え時刻）
- 時間割の一覧と編集: 名前、曜日、開始・終了、セグメント（テンプレート: 25/5、50/10、
  自由10・ブロック20、および手入力）。保存時に検証し、エラーを画面に示す
- ブロックするアプリの選択（`FamilyActivityPicker`）

## Windows アプリ

- タスクトレイに常駐する。アイコンのツールチップに、今の状態と次の切り替え時刻を出す。
- サインイン状態を保つ。Firebase Authentication の REST API でサインインし、refresh token を
  DPAPI（CurrentUser）で暗号化して `%APPDATA%\Cadence\` に保存する。
- 60 秒ごとに、Firestore の REST API で時間割の文書を取得する。取得に成功した有効な時間割を
  `%APPDATA%\Cadence\` にキャッシュし、ネットワークが無いときはキャッシュで動き続ける。
- ブロック中は 2 秒ごとにプロセスを調べ、ブロック対象の exe 名（大文字小文字を区別しない）に
  一致するプロセスを終了し、通知を出す（同じ exe の通知は1分に1回まで）。終了できなかった
  プロセス（管理者権限で動くゲームなど）は、ログに残して通知で知らせる。
- 設定画面: サインイン、ブロックする exe 名の一覧（実行中のプロセスから選んで追加できる）、
  Windows のログオン時に起動する（`HKCU\...\Run`）のオン・オフ。
- Firebase の接続情報（API キー、プロジェクト ID）は `%APPDATA%\Cadence\firebase.json` から読む。
  リポジトリには含めない。
- 時間割もキャッシュも無いときはブロックせず、トレイの表示で知らせる。

## Firebase

- Authentication: メール・パスワードだけを有効にする。アカウントは利用者の1件だけを手動で作る。
- Firestore のルール（`firebase/firestore.rules`）: `users/{uid}` 以下は、その uid で
  サインインした本人だけが読み書きできる。それ以外は拒否する。
- リージョンは `asia-northeast1`。

## 配布

- iOS: Codemagic の `ios-release` ワークフローで App Store 配布の署名を行い、
  App Store Connect にアップロードして TestFlight の内部テストで配る。
  Family Controls の配布用 entitlement が Apple に承認されるまで、TestFlight には出せない。
- Windows: GitHub Actions で `dotnet publish -r win-x64 --self-contained -p:PublishSingleFile=true`
  の成果物を作る。

## MVP の対象外

- 集中時間の記録、カレンダー、ヒートマップ
- Live Activity、ウィジェット
- 解除のしにくさの強化（待ち時間、アプリ削除の防止、Windows サービス化）
- 保護者による管理（`.child` 認可）
- Windows からの時間割の編集、Mac・Android 版

## 実装の順序

| PR | 内容 | 依存 |
|---|---|---|
| 1 | 共通テストケースと C# の `Cadence.Core`、Windows の CI | なし |
| 2 | Swift の `CadenceCore`、XcodeGen のプロジェクト、iOS の CI | 1 の共通テストケース |
| 3 | Windows のトレイアプリ（同期、ブロック、設定画面）と Firestore のルール | 1 |
| 4 | iOS の Screen Time 連携（extension、スケジュール、shield、画面、同期） | 2 |
| 5 | Codemagic の設定と TestFlight 配布 | 4、entitlement の承認 |
