# Cadence

先に決めた時間割どおりに、iPhone、Android、Windows の指定アプリを自動でブロックする集中用アプリ。
仕様は `docs/mvp-spec.md` を正とする。

## Language

- ユーザーへの応答、PR のタイトルと本文、Issue は日本語で書く。
- コード、識別子、コミットメッセージの1行目は英語でよい。

## 公開リポジトリとしての制約

このリポジトリは公開（GPL-3.0）である。次のものは、コード、テストデータ、コメント、
コミットメッセージ、Issue、PR 本文のどこにも書かない。

- 利用者個人が特定できる情報（氏名、実際の時間割、利用状況、端末名、メールアドレス）
- Firebase の設定ファイル（`GoogleService-Info.plist`、Android の `google-services.json`、
  Windows の `cadence-firebase.json`、`.firebaserc`）、API キー、App Store Connect の API キー、
  署名用の証明書や provisioning profile、Android の署名の鍵（keystore）
- Apple の Team ID 以外のアカウント情報

テストや例には合成データだけを使う。設定は `.gitignore` 済みのローカルファイルか、
CI / Codemagic の環境変数から読む。

## 構成

```text
ios/        Swift / SwiftUI。XcodeGen の project.yml で Xcode プロジェクトを生成する
  CadenceCore/   時間割の展開などの純粋ロジック（Swift Package、swift test で検証）
windows/    C# / .NET 8。タスクトレイ常駐アプリ
  Cadence.Core/  時間割の展開などの純粋ロジック（net8.0、OS 非依存）
android/    Kotlin / Jetpack Compose。ホームアプリ（ランチャー）
  core/          時間割の展開などの純粋ロジック（Kotlin/JVM、Android 非依存）
firebase/   firestore.rules と firebase.json（ルールのみ。プロジェクト設定は置かない）
spec/fixtures/  iOS・Android・Windows のテストが読む、時間割の展開の共通テストケース
docs/       仕様と運用手順
```

時間割の展開ロジックは Swift、C#、Kotlin に実装する。3つの一致は
`spec/fixtures/` の共通テストケースで保証し、1つだけを変えない。

## 検証

サーバーには Xcode、Swift、.NET が無い前提で、検証は GitHub Actions で行う。

- Windows: `windows-latest` で `dotnet build` と `dotnet test`
- iOS: `macos-latest` で `swift test`（CadenceCore）と、XcodeGen で生成したプロジェクトの
  シミュレーター向け `xcodebuild build`（署名なし）
- Android: `ubuntu-latest` で `android/core` のテスト、`android/app` の unit test・lint・debug の APK のビルド
- Screen Time（FamilyControls / DeviceActivity / ManagedSettings）の動作はシミュレーターで
  確かめられないため、実機での確認手順を PR 本文に書く。Android のブロック（前面のアプリの検知、
  境目のアラーム、foreground service）も、エミュレーターで確かめた範囲と実機での確認手順を PR 本文に書く。

## コミットと PR

- ブランチ名は `feat/` `fix/` `chore/` `docs/` などで始める。
- 実装したモデルを trailer で示す（例: `Implemented-by: Codex gpt-6.1-sol`）。
- main へ直接 push しない。マージは人が承認してから行う。
