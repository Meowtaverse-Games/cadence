# Android

`:core` は Android や JSON ライブラリに依存しない Kotlin/JVM の時間割エンジン。
`TimetableParser.parse(TimetableValue)` が検証し、`ValidationResult` の `timetable` または
`errorCode` を返す。`TimetableValue.NumberValue` には JSON 数値の原表記を渡す。
Firestore の Long・Double は `TimetableValue.number(...)` で変換する。
非有限の Double は文字列になり、数値フィールドでは型の誤りとして無効、未知フィールドでは無視される。
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
debug の APK は、secret `ANDROID_DEBUG_KEYSTORE_BASE64` の固定の鍵（`~/.android/debug.keystore` に
展開する）で署名するため、版を上げても上書きで入れられる。ビルドの後に、APK の署名者がその鍵であることを
`apksigner` で確かめ、違えば CI を失敗させる。secret が無い実行では、その場で作った鍵になる。

固定の鍵と違う鍵の APK（固定の鍵を使う前の CI の APK、secret が無い実行の APK、手元のビルドの APK）とは、
互いに上書きで入らない。入れ替えるときはアンインストールが要り、端末内の保存（お気に入り、テーマ、ウィジェット）が消える。

`:core:test` は JDK 21 で実行できる。`:app` のビルド・lint・テストには Android SDK も必要。
エージェントの sandbox の中では Gradle の native library を読み込めない場合があるため、
その場合はローカルの実行を省略し、Android workflow で core を含めて検証する。
共有 fixtures は103件。テストは件数を固定せず、追加された JSON も自動で取り込む。
