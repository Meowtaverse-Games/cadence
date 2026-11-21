using System.Globalization;
using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;

namespace Cadence.Tray.Logic;

public sealed record SavedSession(string RefreshToken, string Email);
public interface ISessionStore
{
    SavedSession? Load();
    void Save(SavedSession? session);
}

public sealed record AuthToken(string IdToken, string RefreshToken, string Uid, DateTimeOffset ExpiresAt)
{
    public bool NeedsRefresh(TimeProvider clock) => clock.GetUtcNow() >= ExpiresAt.AddMinutes(-5);
}

public sealed class FirebaseFailure(HttpStatusCode status, string code) : Exception
{
    public HttpStatusCode Status { get; } = status;
    public string Code { get; } = code;
    public bool RequiresSignIn => Code is "INVALID_REFRESH_TOKEN" or "TOKEN_EXPIRED" or "USER_DISABLED" or "USER_NOT_FOUND";
}

public static class SignInErrors
{
    public static string Message(string code) => code switch
    {
        "INVALID_LOGIN_CREDENTIALS" or "EMAIL_NOT_FOUND" or "INVALID_PASSWORD" => "メールアドレスまたはパスワードが違います。",
        "INVALID_EMAIL" => "メールアドレスの形式を確認してください。",
        "MISSING_PASSWORD" => "パスワードを入力してください。",
        "TOO_MANY_ATTEMPTS_TRY_LATER" => "試行回数が多すぎます。しばらく待ってください。",
        "USER_DISABLED" => "このアカウントは無効です。",
        "OPERATION_NOT_ALLOWED" => "メールとパスワードでの認証が許可されていません。",
        _ => "サインインできません。接続や設定を確認してください。"
    };
}

public sealed class FirebaseClient : IDisposable
{
    private readonly FirebaseConfig config;
    private readonly TimeProvider clock;
    private readonly HttpClient http;
    public FirebaseClient(FirebaseConfig config, TimeProvider clock, HttpMessageHandler handler)
    {
        this.config = config;
        this.clock = clock;
        http = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(15) };
    }

    public async Task<AuthToken> SignInAsync(string email, string password, CancellationToken cancellation = default)
    {
        using var request = new HttpRequestMessage(HttpMethod.Post,
            "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=" + Uri.EscapeDataString(config.ApiKey));
        request.Content = new StringContent(JsonSerializer.Serialize(new { email, password, returnSecureToken = true }), Encoding.UTF8, "application/json");
        using var response = await http.SendAsync(request, cancellation);
        using var body = await ReadAuthAsync(response, cancellation);
        return ParseToken(body.RootElement, refreshing: false);
    }

    public async Task<AuthToken> RefreshAsync(string refreshToken, CancellationToken cancellation = default)
    {
        using var request = new HttpRequestMessage(HttpMethod.Post,
            "https://securetoken.googleapis.com/v1/token?key=" + Uri.EscapeDataString(config.ApiKey));
        request.Content = new FormUrlEncodedContent(new Dictionary<string, string>
        {
            ["grant_type"] = "refresh_token", ["refresh_token"] = refreshToken
        });
        using var response = await http.SendAsync(request, cancellation);
        using var body = await ReadAuthAsync(response, cancellation);
        return ParseToken(body.RootElement, refreshing: true);
    }

    public async Task<(HttpStatusCode Status, string Body)> FetchAsync(AuthToken token, CancellationToken cancellation = default)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get,
            $"https://firestore.googleapis.com/v1/projects/{Uri.EscapeDataString(config.ProjectId)}/databases/(default)/documents/users/{Uri.EscapeDataString(token.Uid)}/config/timetable");
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token.IdToken);
        using var response = await http.SendAsync(request, cancellation);
        return (response.StatusCode, await response.Content.ReadAsStringAsync(cancellation));
    }

    private static async Task<JsonDocument> ReadAuthAsync(HttpResponseMessage response, CancellationToken cancellation)
    {
        var text = await response.Content.ReadAsStringAsync(cancellation);
        if (!response.IsSuccessStatusCode)
        {
            var code = "UNKNOWN";
            try
            {
                using var error = JsonDocument.Parse(text);
                code = error.RootElement.GetProperty("error").GetProperty("message").GetString()?.Split(' ', ':')[0] ?? code;
            }
            catch (Exception ex) when (ex is JsonException or InvalidOperationException or KeyNotFoundException) { }
            throw new FirebaseFailure(response.StatusCode, code);
        }
        return JsonDocument.Parse(text);
    }

    private AuthToken ParseToken(JsonElement root, bool refreshing)
    {
        string Read(string signIn, string refresh)
        {
            var value = root.GetProperty(refreshing ? refresh : signIn).GetString();
            return !string.IsNullOrWhiteSpace(value) ? value : throw new FormatException();
        }
        var seconds = int.Parse(Read("expiresIn", "expires_in"), CultureInfo.InvariantCulture);
        if (seconds <= 0) throw new FormatException();
        return new(Read("idToken", "id_token"), Read("refreshToken", "refresh_token"),
            Read("localId", "user_id"), clock.GetUtcNow().AddSeconds(seconds));
    }

    public void Dispose() => http.Dispose();
}
