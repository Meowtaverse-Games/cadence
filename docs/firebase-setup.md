# Firebase の準備

この手順は管理者が自分の作業環境で上から順に実行する。iPhone / Windows アプリは
まだ無い前提で、最後に合成データをコンソールから作る。本物のプロジェクトの作成、
利用者の登録、ルールのデプロイは人が行う。デプロイは承認を得てから実施する。
画面の日本語訳が異なる場合に備えて英語名を併記する。

実際のプロジェクト ID、uid、メールアドレス、パスワード、API キーや設定ファイルは
この公開リポジトリや Issue / PR に貼らない。以下の山括弧は各自の値に置き換える。
`.firebaserc` は作らず、CLI では毎回 `--project` を指定する。

## 1. プロジェクトと Firestore を作る

1. [Firebase コンソール](https://console.firebase.google.com/) の「プロジェクトを作成」から作成する。
   Google アナリティクスは無効にする。完了後、プロジェクトの概要が開くことを確認する。
2. 左の「構築（Build）」→「Firestore Database」→「データベースの作成」を開く。
   エディション選択がある場合は Standard、データベース ID は `(default)` とする。
   ロケーションに **`asia-northeast1`（東京）**、セキュリティルールに「本番モード」を選び作成する。
   ロケーションは後から変更できないので確定前に確認する。
3. Firestore の「データ」タブが開くことと、データベースのロケーションが
   `asia-northeast1` であることを確認する。「ルール」タブは初期状態ではアクセス拒否になっている。

## 2. メール・パスワードと利用者を1件用意する

1. 「構築」→「Authentication」→「始める」→「Sign-in method」を開く。
   「メール / パスワード」のうちパスワードの方式だけを有効にして保存する。
   メールリンク（パスワードなし）、匿名、Google などは有効にしない。
2. プロバイダ一覧でメール / パスワードだけが有効と確認する。
3. 「Users」→「ユーザーを追加」で本人のメールアドレスと十分に長いパスワードを登録する。
   「Users」の利用者が1件であることを確認し、その uid を手元で控える。
   パスワードや uid をスクリーンショットなどで公開しない。

## 3. 利用者によるサインアップを無効にする

アプリに登録画面を置かないだけでは API 経由の新規登録を防げない。
[Firebase の公式資料「User self-service」](https://firebase.google.com/docs/auth/users#user_self-service)
は Authentication の設定で利用者による作成・削除を無効にできると説明している。
[Identity Platform の管理手順](https://cloud.google.com/identity-platform/docs/concepts-manage-users#user_self-service)
と [Config API の `client.permissions.disabledUserSignup`](https://cloud.google.com/identity-platform/docs/reference/rest/v2/Config#Permissions)
にも同じ制御が記載されている。

1. Firebase コンソールの「Authentication」→「Settings」→「ユーザー アカウント管理
   （User account management）」→「ユーザー アクション（User actions）」を開く。
   「作成を有効にする（Enable create / sign-up）」をオフにして保存する。
   メール / パスワードのプロバイダ自体はオフにしない（既存利用者のサインインにも必要）。
2. 画面を開き直し、作成の許可がオフのままであることを確認する。
   「Users」の既存の1件が残っていることも確認する。
   クライアント経由の新規登録は `auth/admin-restricted-operation`
   （iOS は `ERROR_ADMIN_RESTRICTED_OPERATION`）で拒否されるのが公式資料上の期待結果である。
3. この設定に Identity Platform のアップグレードを求められる場合は、同じ
   「Authentication」→「Settings」のアップグレード案内で条件を確認し、人が判断して実施後、
   手順1〜2を行う。Google Cloud コンソールを使う場合は、同じプロジェクトの
   「Identity Platform」→「Settings」→「User actions」で作成の許可をオフにする。
   Google Cloud コンソールで Identity Platform の有効化を求められた場合、
   その有効化自体が Identity Platform へのアップグレードになる。条件と料金を確認してから判断する。

### Identity Platform と料金プラン、未確定事項

[Firebase Authentication の公式説明](https://firebase.google.com/docs/auth#identity-platform)
では、Identity Platform へのアップグレードは Spark と Blaze の双方で扱われている。
メール・パスワード利用の Spark はアップグレード後 3,000 DAU/日まで、Blaze は
50,000 MAU の無料枠を超えると従量課金となる。
[Authentication の制限](https://firebase.google.com/docs/auth/limits#daily_instrumentless_usage_limits)
も Spark の日次制限を記載している。Identity Platform の利用だけを理由に
Blaze が必須とはしない。実施時には画面に示される条件と料金を再確認する。

**未確定:** [Firebase の User self-service の説明](https://firebase.google.com/docs/auth/users#user_self-service)は、
Authentication の Settings で user actions を無効にできるとだけ説明し、
アップグレードを条件にしていない。また、[アップグレードで使える機能の表](https://firebase.google.com/docs/auth#identity-platform)にも
この設定は含まれていない。このため、アップグレード無しで使える見込みが高いと考えられるが、
これは資料からの推測であり、最終的には手順1で対象プロジェクトの画面を確認する。
設定が提供されず、アップグレードもしない場合は、サインアップ無効化は未完了として扱う。
「利用者を1件だけに制限できた」と判断して運用を開始しない。

無効化しない場合、第三者がアカウントを作り自分の uid の文書を書く余地が残る。
この PR のルールで他人の文書は読めず、書けるのも自分の時間割1文書だけである。
トップレベルのキー4つ、schemaVersion、timeZone、patterns のリスト型と20件上限、
サーバー時刻も制限する。ただし uid の許可リスト、書き込み頻度、パターン内部の大きさを
制限するルールではない。大量のアカウントやリクエストによる枠の消費を防ぐものではない。

## 4. ルールをデプロイする（人が承認後に実行）

このリポジトリを clone したフォルダ（main の最新）で作業する。Node.js 24 を使い、Firebase CLI は
`firebase/package.json` で版を固定したものを `npm ci` で入れる（グローバルに入れる必要はない）。
ブラウザの無い環境（SSH で入ったサーバーなど）では `login --no-localhost` を使い、表示された URL を
手元のブラウザで開いて許可する。Firebase CLI のログインは Google Cloud への広い権限を持つので、
共有の環境では反映のあとにログアウトする。

```sh
cd <clone したフォルダ>/firebase
npm ci
npx --no-install firebase login --no-localhost
npx --no-install firebase deploy --only firestore:rules --project <プロジェクト ID>
npx --no-install firebase logout
```

固定されたローカル CLI で `firebase deploy --only firestore:rules --project <プロジェクト ID>`
を実行する形である。`firebase init` やプロジェクト設定ファイルの追加は不要。
成功メッセージのあと、Firebase コンソールの「Firestore Database」→「ルール」で公開時刻と
内容を確認し、`firestore.rules` と一致することを確かめる。特に許可パス、本人判定、
get / create / update のみの許可、書き込みの制約を確認する。

ルールは Windows と iPhone を区別しない。同じ uid の認証なら書き込みは許可されるため、
「Windows は読み取り専用」は Windows アプリ側で実装する。
クライアントの保存では必ず `FieldValue.serverTimestamp()` を使う。

## 5. iOS アプリを登録する

1. 「プロジェクトの概要」→ iOS アイコン（または「プロジェクトの設定」→「全般」→
   「マイアプリ」→「アプリを追加」→ iOS）を開く。
2. bundle ID を **`com.meowtaverse-games.cadence`** として登録する。
   「マイアプリ」に登録された bundle ID が一致することを確認する。
3. `GoogleService-Info.plist` をダウンロードして `ios/GoogleService-Info.plist` に配置する。
   SDK の組み込みはアプリ実装時に行う。Codemagic では環境変数からこのファイルを
   書き出す予定であり、その設定は別途行う。
4. リポジトリのルートで `git check-ignore ios/GoogleService-Info.plist` がパスを表示し、
   `git status --short` にファイルが出ないことを確認する。強制追加はしない。

## 6. Windows の接続情報を置く

1. Firebase コンソールの歯車→「プロジェクトの設定」→「全般」で「プロジェクト ID」と
   「ウェブ API キー（Web API Key）」を確認する。キーが見当たらない場合は、同じプロジェクトの
   Google Cloud コンソール「API とサービス」→「認証情報」の Firebase 用 Browser key を確認する。
2. Windows のエクスプローラーで `%APPDATA%` を開き `Cadence` フォルダーを作る。
   `%APPDATA%\Cadence\cadence-firebase.json` を以下の形で保存する。

   ```json
   { "apiKey": "<Web API キー>", "projectId": "<プロジェクト ID>" }
   ```

3. ファイル名が `.json.txt` でないこと、JSON のキーが `apiKey` と `projectId` の2つで、
   設定画面の値と一致することをローカルで確認する。このファイルをリポジトリに置かない。
   Firebase CLI 用の `firebase/firebase.json` とは別のファイルである。

[Firebase の API キーの説明](https://firebase.google.com/docs/projects/api-keys)のとおり、
このキーはプロジェクトを識別するもので秘密ではなく、データへのアクセスは認証とルールが守る。
ただし、このプロジェクトでは公開リポジトリに API キーや設定ファイルを書かない方針とする。

任意: Google Cloud コンソール「API とサービス」→「認証情報」→対象キー→「API の制限」で
「キーを制限」を選び、Windows 用には Identity Toolkit API (`identitytoolkit.googleapis.com`)、
Token Service API (`securetoken.googleapis.com`)、Cloud Firestore API (`firestore.googleapis.com`)
だけを許可して保存する。開き直して3つが残ることを確認する。共有キーを変更すると他アプリにも
影響するので対象キーを確認する。アプリ実装後はサインイン、トークン更新、文書取得を確認する。

## 7. アプリが無い段階で合成データを作って確認する

1. 「Authentication」→「Users」で本人の uid を確認する。
2. 「Firestore Database」→「データ」→「コレクションを開始」で `users` を作り、
   文書 ID にその uid を指定する。親文書に実際のプロフィールは保存しない。
   UI で親文書の作成が必要な場合は空の文書にする。
3. その文書で「コレクションを開始」→ `config` →文書 ID `timetable` と進む。
   以下のフィールドを型も指定して追加し保存する。

   | フィールド | コンソールの型 | 合成データ |
   |---|---|---|
   | `schemaVersion` | number（整数） | `1` |
   | `timeZone` | string | `Asia/Tokyo` |
   | `patterns` | array | 空の配列 `[]` |
   | `updatedAt` | timestamp | 任意の合成日時（例: 2026-01-01 00:00:00 UTC） |

   空の patterns は仕様上有効な「ブロックが無い時間割」である。必要なら仕様の合成パターンを
   array 内の map として入力できるが、実際の時間割や個人情報は入れない。
4. データタブで `users/<本人の uid>/config/timetable` を開き、4つのフィールドの値と型を確認する。

**コンソールの管理者操作には Firestore セキュリティルールが適用されない。**
この操作で保存できても、本人以外が拒否されることや serverTimestamp の制約を検証したことには
ならない。ここだけは管理者が固定 timestamp を入力できる。アプリからの書き込みでは
serverTimestamp が必須。アクセス許可・拒否の検証は下記のエミュレーターテストで行う。

## 自動テスト

GitHub Actions の Firebase ワークフローは Node.js 24 と Java 21 を準備し、
`firebase/` で `npm ci` と `npm test` を実行する。依存と CLI は lockfile と package.json で固定する。
`npm test` の実体は次のコマンドで、認証や本物のプロジェクトを必要としない。

```sh
firebase emulators:exec --only firestore --project demo-cadence "node --test test/*.test.mjs"
```

[公式の demo プロジェクトの説明](https://firebase.google.com/docs/emulator-suite/connect_firestore#choose_a_firebase_project)
のとおり `demo-` ID はエミュレーター専用で、実リソースへフォールバックしない。
テスト側でも ID とエミュレーター接続先をチェックする。テスト結果は Actions の Firebase run の
「Test Firestore rules in the emulator」で確認する。開発用環境での実行は Java 21 と
エミュレーターが利用できる場合に限る。
