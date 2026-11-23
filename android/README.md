# Android

`:core` は Android や JSON ライブラリに依存しない Kotlin/JVM の時間割エンジン。
`TimetableParser.parse(TimetableValue)` が検証し、`ValidationResult` の `timetable` または
`errorCode` を返す。`TimetableValue.NumberValue` には JSON 数値の原表記を渡す。
整数への変換や丸めを入力時に行わず、検証時に C# の `GetDouble` と同じ判定を行う。
未知フィールドは無視する。タイムゾーンは固定の UTC+09:00。

`TimetableExpander.expand(timetable, from, to)` は `Instant` の期間と重なるブロック区間を返す。
`isBlockedAt(timetable, now)` は呼び出し元から明示された時点を判定する。
検索期間の境界で区間を切り詰めない。同じ窓内の隣接ブロックを結合し、15分未満を除外する。

`:app` は Compose の Activity に Cadence と表示する土台。HOME/LAUNCHER の intent-filter は
まだ持たず、権限・ブロック・Firebase は後続 PR で実装する。

## 検証

```sh
cd android
./gradlew :core:test
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

core の JUnit テストは `spec/fixtures/*.json` を全件列挙する。JSON ライブラリはテストだけで使い、
数値の原表記を保持して値の木に変換する。API の境界と検証順序の追加テストも実行する。
app は現段階では unit test を持たないため、`testDebugUnitTest` の件数は0件。

CI では JDK 21 と Android SDK がある Ubuntu ランナーで検証し、JUnit XML を
`android-test-results`、APK を `cadence-android-debug-apk` に保存する。

## 現在のブロッカー

キャッシュ済み Gradle 8.13 が Linux ARM64 の `libnative-platform.so` を読み込めず、起動に失敗した。
実装指示に従って起動の回避策を試していないため、wrapper のスクリプトと JAR は未生成。
`gradle-wrapper.properties` に選定した distribution の版と公式 SHA-256 は記載したが、
wrapper 生成とコミットが済むまでは上記コマンドと CI は実行できない。
