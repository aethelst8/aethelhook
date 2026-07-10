using System;
using System.Collections.Generic;
using System.IO;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Threading;
using System.Threading.Tasks;

namespace AethelHook.Tray;

// /hook/status is the one endpoint that uses snake_case field names (unlike every
// other endpoint here, which is camelCase) — PropertyNameCaseInsensitive doesn't
// bridge "ws_connected" to "WsConnected" since the underscore makes them genuinely
// different strings, not just different casing. Map explicitly instead of relying
// on convention, which silently left both booleans defaulted to false.
public record HookStatus(
    string Status,
    [property: JsonPropertyName("ws_connected")] bool WsConnected,
    [property: JsonPropertyName("gateway_active")] bool GatewayActive);
public record DeviceInfo(string Id, string Token, string Label, DateTime PairedAt);
public record FeedItem(string SessionId, string ToolName, string CommandName, string Detail, DateTime CreatedAt, string? Decision, DateTime? DecidedAt);
public record PairSession(string Sid, int ExpiresInSec);

// Thin HTTP client for the AethelHook API. Always talks to localhost — the tray app
// only ever runs on the same machine as the service, so there's no LAN/Tailscale
// discovery to do (that's the phone app's problem, not this one's).
public class AethelHookClient
{
    private static readonly JsonSerializerOptions JsonOpts = new() { PropertyNameCaseInsensitive = true };

    // Port 5266, not 5264: the API now serves HTTPS-only on 5264 for phone-facing
    // traffic (pinned self-signed cert), and a second loopback-only plain-HTTP
    // listener on 5266 for PC-local callers like this one - no cert handling needed
    // here at all, since this client never leaves the machine.
    private readonly HttpClient _http = new() { BaseAddress = new Uri("http://localhost:5266"), Timeout = TimeSpan.FromSeconds(5) };
    private readonly string _configPath;

    public string? Token { get; private set; }

    public AethelHookClient()
    {
        var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "AethelHook");
        Directory.CreateDirectory(dir);
        _configPath = Path.Combine(dir, "tray-config.json");
    }

    // Loads a cached device token, or self-registers via the loopback-only
    // /pair/local-token endpoint the first time the tray app ever runs.
    public async Task EnsureTokenAsync(CancellationToken ct = default)
    {
        if (!string.IsNullOrEmpty(Token)) return;

        if (File.Exists(_configPath))
        {
            try
            {
                var cfg = JsonSerializer.Deserialize<TrayConfig>(await File.ReadAllTextAsync(_configPath, ct), JsonOpts);
                if (!string.IsNullOrEmpty(cfg?.Token))
                {
                    Token = cfg.Token;
                    return;
                }
            }
            catch { /* corrupt/missing cache — fall through and re-bootstrap */ }
        }

        var resp = await _http.PostAsync("/pair/local-token", content: null, ct);
        resp.EnsureSuccessStatusCode();
        var body = await resp.Content.ReadFromJsonAsync<JsonElement>(cancellationToken: ct);
        Token = body.GetProperty("token").GetString();
        var id = body.GetProperty("id").GetString() ?? "";
        await File.WriteAllTextAsync(_configPath, JsonSerializer.Serialize(new TrayConfig(id, Token ?? "")), ct);
    }

    private HttpRequestMessage TokenedRequest(HttpMethod method, string path)
    {
        var req = new HttpRequestMessage(method, path);
        if (!string.IsNullOrEmpty(Token))
            req.Headers.TryAddWithoutValidation("X-AethelHook-Token", Token);
        return req;
    }

    // Public, unauthenticated heartbeat — safe to poll frequently even before a
    // token has been bootstrapped.
    public async Task<HookStatus?> GetStatusAsync(CancellationToken ct = default)
    {
        try { return await _http.GetFromJsonAsync<HookStatus>("/hook/status", JsonOpts, ct); }
        catch { return null; }
    }

    public async Task<bool> SetGatewayActiveAsync(bool active, CancellationToken ct = default)
    {
        try
        {
            await EnsureTokenAsync(ct);
            using var req = TokenedRequest(HttpMethod.Post, active ? "/gateway/activate" : "/gateway/deactivate");
            var resp = await _http.SendAsync(req, ct);
            return resp.IsSuccessStatusCode;
        }
        catch { return false; }
    }

    // Loopback-gated, no token required — same as the browser pairing page.
    public async Task<List<DeviceInfo>> GetDevicesAsync(CancellationToken ct = default)
    {
        try { return await _http.GetFromJsonAsync<List<DeviceInfo>>("/pair/devices", JsonOpts, ct) ?? new(); }
        catch { return new(); }
    }

    public async Task<bool> RevokeDeviceAsync(string id, CancellationToken ct = default)
    {
        try
        {
            var resp = await _http.DeleteAsync($"/pair/devices/{id}", ct);
            return resp.IsSuccessStatusCode;
        }
        catch { return false; }
    }

    public async Task<List<FeedItem>> GetFeedAsync(CancellationToken ct = default)
    {
        try { return await _http.GetFromJsonAsync<List<FeedItem>>("/tray/feed?limit=50", JsonOpts, ct) ?? new(); }
        catch { return new(); }
    }

    public async Task<PairSession?> CreatePairingSessionAsync(CancellationToken ct = default)
    {
        try
        {
            var resp = await _http.PostAsync("/pair/session", content: null, ct);
            return await resp.Content.ReadFromJsonAsync<PairSession>(JsonOpts, ct);
        }
        catch { return null; }
    }

    public async Task<byte[]?> GetPairingQrAsync(string sid, CancellationToken ct = default)
    {
        try { return await _http.GetByteArrayAsync($"/pair/qr.png?sid={sid}", ct); }
        catch { return null; }
    }

    public async Task<string> GetPairingStatusAsync(string sid, CancellationToken ct = default)
    {
        try
        {
            var body = await _http.GetFromJsonAsync<JsonElement>($"/pair/status?sid={sid}", JsonOpts, ct);
            return body.GetProperty("status").GetString() ?? "not_found";
        }
        catch { return "not_found"; }
    }

    private record TrayConfig(string Id, string Token);
}
