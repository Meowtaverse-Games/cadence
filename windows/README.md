# Cadence.Core

OS 非依存の .NET 8 ライブラリ。仕様は `docs/mvp-spec.md`、共有ケースの形式と
検証理由コードは `spec/fixtures/README.md` を参照する。

```csharp
var result = TimetableParser.Parse(json);
if (result.Valid)
{
    var intervals = TimetableExpander.Expand(result.Timetable!, from, to);
    var isBlocked = TimetableExpander.IsBlockedAt(result.Timetable!, now);
}
else
{
    // 呼び出し元が直前に有効だった時間割を保持し、この理由を表示する。
    var errorCode = result.ErrorCode;
}
```

`Timetable` は検証成功時だけ作成できる不変データ。入力は通常の JSON 文字列で、
Firestore の typed JSON の変換は呼び出し元で行う。
`from` / `to` / `now` は `DateTimeOffset` として明示的に渡す。
UTC+09:00 の固定オフセットで窓を求め、結果は UTC の `BlockInterval` の一覧で返す。
時刻の文字列化が必要な場合は `yyyy-MM-dd'T'HH:mm:ss'Z'` と invariant culture を使う。

`Expand` は検索期間と重なる区間全体を返す。空の期間は空配列、逆順の期間は
`ArgumentException`。`IsBlockedAt` は開始を含み終了を含まない判定で、システム時計を読まない。

SDK のある環境、または Windows CI で次を実行する。

```sh
dotnet restore windows/Cadence.sln --locked-mode
dotnet build windows/Cadence.sln --configuration Release --no-restore
dotnet test windows/Cadence.sln --configuration Release --no-build --no-restore
```

依存更新時は SDK のある環境で `dotnet restore windows/Cadence.sln --force-evaluate` を実行し、
各プロジェクトの `packages.lock.json` の差分をコミットする。
テストは共有 JSON 全件に加え、現在時刻の境界、異なるオフセット、年をまたぐ6週間、
20パターン×20セグメントの14日間展開を確認する。
性能目標は50 ms、CI上の失敗判定は500 ms。JITのウォームアップ後、展開だけを計測する。

## Windows トレイアプリ

Windows x64 用の確認用ビルドは GitHub Actions の **Windows** workflow にある
`cadence-windows-win-x64` 成果物からダウンロードする（保存期間14日）。ZIP を展開し、
書き込み可能で移動しないフォルダーに置いて `Cadence.Tray.exe` を起動する。
.NET ランタイムの別途インストールは不要。署名していないため SmartScreen の警告が
表示されることがある。取得元と対象コミットを確認してから実行する。
管理者権限は不要で、二重起動はしない。

接続設定は `%APPDATA%\Cadence\cadence-firebase.json` から読む。
`%APPDATA%` は通常 `C:\Users\<ユーザー>\AppData\Roaming` を指す。
`AppData\Cadence` の直下ではない。設定画面の「設定フォルダを開く」で、
実際の読み込み先のフォルダをエクスプローラーで開ける（無ければ作成する）。
以下の値は置き換え用の例であり、実際の接続情報はコミットしない。

```json
{ "apiKey": "<Firebase Web API key>", "projectId": "<Firebase project ID>" }
```

設定画面とトレイの右クリックメニューには、ファイルが無い・読めない・JSON が不正・
`apiKey` / `projectId` が無い、文字列でない、空、という理由と実際の絶対パスを表示する。
JSON の不正は取得できる場合に行とバイト位置（どちらも1から）を表示し、値は表示しない。
ファイルが無い場合は、同じフォルダの `cadence-firebase.json.txt` と
`%USERPROFILE%\AppData\Cadence\cadence-firebase.json` も調べ、置き間違いを案内する。
エクスプローラーの「表示」→「表示」→「ファイル名拡張子」で `.txt` が付いていないか確認できる。

設定ファイルの配置・変更後は「接続設定を読み直す」を押す。再起動せずにサインインできる。
読み直しは実行中の同期や認証の完了を待って接続クライアントを交換し、次の同期でトークンを取り直す。
保存済みのセッション、時間割、exe 一覧は消さない。設定が無い、不正、または読み取り不能でも、
保存済みの時間割によるブロックは継続する。Firebase のアカウントとルールの準備は別途必要。

- 接続設定が使えない、または保存済みセッションが無いときは、起動直後に設定画面を1回だけ開く。
  両方が正常なら画面を出さずに常駐する。同期周期や認証切れのたびに画面を開くことはない。
- Windows 11 では、タスクバー右の通知領域の「^」の中に Cadence のアイコンが隠れることがある。
  アイコンをダブルクリックするか、右クリック →「設定...」で設定を開く。
  設定画面を閉じても常駐とブロックは続く。
- メールとパスワードでサインインすると直ちに同期する。以後60秒ごとに取得する。
  「今すぐ同期」でも取得できる。パスワードは保存しない。
- exe 名を入力して追加するか「実行中のアプリから選ぶ」で選ぶ。
  パスは指定できない。大文字小文字を無視して比較し、自分自身は終了しない。
- ブロック中は2秒ごとに対象プロセスを子プロセスごと終了する。
  未保存のデータは失われる可能性がある。確認には未保存の作業が無いアプリを使う。
  管理者権限で動くアプリなどは終了できないことがある。
- 琥珀の太い輪はブロック中、青緑の細い中抜きの輪は自由時間、大きく欠けた警告色の輪は時間割なし、同期・認証・保存の問題を示す。
  ツールチップに状態、次の切り替え、最終同期を表示する。時刻はすべて日本時間。
- ネットワーク切断、無効な時間割、文書の削除、認証切れでも、最後の有効な時間割で動く。
  サインアウトしてもキャッシュと exe 一覧は残り、ブロックは継続する。
  キャッシュも時間割も無い場合はブロックしない。
- 取得が401ならトークンを更新して、その周期で一度だけ再取得する。再び401ならセッションを残して
  次の周期で再試行する。403なら「時間割を読む権限がありません」と表示し、サインイン状態を保つ。
  更新時の `INVALID_REFRESH_TOKEN` / `TOKEN_EXPIRED` / `USER_DISABLED` / `USER_NOT_FOUND` だけが
  再サインインの対象。それ以外の認証設定・通信の失敗でもセッションとキャッシュは保持する。
- 「Windows のログオン時に起動する」は現在のユーザーの Run レジストリに登録する。
  exe を移動した場合は新しい場所でオフ → オンにする。無効にするにはチェックを外す。
- 終了はトレイの「終了」。終了中・PC のスリープ中にはプロセスを監視できない。

ローカルデータはすべて `%APPDATA%\Cadence\` に保存する。
`session.dat` は refresh token と表示用メールを DPAPI CurrentUser で暗号化したもの。
ID token はメモリだけに保持する。`timetable.json` は検証済み時間割と取得時刻、
`executables.json` はこの PC の対象一覧。ログは `logs\yyyy-MM-dd.txt` に保存し、
書き込み時に14日を過ぎたログを削除する。ログに認証情報や HTTP 本文は出力しない。
設定・セッション・キャッシュの保存は一時ファイルへの書き込み後に置き換える。
ログは追記し、同じ exe の終了失敗のログは1分に1回までに制限する（通知とは別に数える）。

### 構成と検証

- `Cadence.Tray`: WinForms、DPAPI、Run レジストリ、プロセス操作。
- `Cadence.Tray.Logic`: REST、キャッシュ選択、認証更新、次の切り替え、exe 名、通知制限。
- `Cadence.Tray.Tests`: 偽の `TimeProvider` と `HttpMessageHandler` によるテスト。
  Firebase に接続せず、共通 fixture の typed JSON 変換も検証する。

上記のソリューション用 restore / build / test コマンドで全プロジェクトを検証する。
Windows と `windows/global.json` に指定した .NET 8 SDK のある環境で、
`windows/` をカレントディレクトリにして確認用成果物を作るには:

```sh
dotnet publish Cadence.Tray -c Release -r win-x64 --self-contained -p:PublishSingleFile=true -p:RestoreLockedMode=true
```

通常ビルドの `packages.lock.json` と、single-file publish 用の
`Cadence.Tray/PublishLocks/*.json` を分けて固定する。依存を更新する場合のみ
`--force-evaluate`（publish は `-p:RestoreForceEvaluate=true`）で再計算し、差分を確認する。
SDK 由来の publish ツールも lock に含むため、SDK 更新時は `global.json` と publish 用 lock を一緒に更新する。
CI は build / test / publish を行い、テスト結果 `windows-test-results` と確認用 exe を
14日間保存する。Windows 実機での通知、DPAPI、プロセス終了、自動起動は別途確認する。

### デザインのプレビュー

Windows CI の `windows-design-preview` に、合成データだけで描いたホームの FOCUS / FREE / ALERT、ブロックするアプリ、未サインインのアカウント、接続と詳細、
最小サイズで右端へスクロールした画面、ブロック中の ALERT、システム色による高コントラスト配色の画面と、
16 / 20 / 24 / 32px のトレイアイコンを保存する。画像生成と artifact の保存も CI の必須検証に含める。
Windows 上では build 後に次で生成できる。通常起動と独立した経路なので、実際の設定・
AppData・レジストリ・プロセス一覧・Firebase を読み書きしない。

```sh
dotnet run --project Cadence.Tray -c Release --no-build --no-restore -- --design-preview design-preview
```

色・フォント・SVG は `design/README.md`、描画の幾何は
`Cadence.Tray.Logic/Design.cs` を参照する。高コントラスト時はシステム色を使う。

### 設定画面 v2

左のメニューでホーム・ブロックするアプリ・アカウント・接続と詳細を切り替える。
メニューは上下キーで移動して Enter で選択、各操作へは Tab で移動する。
上端の余白はドラッグで移動、ダブルクリックで最大化／元に戻す。
右上の最大化ボタンは Windows 11 のスナップ レイアウトに対応し、Alt+Space で
システムメニュー（移動・サイズ変更・最小化・最大化・閉じる）を開ける。

ホームの残り時間は画面表示中だけ1秒ごとに更新する。同期は60秒、ブロックは2秒のまま。
「今日の記録」は日本時間の当日のログと時間割から、最新8件を表示する。
終了成功は通知の回数制限に関係なく `process_terminated exe=<exe>` を記録する。
同じ2秒周期のスキャン内では、同一 exe の複数プロセスの終了を1件にまとめる。
ログの読み直しは画面表示中だけ10秒ごと。exe 一覧の回数は最新8件に限らず当日全件を数える。
ログを読めなくても時間割の出来事を表示し、常駐・ブロックを継続する。

標準のタイトルバーを取り込み、サイズ変更可能な Win32 の枠を残している。
角丸カード・ボタンは GDI+、フォントは Windows にインストールされたものを使う。
最大化・スナップ・複数モニター・100%/150%/200% DPI・高コントラストは
Windows 実機で確認する（CI の DrawToBitmap では確認できない）。

高コントラスト配色のプレビューはテーマの判定だけを差し替え、OS の設定は変更しない。
自前描画するボタン／トグルが `FlatStyle.System` になっていないことと、
初回表示と一覧の再生成で行の高さ（60論理px）が変わらないことも画像生成時に検証する。
実機では DPI 150%/200% での起動直後と追加・削除後の行の高さを比較する。
最大化したまま Win+Shift+矢印で別モニターへ移動し、タスクバー自動非表示時の辺も確認する。
Alt+Space は Windows の既定システムメニューを使い、最大化ボタンのダブルクリックは1回の切り替えとして扱う。
