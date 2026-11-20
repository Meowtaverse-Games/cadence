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
両プロジェクトの `packages.lock.json` の差分をコミットする。
テストは共有 JSON 全件に加え、現在時刻の境界、異なるオフセット、年をまたぐ6週間、
20パターン×20セグメントの14日間展開を確認する。
性能目標は50 ms、CI上の失敗判定は500 ms。JITのウォームアップ後、展開だけを計測する。
