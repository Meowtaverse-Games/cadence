using System.Security;
using System.Text.Json;

namespace Cadence.Tray.Logic;

public enum FirebaseConfigStatus { Ready, Missing, Unreadable, InvalidJson, InvalidKeys }

public sealed record FirebaseConfigLoadResult(
    FirebaseConfigStatus Status, string Path, FirebaseConfig? Config = null,
    string? KeyProblem = null, long? JsonLine = null, long? JsonBytePosition = null,
    string? PlacementHint = null)
{
    public bool Usable => Status == FirebaseConfigStatus.Ready;
    public string Code => Status switch
    {
        FirebaseConfigStatus.Ready => "config_ready",
        FirebaseConfigStatus.Missing => "config_missing",
        FirebaseConfigStatus.Unreadable => "config_unreadable",
        FirebaseConfigStatus.InvalidJson => "config_invalid_json",
        _ => "config_missing_keys"
    };

    // Never include exception messages or JSON values: they may contain credentials.
    public string Message
    {
        get
        {
            var reason = Status switch
            {
                FirebaseConfigStatus.Ready => "接続設定は正常です",
                FirebaseConfigStatus.Missing => "接続設定のファイルがありません",
                FirebaseConfigStatus.Unreadable => "接続設定のファイルを読み込めません。読み取り権限などを確認してください",
                FirebaseConfigStatus.InvalidJson => "接続設定を JSON として読めません"
                    + (JsonLine is { } line && JsonBytePosition is { } position
                        ? $"（{line + 1} 行目、バイト位置 {position + 1}）" : ""),
                _ => "接続設定の項目を確認してください（" + KeyProblem + "）"
            };
            return reason + ": " + Path + (PlacementHint is null ? "" : "\n" + PlacementHint);
        }
    }
}

public sealed record FirebaseConfig(string ApiKey, string ProjectId)
{
    public static FirebaseConfigLoadResult Load(string path, string? directlyUnderAppDataPath = null)
    {
        path = System.IO.Path.GetFullPath(path);
        try
        {
            using var doc = JsonDocument.Parse(File.ReadAllText(path));
            var root = doc.RootElement;
            if (root.ValueKind != JsonValueKind.Object)
                return new(FirebaseConfigStatus.InvalidKeys, path, KeyProblem: "ルートを JSON オブジェクトにしてください");
            var problems = new List<string>();
            string? Read(string name)
            {
                if (!root.TryGetProperty(name, out var value)) problems.Add(name + " がありません");
                else if (value.ValueKind != JsonValueKind.String) problems.Add(name + " が文字列ではありません");
                else if (string.IsNullOrWhiteSpace(value.GetString())) problems.Add(name + " が空です");
                else return value.GetString();
                return null;
            }
            var key = Read("apiKey");
            var project = Read("projectId");
            return problems.Count == 0
                ? new(FirebaseConfigStatus.Ready, path, new(key!, project!))
                : new(FirebaseConfigStatus.InvalidKeys, path, KeyProblem: string.Join("、", problems));
        }
        catch (Exception ex) when (ex is FileNotFoundException or DirectoryNotFoundException)
        {
            var hints = new List<string>();
            if (File.Exists(path + ".txt")) hints.Add("拡張子が .txt になっています。cadence-firebase.json に名前を変更してください。");
            if (directlyUnderAppDataPath is not null && File.Exists(directlyUnderAppDataPath))
                hints.Add(@"AppData の直下ではなく AppData\Roaming\Cadence に置いてください。");
            return new(FirebaseConfigStatus.Missing, path, PlacementHint: hints.Count == 0 ? null : string.Join("\n", hints));
        }
        catch (JsonException ex)
        {
            return new(FirebaseConfigStatus.InvalidJson, path, JsonLine: ex.LineNumber, JsonBytePosition: ex.BytePositionInLine);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or SecurityException)
        {
            return new(FirebaseConfigStatus.Unreadable, path);
        }
    }
}

public static class StartupSettings
{
    public static bool ShouldOpen(bool configurationUsable, bool hasSavedSession) =>
        !configurationUsable || !hasSavedSession;
}
