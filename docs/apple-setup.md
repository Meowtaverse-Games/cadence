# Apple 開発用署名と実機確認

この手順は Apple Developer Program の有効な登録と、iPhone を接続できる Mac を前提とする。
Codemagic がビルドするため、Mac でソースをビルドする必要はない。
値を控える場合は非公開の保管先を使う。公開の Issue / PR に Team ID、Key ID、Issuer ID、UDID、
メールアドレス、秘密鍵、profile、実際の時間割や記録を貼らない。以下の `<...>` は置き換え欄。

## 1. App ID と App Group

[Certificates, Identifiers & Profiles](https://developer.apple.com/account/resources/identifiers/list) で、
Identifiers → ＋ → App Groups を選び `group.com.meowtaverse-games.cadence` を登録する。
続いて App IDs → App の Explicit Bundle ID として次の3つを登録する。

| ターゲット | Bundle ID |
|---|---|
| Cadence | `com.meowtaverse-games.cadence` |
| DeviceActivityMonitor | `com.meowtaverse-games.cadence.DeviceActivityMonitor` |
| ShieldConfiguration | `com.meowtaverse-games.cadence.ShieldConfiguration` |

それぞれ Family Controls（開発用）と App Groups を有効にし、Configure で同じ App Group を関連付ける。
確認: 3つすべての Identifier の詳細で、両 capability と Group の選択が保存されていること。
配布用の申請は手順11で別に扱う。

## 2. 実機の登録

iPhone を Mac にケーブル接続し、ロックを解除して「このコンピュータを信頼」を許可する。
Finder のサイドバーで iPhone を選び、端末情報のシリアル番号表示をクリックして UDID を表示・コピーする。
または Xcode → Window → Devices and Simulators → Devices で Identifier をコピーする。
Developer の Devices → ＋ で iOS を選び、端末名 `<DEVICE_LABEL>` と `<DEVICE_UDID>` を登録する。
確認: Devices で有効になっていること。UDID はこの文書や Git に書き戻さない。
[Apple の端末登録手順](https://developer.apple.com/help/account/devices/register-a-single-device/)

## 3. デベロッパモード

iPhone の「設定 → プライバシーとセキュリティ → デベロッパモード」をオンにし、再起動後の確認で許可する。
項目が出ない場合は Xcode の Devices and Simulators でペアリングしてから確認する。
確認: 再起動後もオンで、Xcode に開発利用不可の警告が出ないこと。
[Apple の説明](https://developer.apple.com/documentation/xcode/enabling-developer-mode-on-a-device)

## 4. App Store Connect API キー

Account Holder が API アクセスを有効化し、Users and Access → Integrations → App Store Connect API の
Team Keys で専用キーを作る。Codemagic の案内に沿って App Manager を選び、Certificates, Identifiers & Profiles
へアクセスできることを確認する。個人キーではなく Issuer ID を持つチームキーを使用する。
`.p8` は一度しかダウンロードできないため、安全な非公開の保管先に保存する。
確認: キーが有効で `<KEY_ID>` と `<ISSUER_ID>` を控えたこと。権限不足なら管理者がアクセス設定を確認する。
[Codemagic の CLI 署名手順](https://docs.codemagic.io/yaml-code-signing/alternative-code-signing-methods/)

## 5. 証明書の秘密鍵

Mac のターミナルで、リポジトリ外の安全な保存先を指定して実行する。
Codemagic の例と同じ RSA 2048 bit / PEM 形式を使う（API キー `.p8` とは別物）。

```sh
ssh-keygen -t rsa -b 2048 -m PEM -f '<PRIVATE_DIRECTORY>/cadence_development_key' -q -N ''
```

確認: 秘密鍵が PEM 形式で保存されていること。`.pub` ではなく秘密鍵の全文を手順6に使用する。
同じ秘密鍵を継続利用し、ビルドのたびに証明書を増やさない。
証明書の種類はファイル名ではなく `IOS_APP_DEVELOPMENT` で指定する。

## 6. Codemagic の登録

Codemagic に GitHub のリポジトリを追加して `codemagic.yaml` を選ぶ。
Environment variables にグループ `cadence_apple` を作り、次の5つをすべて Secret にする。

| 変数 | 値 |
|---|---|
| `APP_STORE_CONNECT_ISSUER_ID` | `<ISSUER_ID>` |
| `APP_STORE_CONNECT_KEY_IDENTIFIER` | `<KEY_ID>` |
| `APP_STORE_CONNECT_PRIVATE_KEY` | `.p8` のヘッダー・フッターと改行を含む全文 |
| `CERTIFICATE_PRIVATE_KEY` | 手順5の秘密鍵のヘッダー・フッターと改行を含む全文 |
| `GOOGLE_SERVICE_INFO_PLIST_BASE64` | Firebase から取得した plist 全体を base64 にした1行 |

確認: 5変数が Secret で同じグループに入り、ワークフローから参照されること。
キーをログに出す `echo` や `set -x` は追加しない。

Firebase の plist は [Firebase の準備](firebase-setup.md#5-ios-アプリを登録する) で取得する。
手元の安全な環境で次を実行し、出力を Secret に貼り付ける。base64 は暗号化ではない。
値を Issue / PR やビルドログに貼らない。末尾を含め改行を入れず、1行だけを登録する。

Mac:

```sh
base64 -i '<PRIVATE_DIRECTORY>/GoogleService-Info.plist' | tr -d '\r\n'
```

Linux:

```sh
base64 -w 0 '<PRIVATE_DIRECTORY>/GoogleService-Info.plist'
```

Windows PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes('<PRIVATE_DIRECTORY>\GoogleService-Info.plist'))
```

ワークフローはプロジェクト生成より前に `ios/GoogleService-Info.plist` を書き出し、`plutil -lint` で確認する。
変数が無いと登録を促すメッセージで失敗する。値や復号した内容はログへ出さない。
archive/export 後は IPA の `Payload/Cadence.app/GoogleService-Info.plist` の存在を検査する。

## 7. 手動ビルド

Start new build で対象ブランチと `ios-development` を選ぶ。push トリガーと TestFlight への publishing は無い。
Xcode 26.6、SHA-256 照合付き XcodeGen 2.44.1、プロジェクト生成、署名、archive/export の順に走る。
手元でビルドする場合も Xcode 26.6 を使う。GitHub Actions は macos-26、Codemagic の26.6イメージは
確認時点で macOS 26.5.1。iOS 16.0以上・Swift 5モードは維持する。
版の根拠: [GitHub ランナー一覧](https://github.com/actions/runner-images/blob/main/images/macos/macos-26-arm64-Readme.md)、
[Codemagic イメージ一覧](https://docs.codemagic.io/specs-macos/xcode-26-6/)。

確認: 各 Bundle ID について profile が取得され、3ターゲットに適用されること。
`--strict-match-identifier` によって、本体の検索に extension の ID が混ざるのを防ぐ。
`--create` は不足時だけ作成する。鍵は環境変数から CLI が読む。
成功後、Artifacts の `.ipa` とビルドログをダウンロードする。
実際の署名・export はこの手順で初めて確認する。YAML の構文検証だけでは成功を保証しない。

署名内容は Mac で IPA を展開し、アプリと `PlugIns/*.appex` のそれぞれに対して確認できる。

```sh
unzip '<IPA_PATH>' -d '<PRIVATE_INSPECTION_DIRECTORY>'
codesign -d --entitlements :- '<APP_OR_APPEX_PATH>'
security cms -D -i '<APP_OR_APPEX_PATH>/embedded.mobileprovision'
```

確認: 3つとも Family Controls と App Group が一致し、profile が開発用で、対象 UDID と有効期限を含むこと。
これらの出力にはアカウント情報があるため公開しない。

コマンドの根拠:
[fetch-signing-files](https://github.com/codemagic-ci-cd/cli-tools/blob/master/docs/app-store-connect/fetch-signing-files.md)、
[use-profiles](https://github.com/codemagic-ci-cd/cli-tools/blob/master/docs/xcode-project/use-profiles.md)、
[build-ipa](https://github.com/codemagic-ci-cd/cli-tools/blob/master/docs/xcode-project/build-ipa.md)。
`use-profiles --archive-method development` が署名と export 設定を作り、`build-ipa --archive-flags`
で `-onlyUsePackageVersionsFromResolvedFile` を渡す。Team ID は profile からビルド時に設定される。

export method は互換名 `development` を維持する。Apple の現在の名称は
[Debugging](https://developer.apple.com/documentation/xcode/distributing-your-app-for-beta-testing-and-releases) だが、
CLI の `--archive-method` と [ExportOptions の型](https://github.com/codemagic-ci-cd/cli-tools/blob/master/src/codemagic/models/export_options.py)
は `development` を扱うため、`debugging` を CLI に直接渡さない。
CI で Xcode 26.6 自身の `xcodebuild -help` の method 欄を表示し、新名称と互換名を確認する。
旧名による非推奨警告が出る可能性があり、実際の署名付き export は手順7で未確認。
将来互換名が削除された場合は CLI の対応版への更新と export 設定の移行が必要になる。

## 8. iPhone にインストール

接続した iPhone を Xcode → Window → Devices and Simulators で選び、Installed Apps の ＋ から `.ipa` を選ぶ。
または Apple Configurator で接続端末に `.ipa` を追加する。
確認: Cadence が起動すること。インストールできなければ手順2・3・7の UDID、開発モード、署名の一致を確認する。

## 9. 記録を見る

iPhone だけなら Cadence → 記録 → 更新で新しい順に確認し、テキストとして共有できる。最新500件を保持する。
Mac なら「コンソール」を開き、接続した iPhone を選んでストリーミングを開始し、
subsystem `com.meowtaverse-games.cadence` で絞る。category は `app` / `monitor` / `shield`。
shield の通常表示は記録しない（読込障害は記録する）。再登録1回の診断は1件にまとめ、本文は4096文字までとする。
確認: 再登録ボタンで両方に同じイベントが出ること。

主なフィールドは `event`、`activity`、`scheduledStart/End`、`received`、`evaluation`、
`decision`（かけた・外した・変えない）、`blocked`、`registered count/first/last`（区間名の先頭・末尾）、
`watchdog`、`plannedLastEnd`、`timezone`。`authorizationStatus` は読んだ状態、`authorizationAction` はその状態で行った操作。
extension は未許可の読み値だけでは停止せず、本体は未許可なら解除・監視停止する。
`timeZoneReset` は保存済み identifier と現在値の違いによる、見張りを含む全解除・再登録を表す。
`startMonitoringFailure` には失敗した activity とエラーの domain/code が出る。
時刻はオフセット付き ISO 8601。`selectionCounts` はアプリ/カテゴリ/Web の件数のみ。
記録には動作時刻があるため、共有は非公開の保管先に限る。結果を公開するなら合成データでの要約だけにする。

## 10. 証明書・profile の更新

年1回の更新を予定に入れ、実際の証明書/profile の有効期限を手順7で確認して期限前に更新する。
新しい証明書が必要なら手順5〜7を行い、3つの開発 profile に新しい証明書を関連付けて再生成する。
端末追加時も、Devices への登録だけでは既存 profile に反映されないため、Developer の Profiles で
3つすべての対象開発 profile を編集し、新端末を含めて再生成する。その後 Codemagic を手動実行する。
確認: ダウンロードした IPA 内の3つの profile の有効期限・証明書・UDID を再確認し、新端末で起動すること。
`--create` は既存の有効な profile を常に再生成するオプションではない。古い profile が選ばれる場合は、
管理者が対象を確認して古い開発 profile のみ整理する。ほかのアプリの証明書は失効させない。

## 11. TestFlight の前に必要なこと（PR 5）

Account Holder が3つすべての Bundle ID について Family Controls の配布用 entitlement を申請する。
承認後は配布用 profile を作り直し、3ターゲットの entitlement を確認する。
確認: Capability Requests で各 ID が承認済みになっていること。
[Apple の申請説明](https://developer.apple.com/documentation/familycontrols/requesting-the-family-controls-entitlement)
この開発用ワークフローは App Store Connect へアップロードしない。

## 実機確認表

すべて合成の時間割・テスト用の対象アプリで実施する。時刻入力は日本時間、shield の表示時刻は端末時間。
各ケース前に不要なパターンを無効化する。ブロック15分・自由0分は無効なので使用しない。
以下の結果欄は未確認のまま開始し、確認端末の OS 版・合成ケース・観測結果は非公開で保存する。
仕様の修正が必要になった場合は別作業とする。

判定・shield の行の `shieldMs` は受信から適用まで、照合の行の `queuedMs` は受信から照合開始まで、
`reconcileMs` は照合開始から終了までの所要時間（ミリ秒）。同じ `event`・`activity`・`received` で行を対応付ける。
extension の連続通知をまとめた照合は最新の通知に対応し、`coalescedCount` がまとめた件数を示す。
本体では `received` はまとめた要求のうち最初の要求時刻で、`shieldMs`・`queuedMs` は本体のキュー待ちも含む。
本体の判定行と照合行の `coalescedCount` は、同じイベント名の繰り返しも含む要求数。
本体の `evaluation` は要求時刻ではなく実行時点の時刻である。
monitor の判定行はコールバック内で同期的に OS ログへ出し、同じ行を非同期でファイルへ追記する。
ファイルへの追記前にプロセスが終了する場合があるため、共有記録に無い判定行は Mac のコンソールも確認する。
照合行も OS ログへの出力後にファイルへ追記する。照合失敗時の画面の監視件数は直前の値を保持し、最新値とは限らない。
起動・前面、権限状態変更、BG タスクは差分照合だけを行い、全解除しない。
全解除は内容が変わった時間割保存・同期受信・選択保存・タイムゾーン変更（登録ゾーン未記録時を含む）・手動の「再登録」に限る。
extension の照合は非同期のため、途中でプロセスが終了すると照合行が残らない場合がある。
次のコールバック・見張り・本体・BG タスクで不足分が補充されることを確認する。

| ID | 操作 | 記録と判定 | 結果 |
|---|---|---|---|
| D1 権限・picker | 初回は許可を拒否し、後で設定から許可。アプリ/カテゴリ/Web を選択して保存 | 拒否中は監視0件。許可後は件数が一致し、ブロック中に対象を開くと「集中の時間です」と解除時刻が表示される。カテゴリに含まれる Web を Safari で開いた場合も同じ表示になる。許可したあとアプリを終了・再起動しても `authorized=true` のままで監視が消えないこと。状態と違う場合は `authorizationStatus/Action` と本体・monitor の違いを記録する | 未確認 |
| D2 編集・保持 | テンプレート3種類を保存。名前/曜日/時刻/セグメントを編集、無効化、削除。重複窓、14分ブロック、空曜日で保存。セグメントの末尾・途中・最後の1行をそれぞれ削除、並べ替え | 日本語理由コードを表示し、再起動しても直前の有効な時間割が残る。行の削除で落ちず、0行の保存は検証エラーになる。全パターン削除で shield が外れる。未許可・未選択・時間割なしでは理由を示し、次の切り替えを表示しない | 未確認 |
| D3 15分・24時間 | 未来の15分窓にブロック15分。別ケースで開始=終了、ブロック720分のみ | イベントの `scheduledStart/End` の差が15分/24時間、登録失敗がないこと、開始と終了で shield が切り替わる | 未確認 |
| D4 同時監視数 | 長い窓にブロック15分/自由1分を繰り返す | `registered count=8`（区間7＋見張り1）。終了後に `first/last` が進み、次が補充される。`shieldMs`・`queuedMs`・`reconcileMs` を対応付け、照合が遅くても次の通知の shield 適用が待たされないか確認。起動・前面で全解除による通知の連鎖が増えないこと。失敗なら domain/code を控える。本アプリが使う8件までを確認し、OSの絶対上限の探索は別の実験とする | 未確認 |
| D5 開始が過去 | 現在が中に入る窓を保存 | 区間の途中で時間割を保存しても画面が固まらず、すぐに編集画面が閉じる。「登録中…」の間も記録・パターン編集・アプリ選択を操作でき、追加の保存後は最新の内容が反映される。本体の `blocked=true` で直ちに shield。記録に本体の再登録と monitor の `intervalDidStart` の両方が出ることを確認し、予定開始と受信時刻を比較。`shieldMs`・`queuedMs`・`reconcileMs` を比較。アプリを終了・再起動しても操作でき、Screen Time の表示が許可済みのままであること。起動・前面では全解除せず、集合が同じなら通知の連鎖が増えないこと | 未確認 |
| D6 stopMonitoring | 保存・選択・タイムゾーンを変えずにブロック中に起動・前面へ戻し、その後に手動の「再登録」を押す | この手順では手動の「再登録」を押したときだけ全解除する。起動・前面で全解除されないことと、各回の `shieldMs`・`queuedMs`・`reconcileMs` を確認。 `intervalDidEnd` が呼ばれるか記録する。呼ばれても `evaluation` がブロック内なら `blocked=true` で外れたままにならない。本体の再登録後、そのブロック区間の終了で shield が外れることも確認する | 未確認 |
| D7 接する境目 | 例: 19:00〜19:15 と19:15〜19:30を別パターンで作り、両方ブロック15分。対象を開いたまま境目を待つ | 前の終了/次の開始の両イベントで `blocked=true`。解除表示は19:30（端末時間に換算）、境目に自由にならない。同じ窓の連続ブロックは結合されるため、このケースに使わない | 未確認 |
| D8 早い開始・終了通知 | 未来の区間を登録しアプリを閉じて待つ | `received < scheduledStart` の有無。早い場合 `evaluation=scheduledStart` となり shield がかかる。遅い場合は受信時刻を使用。`intervalDidEnd` で `received < scheduledEnd` が起こるか、その場合 shield が残るかも観察する。終了イベントの判定は仕様どおり受信時刻のまま。`shieldMs`・`queuedMs`・`reconcileMs` で通知自体の遅れと照合の遅れを区別。これらは端末のスリープ中に進まない時計で測るので、画面が消えていた間は `received` と行の時刻の差も合わせて見る。記録を見るため前面へ戻しても全解除しない | 未確認 |
| D9 電源断・再起動 | D4の `plannedLastEnd` を非公開に控え、登録済み7件がすべて終了するまで電源オフ。再起動し一度ロック解除、Cadenceを開かず見張りの次の境界と次のブロックを待つ。再起動だけのケースも実施 | 対象アプリで shield を確認してから Cadence の記録を見る。`見張り/intervalDidStart` または End、`BGタスク`、補充一覧を確認。本体の前面イベントによる回復と混同しない。BG実行時刻はOS任せ | 未確認 |
| D10 タイムゾーン | 未来の日本時間の区間を登録。端末の自動タイムゾーンを切り、別ゾーンへ変更。Cadenceを前面に戻すケースと、開かず待つケースを別々に行う | 前者はタイムゾーン変更/前面イベントと再登録、後者は monitor の時刻と `timeZoneReset` を確認。extension の次イベントで見張りを含め全再登録され、同じ identifier の後続イベントでは全再登録を繰り返さないこと。同じ絶対時刻でブロックされるか、shield の表示が端末時間か確認。後で自動設定に戻す | 未確認 |
| D11 明示的timeZone | 通常ビルドのD10を先に確認。比較用ビルドで `ScreenTimeCoordinator.useExplicitTokyoTimeZone` のみ true にして、CodemagicでビルドしD10を再実施 | 日本時間の components + timeZone をOSが尊重するか比較。結果だけ保存し、仕様/既定値の変更は別作業 | 未確認 |
| D12 記録・障害 | 記録を共有して件数とフィールドを確認し、消去して更新。OSが監視登録を拒否したケースがあれば再登録 | 新しい順、最大500件、トークン/対象名なし。`shieldMs`・`queuedMs`・`reconcileMs` と相関項目を確認し、照合が完了しない間にも次の判定行が残ること。起動・前面は全解除せず差分照合で回復すること。テスト用の選択ファイルを読めない・デコードできない状態にした場合は `selectionReadFailure action=preserveShieldAndMonitoring` が残り、extension が shield と監視を維持すること。ファイルなし・正常な空の選択では解除されること。失敗理由が画面と記録にあり、登録失敗でも本体の shield 判定が続く。`lockTimeout name=... waitedMs=... action=skipWrite` の有無と対象を確認し、発生時も shield 判定・監視の更新が続くこと。events ロックの取得失敗はファイルに残せないため、Mac のコンソールで OS ログも確認する | 未確認 |

端末のタイムゾーン変更を本体が動いていない間に OS がどう扱うか、開始前通知、過去開始区間、
再起動後の連鎖、監視上限はシミュレーター CI では判定できない。
再起動後の初回ロック解除前は App Group のファイル保護により読み出せない場合がある。

### 同期の実機確認（PR 4b）

Codemagic の実行と端末へのインストールは人が行う。CI は Firebase の本物の認証・同期や Screen Time の動作を確認しない。
以下も合成の時間割で実施し、実際の uid・メールアドレス・時間割・設定ファイル・記録は公開しない。

| ID | 操作 | 期待結果 | 結果 |
|---|---|---|---|
| S1 設定・導入 | Secret を登録して ios-development をビルドし IPA を入れる | plist 構文・IPA 同梱検査が成功し、同期欄にサインインが出る | 未確認 |
| S2 初回選択 | 端末に時間割を保存してサインイン。サーバーには合成の空 patterns を用意 | 両方の要約と選択肢が出る。選択前は編集不可でブロックは継続。再起動後も選択が出る | 未確認 |
| S3 アップロード | 「この iPhone の時間割をサーバーに上げる」を選ぶ | 確定後ひも付け済み。コンソールの文書が一致し、Windows のトレイが60秒以内に同じ時間割を使う | 未確認 |
| S4 編集 | iPhone で時間割を変更・保存 | 端末・コンソール・Windows に反映し、登録記録の reset は1回だけ。同じ内容の保存・受信で追加の reset が無い | 未確認 |
| S5 受信・無効値 | コンソールで有効な合成時間割へ変更し、次に enabled を数値にする | 有効な変更は端末へ採用して再登録。無効値は直前の時間割を維持して検証コードを表示 | 未確認 |
| S6 再起動・前面 | 同じ内容のまま閉じて開き直す・前面に戻す | サインインが保持され、起動・前面・同じ snapshot で reset が無い | 未確認 |
| S7 オフライン | ひも付け後に機内モードで時間割を変更し、その後解除 | 端末のブロックは新しい時間割で動き、接続後にサーバーと Windows に反映。最終同期時刻が更新される | 未確認 |
| S8 認証・サインアウト | 間違ったパスワードを試し、正しくサインイン後ブロック中にサインアウト | 日本語の失敗案内。確認文を経ても shield・監視・選択・時間割が変わらない。未サインインでも編集でき、再サインインで初回突き合わせを行う | 未確認 |
| S9 初回の各分岐 | サーバーなし、同じ内容、端末なし、異なる内容でサーバー採用、無効文書で上書きも確認 | 仕様の同期表どおり。同じ内容で再登録せず、初回アップロードは確定後にだけひも付ける | 未確認 |
| S10 エラー・キャッシュ | 初回オフライン、保存拒否、listener 権限エラーを管理者のテスト環境で確認 | キャッシュだけで初回判断しない。失敗案内はドメイン・コードだけで、端末のブロックを継続。記録に uid・メールアドレス・時間割の内容が無い | 未確認 |
