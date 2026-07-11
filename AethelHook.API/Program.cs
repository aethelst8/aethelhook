using System.Diagnostics;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.Json.Serialization;
using System.Collections.Concurrent;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Linq;
using QRCoder;

// Force AutoFlush on stdout and tee all Console.WriteLine calls to a log file.
// The log file is essential when running as a Windows Service (no console window).
Console.OutputEncoding = Encoding.UTF8;
var logDir  = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "AethelHook");
Directory.CreateDirectory(logDir);
var logFile = new StreamWriter(Path.Combine(logDir, "api.log"), append: true, Encoding.UTF8) { AutoFlush = true };
Console.SetOut(new TeeWriter(new StreamWriter(Console.OpenStandardOutput(), Encoding.UTF8) { AutoFlush = true }, logFile));

var localIp      = GetLocalIpAddress();
var tailscaleIp  = GetTailscaleIpAddress();
var ApiBaseUrl   = $"https://{localIp}:5264";
Console.WriteLine($"[API] Detected local IP: {localIp}  →  {ApiBaseUrl}");
if (tailscaleIp != null)
    Console.WriteLine($"[API] Tailscale IP: {tailscaleIp}  →  https://{tailscaleIp}:5264");
else
    Console.WriteLine("[API] Tailscale not detected - LAN-only mode");

// ── TLS: self-signed cert, pinned by the phone via the QR pairing flow ─────────
// Phone-facing traffic (port 5264) is HTTPS-only; PC-local callers (hook scripts,
// the Tray app, the /pair browser page) talk to a second, loopback-only plain-HTTP
// port instead (5266, configured below) so none of them need any cert-handling
// code at all - only the phone, which is never on loopback, needs to validate this
// certificate, and it does so by pinning the exact fingerprint it received
// out-of-band via the QR code, not by trusting a certificate authority.
var httpsCert = LoadOrCreateHttpsCertificate(localIp);
var CertFingerprint = Convert.ToHexString(httpsCert.GetCertHash(HashAlgorithmName.SHA256)).ToLower();
Console.WriteLine($"[TLS] Certificate fingerprint: {CertFingerprint}");

// ── Per-device API tokens ──────────────────────────────────────────────────────
// Legacy shared token (api_token.txt) is migrated into DeviceRegistry as a "legacy"
// device on first run so an already-paired phone keeps working without re-pairing.
DeviceRegistry.Initialize(LoadOrCreateApiToken);
Console.WriteLine($"[Security] {DeviceRegistry.List().Count} device(s) registered");

bool ValidateToken(HttpContext ctx) {
    if (ctx.Request.Headers.TryGetValue("X-AethelHook-Token", out var h) && DeviceRegistry.IsValidToken(h.ToString()))
        return true;
    Console.WriteLine($"[Security] Unauthorized request from {ctx.Connection.RemoteIpAddress} - rejected");
    return false;
}

// Loopback-only guard for the pairing UI endpoints - you must be sitting at the PC
// to generate/view a pairing QR. Only /pair/claim (called by the phone) skips this.
bool IsLocalRequest(HttpContext ctx) => IPAddress.IsLoopback(ctx.Connection.RemoteIpAddress ?? IPAddress.None);

var builder = WebApplication.CreateBuilder(args);

// Run as a Windows Service when installed (no-op when launched from a console)
builder.Services.AddWindowsService(options => options.ServiceName = "AethelHook");

// Explicit Listen calls (not --urls) so the phone-facing port can carry our own
// generated cert instead of ASP.NET's dev cert / plain HTTP.
builder.WebHost.ConfigureKestrel(options =>
{
    options.ListenAnyIP(5264, lo => lo.UseHttps(httpsCert));
    options.ListenLocalhost(5266);
});

var app = builder.Build();

// Must be before routing
app.UseWebSockets(new WebSocketOptions { KeepAliveInterval = TimeSpan.FromSeconds(30) });

// ─────────────────────────────────────────────────────────────────────────────
// GET /ws  ─  WebSocket endpoint: phone connects and stays connected
//            When a hook fires, we push the event JSON over the socket instantly.
// ─────────────────────────────────────────────────────────────────────────────
app.Map("/ws", async context =>
{
    if (!context.WebSockets.IsWebSocketRequest)
    {
        context.Response.StatusCode = 400;
        return;
    }

    // WebSocket upgrades cannot set custom headers - token comes as a query param
    var queryToken = context.Request.Query["token"].ToString();
    if (!DeviceRegistry.IsValidToken(queryToken))
    {
        Console.WriteLine($"[Security] WS: unauthorized from {context.Connection.RemoteIpAddress}");
        context.Response.StatusCode = 401;
        return;
    }

    using var ws = await context.WebSockets.AcceptWebSocketAsync();
    Console.WriteLine($"[WS] Phone connected from {context.Connection.RemoteIpAddress}");

    // Register with a send-safe wrapper (serialises sends + 5s timeout per send)
    var conn = WsClientStore.Register(ws, queryToken);

    // Send a welcome ping so phone knows it's connected
    await conn.TrySendAsync(JsonSerializer.Serialize(new { type = "connected", message = "AethelHook WebSocket ready" }));

    // Re-push events that arrived before this connection - only if recent (< 90s) to avoid
    // replaying stale test pings that were never resolved via wait-decision
    var replayCutoff = DateTime.UtcNow.AddSeconds(-90);
    foreach (var kv in DecisionStore.PendingEventPayloads)
    {
        if (kv.Value.StoredAt > replayCutoff && DecisionStore.PendingDecisions.ContainsKey(kv.Key))
        {
            await conn.TrySendAsync(kv.Value.Payload);
            Console.WriteLine($"[WS] Re-pushed pending event {kv.Key} to newly connected phone");
        }
    }
    foreach (var kv in QuestionStore.PendingQuestionPayloads)
    {
        if (kv.Value.StoredAt > replayCutoff && QuestionStore.PendingAnswers.ContainsKey(kv.Key))
        {
            await conn.TrySendAsync(kv.Value.Payload);
            Console.WriteLine($"[WS] Re-pushed pending question {kv.Key} to newly connected phone");
        }
    }

    // Keep the connection alive - read incoming frames (the phone sends decisions this way too)
    var buf = new byte[4096];
    while (ws.State == WebSocketState.Open)
    {
        WebSocketReceiveResult result;
        try
        {
            result = await ws.ReceiveAsync(buf, context.RequestAborted);
        }
        catch
        {
            break;
        }

        if (result.MessageType == WebSocketMessageType.Close)
            break;

        if (result.MessageType == WebSocketMessageType.Text)
        {
            var text = Encoding.UTF8.GetString(buf, 0, result.Count);
            Console.WriteLine($"[WS] Received from phone: {text}");

            // Phone can send decisions over WS too: { "type":"decision","session_id":"...","decision":"...","reason":"..." }
            try
            {
                using var doc = JsonDocument.Parse(text);
                var root = doc.RootElement;
                if (root.TryGetProperty("type", out var t) && t.GetString() == "decision")
                {
                    var sessionId  = root.GetProperty("session_id").GetString() ?? "";
                    var decision   = root.GetProperty("decision").GetString() ?? "ask";
                    var reason     = root.TryGetProperty("reason", out var r) ? r.GetString() ?? "" : "";
                    var normalized = NormalizeDecision(decision);
                    var safeReason = reason.Replace("\\", "\\\\").Replace("\"", "\\\"");
                    var resultJson = $"{{\"decision\":\"{normalized}\",\"reason\":\"{safeReason}\"}}";

                    if (DecisionStore.PendingDecisions.TryGetValue(sessionId, out var tcs))
                    {
                        tcs.TrySetResult(resultJson);
                        Console.WriteLine($"[WS] Decision '{normalized}' set for session {sessionId}");
                        DecisionStore.PropagateToShadows(sessionId, resultJson);
                    }
                    else
                    {
                        DecisionStore.RecentDecisions[sessionId] = resultJson;
                        DecisionStore.PropagateToShadows(sessionId, resultJson);
                    }
                    TrayFeedStore.Resolve(sessionId, normalized);

                    // Ack back to the phone
                    await conn.TrySendAsync(JsonSerializer.Serialize(new { type = "ack", session_id = sessionId, decision = normalized }));
                }
                else if (root.TryGetProperty("type", out var t2) && t2.GetString() == "question_answer")
                {
                    var sessionId   = root.GetProperty("session_id").GetString() ?? "";
                    var answersJson = root.GetProperty("answers").GetRawText();

                    if (QuestionStore.PendingAnswers.TryGetValue(sessionId, out var qtcs))
                    {
                        qtcs.TrySetResult(answersJson);
                        Console.WriteLine($"[WS] Answer set for question session {sessionId}");
                    }
                    else
                    {
                        QuestionStore.RecentAnswers[sessionId] = answersJson;
                    }

                    // Ack back to the phone
                    await conn.TrySendAsync(JsonSerializer.Serialize(new { type = "ack", session_id = sessionId, kind = "question_answer" }));
                }
                else if (root.TryGetProperty("type", out var t3) && t3.GetString() == "plan_review_decision")
                {
                    var sessionId  = root.GetProperty("session_id").GetString() ?? "";
                    var decision   = root.GetProperty("decision").GetString() ?? "";
                    var feedback   = root.TryGetProperty("feedback", out var fb) ? fb.GetString() ?? "" : "";
                    var resultJson = JsonSerializer.Serialize(new { decision, feedback });
                    PlanReviewStore.PendingPlans.TryRemove(sessionId, out _);

                    if (PlanReviewStore.PendingDecisions.TryGetValue(sessionId, out var ptcs))
                    {
                        ptcs.TrySetResult(resultJson);
                        Console.WriteLine($"[WS] Plan decision '{decision}' set for session {sessionId}");
                    }
                    else
                    {
                        PlanReviewStore.RecentDecisions[sessionId] = resultJson;
                    }

                    // Ack back to the phone
                    await conn.TrySendAsync(JsonSerializer.Serialize(new { type = "ack", session_id = sessionId, kind = "plan_review_decision" }));
                }
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[WS] Parse error: {ex.Message}");
            }
        }
    }

    WsClientStore.Unregister(conn);
    Console.WriteLine("[WS] Phone disconnected");
});

// Gateway active flags - toggled by the Android app switch
bool IsGatewayActive = true;
bool IsCodexGatewayActive = true;

// Phase 2 (Session Access): the most recent working directory seen in any hook
// event - the DEFAULT cwd for a brand-new phone conversation when the phone hasn't
// explicitly picked a project. Shared across every open Claude Code window in every
// editor (hooks are global, not editor-scoped - confirmed live with Cursor, which
// has its own separate extension install but reads the same ~/.claude/ config), so
// whichever project you touched most recently wins for a fresh conversation.
string? LastKnownCwd = null;

// One resumable claude session_id PER PROJECT DIRECTORY, not a single global one -
// lets the phone hold independent conversation threads with different projects (e.g.
// AethelHook vs. a work project in Cursor) without them clobbering each other.
// Passed back via --resume on the next send-prompt call for that same directory so
// each thread is a continuous conversation, not a fresh amnesiac session per message
// (confirmed via a live test: --resume <id> correctly recalled a fact from an
// earlier run). Critical gotcha confirmed live: `--resume` FAILS immediately ("No
// conversation found") if the cwd at resume time doesn't exactly match the cwd the
// session was created in - so a resumed run must always use the SAME key (directory)
// it's stored under here, never a drifted LastKnownCwd. No expiry yet - entries
// persist until the service restarts.
// OrdinalIgnoreCase: Windows paths are case-insensitive but different tools report cwd
// with different casing (e.g. Cursor sends "c:\ERP", Claude Code's own hook sends
// "C:\ERP") - without this, the two are treated as distinct keys, showing up as
// duplicate "projects" on the phone and splitting one directory's session across two
// unrelated ProjectSessions entries (confirmed live via the phone's picker).
var ProjectSessions = new ConcurrentDictionary<string, string>(StringComparer.OrdinalIgnoreCase);

// Same idea as ProjectSessions above, but for headless `codex exec` runs - Codex's own
// resumable identifier is a "thread_id", a distinct namespace from Claude's session_id,
// so a project directory can hold one resumable thread per agent independently (you can
// have a live Claude conversation AND a live Codex conversation in the same directory).
var CodexProjectSessions = new ConcurrentDictionary<string, string>(StringComparer.OrdinalIgnoreCase);

// Every distinct working directory any hook event has reported, with when it was
// last seen - lets the phone list "known projects" to explicitly pick from instead
// of always trusting whichever one LastKnownCwd currently points at.
var KnownProjects = new ConcurrentDictionary<string, DateTime>(StringComparer.OrdinalIgnoreCase);

// Serializes headless prompt runs so two phone-sent prompts never spawn concurrent
// claude.exe processes in the same working directory.
var PromptRunLock = new SemaphoreSlim(1, 1);

// LastKnownCwd/ProjectSessions/KnownProjects above used to live in memory only ("no
// expiry yet - entries persist until the service restarts"). That meant every service
// restart (reboot, crash, or an install.ps1 redeploy - which stops and restarts the
// service every time) silently wiped the phone's project picker and every resumable
// conversation thread, forcing the user to re-open each project in an IDE just to
// re-register it with a hook before the phone could see it again. Persisting to a
// small JSON file closes that gap - loaded once at startup, saved after every mutation.
const string ProjectStateFilePath = @"C:\ProgramData\AethelHook\project_state.json";
var ProjectStateSaveLock = new object();

void SaveProjectState()
{
    try
    {
        var state = new
        {
            lastKnownCwd         = LastKnownCwd,
            projectSessions      = ProjectSessions.ToDictionary(kv => kv.Key, kv => kv.Value, StringComparer.OrdinalIgnoreCase),
            codexProjectSessions = CodexProjectSessions.ToDictionary(kv => kv.Key, kv => kv.Value, StringComparer.OrdinalIgnoreCase),
            knownProjects        = KnownProjects.ToDictionary(kv => kv.Key, kv => kv.Value, StringComparer.OrdinalIgnoreCase)
        };
        var json = JsonSerializer.Serialize(state);
        lock (ProjectStateSaveLock)
        {
            Directory.CreateDirectory(Path.GetDirectoryName(ProjectStateFilePath)!);
            File.WriteAllText(ProjectStateFilePath, json);
            CryptoUtil.RestrictToAdminSystem(ProjectStateFilePath);
        }
    }
    catch (Exception ex) { Console.WriteLine($"[ProjectState] Save failed: {ex.Message}"); }
}

void LoadProjectState()
{
    try
    {
        if (!File.Exists(ProjectStateFilePath)) return;
        using var doc = JsonDocument.Parse(File.ReadAllText(ProjectStateFilePath));
        var root = doc.RootElement;

        if (root.TryGetProperty("lastKnownCwd", out var lkc) && lkc.ValueKind == JsonValueKind.String)
            LastKnownCwd = lkc.GetString();

        if (root.TryGetProperty("projectSessions", out var ps) && ps.ValueKind == JsonValueKind.Object)
            foreach (var prop in ps.EnumerateObject())
                if (prop.Value.ValueKind == JsonValueKind.String)
                    ProjectSessions[prop.Name] = prop.Value.GetString()!;

        if (root.TryGetProperty("codexProjectSessions", out var cps) && cps.ValueKind == JsonValueKind.Object)
            foreach (var prop in cps.EnumerateObject())
                if (prop.Value.ValueKind == JsonValueKind.String)
                    CodexProjectSessions[prop.Name] = prop.Value.GetString()!;

        if (root.TryGetProperty("knownProjects", out var kp) && kp.ValueKind == JsonValueKind.Object)
            foreach (var prop in kp.EnumerateObject())
                if (prop.Value.TryGetDateTime(out var seen))
                    KnownProjects[prop.Name] = seen;

        Console.WriteLine($"[ProjectState] Restored {KnownProjects.Count} known project(s), {ProjectSessions.Count} Claude session(s), {CodexProjectSessions.Count} Codex thread(s) from disk");
    }
    catch (Exception ex) { Console.WriteLine($"[ProjectState] Load failed: {ex.Message}"); }
}

LoadProjectState();

// ─────────────────────────────────────────────────────────────────────────────
// GET /hook/status
// ─────────────────────────────────────────────────────────────────────────────
app.MapGet("/hook/status", () =>
{
    var wsConnected = WsClientStore.HasClients;
    return Results.Ok(new { status = "AethelHook is running", ws_connected = wsConnected, gateway_active = IsGatewayActive });
});

// ─────────────────────────────────────────────────────────────────────────────
// POST /gateway/activate  ─  phone toggle ON: restore hooks + mark active
// ─────────────────────────────────────────────────────────────────────────────
app.MapPost("/gateway/activate", (HttpContext ctx) =>
{
    if (!ValidateToken(ctx)) return Results.Unauthorized();
    IsGatewayActive = true;
    RestoreClaudeCodeHooks();
    Console.WriteLine("[Gateway] Activated - hooks restored");
    return Results.Ok(new { status = "activated" });
});

// ─────────────────────────────────────────────────────────────────────────────
// POST /gateway/deactivate  ─  phone toggle OFF: remove hooks + mark inactive
// ─────────────────────────────────────────────────────────────────────────────
app.MapPost("/gateway/deactivate", (HttpContext ctx) =>
{
    if (!ValidateToken(ctx)) return Results.Unauthorized();
    IsGatewayActive = false;
    RemoveClaudeCodeHooks();
    Console.WriteLine("[Gateway] Deactivated - hooks removed, native dialogs active");
    return Results.Ok(new { status = "deactivated" });
});

// ─────────────────────────────────────────────────────────────────────────────
// POST /codex/gateway/activate  ─  restore Codex hooks.json + mark active
// ─────────────────────────────────────────────────────────────────────────────
app.MapPost("/codex/gateway/activate", (HttpContext ctx) =>
{
    if (!ValidateToken(ctx)) return Results.Unauthorized();
    IsCodexGatewayActive = true;
    RestoreCodexHooks();
    Console.WriteLine("[Codex Gateway] Activated - hooks.json restored");
    return Results.Ok(new { status = "activated" });
});

// ─────────────────────────────────────────────────────────────────────────────
// POST /codex/gateway/deactivate  ─  remove Codex hooks.json + mark inactive
// ─────────────────────────────────────────────────────────────────────────────
app.MapPost("/codex/gateway/deactivate", (HttpContext ctx) =>
{
    if (!ValidateToken(ctx)) return Results.Unauthorized();
    IsCodexGatewayActive = false;
    RemoveCodexHooks();
    Console.WriteLine("[Codex Gateway] Deactivated - hooks.json removed, native Codex approvals active");
    return Results.Ok(new { status = "deactivated" });
});

// ─────────────────────────────────────────────────────────────────────────────
// POST /hook/event  ─  hook fires this; pushed over WS to the phone
// ─────────────────────────────────────────────────────────────────────────────
app.MapPost("/hook/event", async (HttpContext ctx, EventRequest request) =>
{
    if (!ValidateToken(ctx)) return Results.Unauthorized();
    // If gateway is inactive, return 503 so the hook's catch block removes hooks and exits 2
    if (!IsGatewayActive)
    {
        Console.WriteLine($"[Gateway] Inactive - rejecting event {request.SessionId}, hook will restore native dialogs");
        return Results.Problem("Gateway inactive", statusCode: 503);
    }

    Console.WriteLine($"Received event: {request.EventType} for Session: {request.SessionId}");

    // Codex dedup: parallel calls with the SAME normalized command in the same turn share one
    // notification and decision. Different commands each get their own notification.
    if (!string.IsNullOrEmpty(request.CodexTurnId) && !string.IsNullOrEmpty(request.CommandName))
    {
        var turnKey      = $"{request.CodexTurnId}|{request.CommandName}";
        var registeredId = DecisionStore.CodexTurnPrimary.GetOrAdd(turnKey, request.SessionId);
        if (registeredId != request.SessionId)
        {
            // Shadow: create TCS so wait-decision works, link to primary for propagation
            DecisionStore.PendingDecisions.GetOrAdd(request.SessionId, _ => new TaskCompletionSource<string>());
            DecisionStore.LinkedSessions.GetOrAdd(registeredId, _ => new ConcurrentBag<string>()).Add(request.SessionId);
            Console.WriteLine($"[Dedup] Session {request.SessionId} shadowed → primary {registeredId} (cmd {request.CommandName})");
            return Results.Ok(new { success = true, deduplicated = true });
        }
        // Primary: auto-clean the turn key after 120s
        _ = Task.Delay(TimeSpan.FromSeconds(120)).ContinueWith(t =>
            DecisionStore.CodexTurnPrimary.TryRemove(turnKey, out _));
    }

    // Register TCS immediately so wait-decision doesn't miss the decision
    var tcs = DecisionStore.PendingDecisions.GetOrAdd(request.SessionId, _ => new TaskCompletionSource<string>());

    // Build the event payload
    var payload = JsonSerializer.Serialize(new
    {
        type         = "approval_request",
        event_type   = request.EventType,
        message      = request.Message,
        detail       = request.Detail,
        session_id   = request.SessionId,
        timestamp    = request.Timestamp,
        tool_name    = request.ToolName ?? "",
        command_name = request.CommandName ?? "",
        respond_url  = $"{ApiBaseUrl}/hook/respond",
        api_base_url = ApiBaseUrl
    });

    // Store payload so a phone that connects late can receive it on join
    DecisionStore.PendingEventPayloads[request.SessionId] = (payload, DateTime.UtcNow);

    // Record for the tray app's polling-based live feed (see TrayFeedStore for why
    // this is polling rather than a second /ws listener).
    TrayFeedStore.Add(request.SessionId, request.ToolName ?? "", request.CommandName ?? "", request.Detail ?? "");

    // ── 1. Try WebSocket first (LAN, <100ms) ─────────────────────────────
    bool wsSent = false;
    if (WsClientStore.HasClients)
    {
        try
        {
            wsSent = await WsClientStore.BroadcastAsync(payload);
            if (wsSent)
                Console.WriteLine($"[WS] Pushed event to phone for session {request.SessionId}");
            else
                Console.WriteLine($"[WS] No open sockets - session {request.SessionId} will fail open on timeout");
        }
        catch (Exception ex)
        {
            Console.WriteLine($"[WS] Push failed: {ex.Message}");
        }
    }

    return Results.Ok(new { success = true, queued = true, ws_delivered = wsSent });
});

// ─────────────────────────────────────────────────────────────────────────────
// GET /hook/wait-decision/{sessionId}  ─  hook long-polls here
// ─────────────────────────────────────────────────────────────────────────────
app.MapGet("/hook/wait-decision/{sessionId}", async (HttpContext ctx, string sessionId) =>
{
    if (!ValidateToken(ctx)) return Results.Unauthorized();
    // Fast path: decision already arrived
    if (DecisionStore.RecentDecisions.TryRemove(sessionId, out var earlyDecision))
    {
        Console.WriteLine($"[API] Pre-arrived decision for Session {sessionId}");
        try
        {
            using var d = JsonDocument.Parse(earlyDecision);
            var dec = d.RootElement.GetProperty("decision").GetString();
            var rea = d.RootElement.TryGetProperty("reason", out var rv) ? rv.GetString() : "";
            return Results.Ok(new { decision = dec, reason = rea });
        }
        catch { return Results.Ok(new { decision = earlyDecision, reason = "" }); }
    }

    Console.WriteLine($"[API] Waiting for decision on Session: {sessionId}...");
    var tcs = DecisionStore.PendingDecisions.GetOrAdd(sessionId, _ => new TaskCompletionSource<string>());

    var completedTask = await Task.WhenAny(tcs.Task, Task.Delay(TimeSpan.FromSeconds(300)));
    DecisionStore.PendingDecisions.TryRemove(sessionId, out _);
    DecisionStore.PendingEventPayloads.TryRemove(sessionId, out _);

    if (completedTask == tcs.Task)
    {
        var resultJson = await tcs.Task;
        try
        {
            using var doc = JsonDocument.Parse(resultJson);
            var decision = doc.RootElement.GetProperty("decision").GetString();
            var reason   = doc.RootElement.TryGetProperty("reason", out var r) ? r.GetString() : "";
            Console.WriteLine($"[API] Resolved decision for Session {sessionId}: {decision}");
            return Results.Ok(new { decision, reason });
        }
        catch
        {
            return Results.Ok(new { decision = resultJson, reason = "" });
        }
    }
    else
    {
        Console.WriteLine($"[API] Timeout for Session {sessionId}. Falling back to 'ask'.");
        return Results.Ok(new { decision = "ask", reason = "" });
    }
});

// ─────────────────────────────────────────────────────────────────────────────
// POST /hook/notify  ─  fire-and-forget push to phone (no decision required)
//                       Used by the Stop hook to tell the phone Claude is done.
// ─────────────────────────────────────────────────────────────────────────────
app.MapPost("/hook/notify", async (HttpContext ctx, NotifyRequest request) =>
{
    if (!ValidateToken(ctx)) return Results.Unauthorized();

    // Stop fires reliably every turn regardless of whether any tool was called, so
    // this is the most robust place to register a project - a chat-only turn (no
    // PreToolUse/PostToolUse at all) would otherwise never show up in KnownProjects.
    if (!string.IsNullOrWhiteSpace(request.Cwd))
    {
        LastKnownCwd = request.Cwd;
        KnownProjects[request.Cwd] = DateTime.UtcNow;
        _ = Task.Run(SaveProjectState);
    }

    var payload = JsonSerializer.Serialize(new
    {
        type    = "agent_done",
        message = request.Message ?? "Claude finished working",
        detail  = request.Detail  ?? ""
    });

    Console.WriteLine($"[Notify] agent_done - {request.Message}");

    bool wsSent = false;
    if (WsClientStore.HasClients)
    {
        try   { wsSent = await WsClientStore.BroadcastAsync(payload); }
        catch (Exception ex) { Console.WriteLine($"[Notify] WS error: {ex.Message}"); }
    }

    return Results.Ok(new { success = true, ws_delivered = wsSent });
});

// ─────────────────────────────────────────────────────────────────────────────
// POST /hook/session-update  ─  fire-and-forget chunked progress ping (Phase 2:
//                                Session Access). Fired by on_tool_done.ps1 after
//                                each PostToolUse - no decision required, same
//                                shape as /hook/notify above.
// ─────────────────────────────────────────────────────────────────────────────
app.MapPost("/hook/session-update", async (HttpContext ctx, SessionUpdateRequest request) =>
{
    if (!ValidateToken(ctx)) return Results.Unauthorized();

    if (!string.IsNullOrWhiteSpace(request.Cwd))
    {
        LastKnownCwd = request.Cwd;
        KnownProjects[request.Cwd] = DateTime.UtcNow;
        _ = Task.Run(SaveProjectState);
    }

    var wsSent = await BroadcastSessionEventAsync(
        "session_update", request.Message ?? "Still working...", request.Detail ?? "", request.ToolName ?? "", request.Cwd ?? "");
    return Results.Ok(new { success = true, ws_delivered = wsSent });
});

// ─────────────────────────────────────────────────────────────────────────────
// GET /hook/known-projects  ─  phone lists every distinct project directory any hook
//                              has reported, most-recently-seen first, so the user
//                              can explicitly pick which one a new phone conversation
//                              should target instead of trusting LastKnownCwd.
// ─────────────────────────────────────────────────────────────────────────────
app.MapGet("/hook/known-projects", (HttpContext ctx) =>
{
    if (!ValidateToken(ctx)) return Results.Unauthorized();
    var projects = KnownProjects
        .OrderByDescending(kv => kv.Value)
        .Select(kv => new { path = kv.Key, label = Path.GetFileName(kv.Key.TrimEnd('\\')), lastSeenUtc = kv.Value })
        .ToList();
    return Results.Ok(projects);
});

// ─────────────────────────────────────────────────────────────────────────────
// POST /hook/send-prompt  ─  phone → PC (Phase 2: Session Access). Runs the
//                            prompt as a HEADLESS `claude -p` process - NOT
//                            injected into the live interactive session. This
//                            was originally OS-level keystroke injection (typed
//                            into the live VS Code window via the tray app), but
//                            that could misdirect into the wrong control with no
//                            way to verify or undo it. A headless run has no such
//                            failure mode: it's a fully isolated new session in
//                            the last-known project directory, gated by the same
//                            PreToolUse/PostToolUse hooks as any other session
//                            (confirmed live - hooks are global, not session-
//                            scoped). Fire-and-forget from the caller's
//                            perspective; progress/result arrive independently
//                            via /hook/session-update broadcasts.
// ─────────────────────────────────────────────────────────────────────────────
app.MapPost("/hook/send-prompt", (HttpContext ctx, SendPromptRequest request) =>
{
    if (!ValidateToken(ctx)) return Results.Unauthorized();
    if (string.IsNullOrWhiteSpace(request.Prompt))
        return Results.BadRequest(new { error = "prompt is required" });

    // An explicit project_dir from the phone always wins (the user picked it). With
    // none given, fall back to whatever LastKnownCwd currently is - same convenience
    // behavior as before a project was ever explicitly chosen.
    var cwd = !string.IsNullOrWhiteSpace(request.ProjectDir) ? request.ProjectDir : LastKnownCwd;
    if (string.IsNullOrEmpty(cwd) || !Directory.Exists(cwd))
    {
        Console.WriteLine("[SendPrompt] No known working directory yet - cannot run headless prompt");
        return Results.Problem("No active project directory known yet - trigger a tool call in your session first, or pick a project.");
    }

    // Defense in depth: the phone's own project picker only ever offers directories
    // from KnownProjects (see /hook/known-projects), so a legitimate request can never
    // name anything else. Rejecting anything outside that set means a stolen token
    // can't be used to point a headless run at an arbitrary directory on disk.
    if (!KnownProjects.ContainsKey(cwd))
    {
        Console.WriteLine($"[SendPrompt] Rejected unknown project directory: {cwd}");
        return Results.Problem("That directory isn't a known project - trigger a tool call there first, or pick a project the phone already knows about.");
    }

    var useCodex = string.Equals(request.Agent, "codex", StringComparison.OrdinalIgnoreCase);

    if (useCodex)
    {
        var (codexPath, codexProfile) = FindCodexCliInfo();
        if (codexPath == null)
        {
            Console.WriteLine("[SendPrompt] codex.exe not found - cannot run headless prompt");
            return Results.Problem("Codex CLI not found on this machine.");
        }
        _ = Task.Run(() => RunHeadlessCodexPromptAsync(codexPath, codexProfile, cwd, request.Prompt));
        Console.WriteLine($"[SendPrompt] Queued headless Codex run in {cwd}");
        return Results.Ok(new { success = true, queued = true });
    }

    var (cliPath, userProfile) = FindClaudeCliInfo();
    if (cliPath == null)
    {
        Console.WriteLine("[SendPrompt] claude.exe not found - cannot run headless prompt");
        return Results.Problem("Claude Code CLI not found on this machine.");
    }

    _ = Task.Run(() => RunHeadlessPromptAsync(cliPath, userProfile, cwd, request.Prompt));
    Console.WriteLine($"[SendPrompt] Queued headless run in {cwd}");
    return Results.Ok(new { success = true, queued = true });

    async Task RunHeadlessPromptAsync(string exePath, string? profileDir, string workDir, string prompt)
    {
        await PromptRunLock.WaitAsync();
        try
        {
            Console.WriteLine($"[SendPrompt] Starting headless run in {workDir}");
            var psi = new ProcessStartInfo
            {
                FileName               = exePath,
                WorkingDirectory       = workDir,
                RedirectStandardOutput = true,
                RedirectStandardError  = true,
                UseShellExecute        = false,
                CreateNoWindow         = true,
                StandardOutputEncoding = Encoding.UTF8
            };
            psi.ArgumentList.Add("-p");
            psi.ArgumentList.Add(prompt);
            psi.ArgumentList.Add("--output-format");
            psi.ArgumentList.Add("stream-json");
            psi.ArgumentList.Add("--verbose");

            // Continue this DIRECTORY's conversation across phone-sent prompts instead
            // of a fresh amnesiac session every time - confirmed live that --resume
            // gives real conversational memory. A directory with no prior session_id
            // naturally starts fresh. Keyed per-directory (not global) so switching
            // projects on the phone doesn't disturb other projects' threads.
            var resumeId = ProjectSessions.TryGetValue(workDir, out var existingSid) ? existingSid : null;
            if (!string.IsNullOrEmpty(resumeId))
            {
                psi.ArgumentList.Add("--resume");
                psi.ArgumentList.Add(resumeId);
            }

            // This service runs as LocalSystem - without this override, the child
            // process inherits SYSTEM's own profile and claude.exe can't find the
            // real user's authentication under <profile>\.claude\ (confirmed live:
            // failed with is_error=true in seconds until this was added).
            if (!string.IsNullOrEmpty(profileDir))
            {
                psi.EnvironmentVariables["USERPROFILE"]    = profileDir;
                psi.EnvironmentVariables["HOME"]           = profileDir;
                psi.EnvironmentVariables["APPDATA"]        = Path.Combine(profileDir, "AppData", "Roaming");
                psi.EnvironmentVariables["LOCALAPPDATA"]   = Path.Combine(profileDir, "AppData", "Local");
                psi.EnvironmentVariables["HOMEDRIVE"]      = Path.GetPathRoot(profileDir)?.TrimEnd('\\');
                psi.EnvironmentVariables["HOMEPATH"]       = profileDir.Substring(Path.GetPathRoot(profileDir)?.TrimEnd('\\')?.Length ?? 0);
            }

            using var process = new Process { StartInfo = psi };
            process.Start();

            string? resultText = null;
            var isError = false;
            var gotResult = false;

            string? line;
            while ((line = await process.StandardOutput.ReadLineAsync()) != null)
            {
                if (string.IsNullOrWhiteSpace(line)) continue;
                try
                {
                    using var doc = JsonDocument.Parse(line);
                    var root = doc.RootElement;
                    var type = root.TryGetProperty("type", out var t) ? t.GetString() : null;

                    if (type == "assistant" &&
                        root.TryGetProperty("message", out var msg) &&
                        msg.TryGetProperty("content", out var content) &&
                        content.ValueKind == JsonValueKind.Array)
                    {
                        foreach (var block in content.EnumerateArray())
                        {
                            if (block.TryGetProperty("type", out var bt) && bt.GetString() == "text" &&
                                block.TryGetProperty("text", out var txt))
                            {
                                var text = txt.GetString() ?? "";
                                if (text.Length > 0)
                                    await BroadcastSessionEventAsync("session_update", "Claude replied", Truncate(text, 500), cwd: workDir, agent: "claude");
                            }
                        }
                    }
                    else if (type == "result")
                    {
                        gotResult   = true;
                        isError     = root.TryGetProperty("is_error", out var ie) && ie.GetBoolean();
                        resultText  = root.TryGetProperty("result", out var r) ? r.GetString() : "";

                        // Only pin a session to resume from on a genuine success - a
                        // transient failure shouldn't poison future resumes; the next
                        // message just starts fresh instead. Keyed by this run's own
                        // directory, not a global slot.
                        if (!isError && root.TryGetProperty("session_id", out var sid) && sid.GetString() is { } sidValue)
                        {
                            ProjectSessions[workDir] = sidValue;
                            _ = Task.Run(SaveProjectState);
                        }
                    }
                }
                catch (JsonException) { /* not every line is JSON we need - skip */ }
            }

            await process.WaitForExitAsync();

            if (gotResult)
            {
                // A resumed run that still failed means this directory's pinned session
                // is no longer resumable for some reason (deleted transcript, etc.) -
                // clear just that entry so the NEXT message to this directory starts a
                // fresh conversation instead of repeating this same failure forever.
                // Other directories' sessions are untouched.
                if (isError && resumeId != null)
                {
                    ProjectSessions.TryRemove(workDir, out _);
                    _ = Task.Run(SaveProjectState);
                    Console.WriteLine($"[SendPrompt] Resumed run failed for {workDir} - cleared its pinned session, next message starts fresh");
                }

                await BroadcastSessionEventAsync(
                    "prompt_result", isError ? "Prompt finished with an error" : "Prompt finished", Truncate(resultText ?? "", 3999), cwd: workDir, agent: "claude");
                Console.WriteLine($"[SendPrompt] Completed (is_error={isError}): {Truncate(resultText, 300)}");
            }
            else
            {
                await BroadcastSessionEventAsync(
                    "prompt_result", "Prompt failed", $"Process exited with code {process.ExitCode} before producing a result.", cwd: workDir, agent: "claude");
                Console.WriteLine($"[SendPrompt] Ended without a result message (exit code {process.ExitCode})");
            }
        }
        catch (Exception ex)
        {
            Console.WriteLine($"[SendPrompt] Failed to run headless prompt: {ex.Message}");
            await BroadcastSessionEventAsync("prompt_result", "Prompt failed", ex.Message, cwd: workDir, agent: "claude");
        }
        finally
        {
            PromptRunLock.Release();
        }
    }

    // Codex counterpart to RunHeadlessPromptAsync above - same shape (lock, spawn,
    // parse the CLI's own JSON stream, broadcast progress/result, pin a resumable id
    // per directory), adapted to Codex's `exec` subcommand and event schema instead of
    // Claude's `-p --output-format stream-json`. Live-verified: `codex exec` fires the
    // exact same PreToolUse hook (on_approval_request.ps1 under .codex\hooks\) as an
    // interactive Codex session - approval_policy="never" + sandbox_mode=
    // "danger-full-access" in the user's config.toml means Codex's own native approval
    // UI is fully disabled and this hook is the sole gate, confirmed still firing and
    // blocking on the phone's decision in headless exec mode exactly as it does
    // interactively. No --dangerously-bypass-approvals-and-sandbox or
    // --dangerously-bypass-hook-trust is passed - both would either bypass the phone
    // gate entirely or aren't needed since hook trust is already established from this
    // user's prior interactive Codex use.
    async Task RunHeadlessCodexPromptAsync(string exePath, string? profileDir, string workDir, string prompt)
    {
        await PromptRunLock.WaitAsync();
        try
        {
            Console.WriteLine($"[SendPrompt] Starting headless Codex run in {workDir}");

            // Codex's resumable identifier is a thread_id - a separate namespace from
            // Claude's session_id, so this directory's Codex thread is tracked
            // independently of any Claude session_id pinned for the same directory.
            var resumeId = CodexProjectSessions.TryGetValue(workDir, out var existingThreadId) ? existingThreadId : null;

            var psi = new ProcessStartInfo
            {
                FileName               = exePath,
                WorkingDirectory       = workDir,
                RedirectStandardOutput = true,
                RedirectStandardError  = true,
                UseShellExecute        = false,
                CreateNoWindow         = true,
                StandardOutputEncoding = Encoding.UTF8
            };

            // Explicit `-c` overrides instead of relying on the user's own config.toml
            // already having these set - confirmed live on two fresh PCs that a default
            // config.toml (sandbox_mode left as the Windows sandbox, not
            // "danger-full-access") makes `codex exec` try to initialize the Windows
            // sandbox helper and fail with `helper_sandbox_lock_failed` before
            // AethelHook's own Codex hook (the actual phone approval gate) ever runs.
            // Overriding these here doesn't bypass that gate - it only stops Codex's
            // own native sandbox/approval UI from getting in the way of a headless run,
            // exactly like setting them in config.toml does on this dev machine.
            void AddSandboxOverrides()
            {
                psi.ArgumentList.Add("-c");
                psi.ArgumentList.Add("sandbox_mode=\"danger-full-access\"");
                psi.ArgumentList.Add("-c");
                psi.ArgumentList.Add("approval_policy=\"never\"");
            }

            psi.ArgumentList.Add("exec");
            if (!string.IsNullOrEmpty(resumeId))
            {
                // `codex exec resume <id> "<prompt>"` - no -C here: a resumed session
                // already carries its own working directory from when it was created,
                // and `exec resume` has no --cd flag at all (confirmed via --help).
                psi.ArgumentList.Add("resume");
                AddSandboxOverrides();
                psi.ArgumentList.Add("--json");
                psi.ArgumentList.Add("--skip-git-repo-check");
                psi.ArgumentList.Add(resumeId);
            }
            else
            {
                AddSandboxOverrides();
                psi.ArgumentList.Add("--json");
                // AethelHook itself isn't a git repo, and there's no guarantee an
                // arbitrary project directory is either - always allow non-repo dirs.
                psi.ArgumentList.Add("--skip-git-repo-check");
                psi.ArgumentList.Add("-C");
                psi.ArgumentList.Add(workDir);
            }
            psi.ArgumentList.Add(prompt);

            // Same LocalSystem-profile override as the Claude runner (see its own
            // comment above) - plus CODEX_HOME explicitly, since config.toml showed
            // Codex reading it directly rather than always deriving it from HOME.
            if (!string.IsNullOrEmpty(profileDir))
            {
                psi.EnvironmentVariables["USERPROFILE"]  = profileDir;
                psi.EnvironmentVariables["HOME"]         = profileDir;
                psi.EnvironmentVariables["APPDATA"]      = Path.Combine(profileDir, "AppData", "Roaming");
                psi.EnvironmentVariables["LOCALAPPDATA"] = Path.Combine(profileDir, "AppData", "Local");
                psi.EnvironmentVariables["HOMEDRIVE"]    = Path.GetPathRoot(profileDir)?.TrimEnd('\\');
                psi.EnvironmentVariables["HOMEPATH"]     = profileDir.Substring(Path.GetPathRoot(profileDir)?.TrimEnd('\\')?.Length ?? 0);
                psi.EnvironmentVariables["CODEX_HOME"]   = Path.Combine(profileDir, ".codex");
            }

            using var process = new Process { StartInfo = psi };
            process.Start();

            string? threadId  = null;
            string? resultText = null;
            var isError   = false;
            var gotResult = false;

            string? line;
            while ((line = await process.StandardOutput.ReadLineAsync()) != null)
            {
                if (string.IsNullOrWhiteSpace(line)) continue;
                try
                {
                    using var doc = JsonDocument.Parse(line);
                    var root = doc.RootElement;
                    var type = root.TryGetProperty("type", out var t) ? t.GetString() : null;

                    if (type == "thread.started" && root.TryGetProperty("thread_id", out var tid))
                    {
                        threadId = tid.GetString();
                    }
                    else if (type == "item.completed" &&
                             root.TryGetProperty("item", out var item) &&
                             item.TryGetProperty("type", out var itemType) && itemType.GetString() == "agent_message" &&
                             item.TryGetProperty("text", out var txt))
                    {
                        var text = txt.GetString() ?? "";
                        if (text.Length > 0)
                        {
                            resultText = text;
                            await BroadcastSessionEventAsync("session_update", "Codex replied", Truncate(text, 500), cwd: workDir, agent: "codex");
                        }
                    }
                    else if (type == "turn.completed")
                    {
                        gotResult = true;
                        isError   = false;
                    }
                    else if (type == "turn.failed" || type == "error")
                    {
                        gotResult = true;
                        isError   = true;
                        if (root.TryGetProperty("message", out var errMsg) && errMsg.GetString() is { } errText && errText.Length > 0)
                            resultText = errText;
                    }
                }
                catch (JsonException) { /* not every line is JSON we need - skip */ }
            }

            await process.WaitForExitAsync();

            if (gotResult)
            {
                if (!isError && threadId != null)
                {
                    CodexProjectSessions[workDir] = threadId;
                    _ = Task.Run(SaveProjectState);
                }
                else if (isError && resumeId != null)
                {
                    // Same reasoning as the Claude runner: a resumed run that still
                    // failed means this thread is no longer resumable - clear it so
                    // the next message starts fresh instead of repeating the failure.
                    CodexProjectSessions.TryRemove(workDir, out _);
                    _ = Task.Run(SaveProjectState);
                    Console.WriteLine($"[SendPrompt] Resumed Codex run failed for {workDir} - cleared its pinned thread, next message starts fresh");
                }

                await BroadcastSessionEventAsync(
                    "prompt_result", isError ? "Prompt finished with an error" : "Prompt finished", Truncate(resultText ?? "", 3999), cwd: workDir, agent: "codex");
                Console.WriteLine($"[SendPrompt] Codex completed (is_error={isError}): {Truncate(resultText, 300)}");
            }
            else
            {
                await BroadcastSessionEventAsync(
                    "prompt_result", "Prompt failed", $"Process exited with code {process.ExitCode} before producing a result.", cwd: workDir, agent: "codex");
                Console.WriteLine($"[SendPrompt] Codex run ended without a result message (exit code {process.ExitCode})");
            }
        }
        catch (Exception ex)
        {
            Console.WriteLine($"[SendPrompt] Failed to run headless Codex prompt: {ex.Message}");
            await BroadcastSessionEventAsync("prompt_result", "Prompt failed", ex.Message, cwd: workDir, agent: "codex");
        }
        finally
        {
            PromptRunLock.Release();
        }
    }
});

// ─────────────────────────────────────────────────────────────────────────────
// POST /hook/respond  ─  phone posts its decision back via HTTP
// ─────────────────────────────────────────────────────────────────────────────
app.MapPost("/hook/respond", (HttpContext ctx, RespondRequest request) =>
{
    if (!ValidateToken(ctx)) return Results.Unauthorized();
    if (string.IsNullOrWhiteSpace(request.SessionId) || string.IsNullOrWhiteSpace(request.Decision))
        return Results.BadRequest(new { error = "session_id and decision are required" });

    Console.WriteLine($"Session ID: {request.SessionId}, Decision: {request.Decision}");

    var normalizedDecision = NormalizeDecision(request.Decision);
    var safeReason  = (request.Reason ?? "").Replace("\\", "\\\\").Replace("\"", "\\\"");
    var resultJson  = $"{{\"decision\":\"{normalizedDecision}\",\"reason\":\"{safeReason}\"}}";
    var targetId    = request.SessionId;

    if (targetId.Equals("latest", StringComparison.OrdinalIgnoreCase) && !DecisionStore.PendingDecisions.IsEmpty)
    {
        targetId = System.Linq.Enumerable.First(DecisionStore.PendingDecisions.Keys);
        Console.WriteLine($"[API] Resolved 'latest' to: {targetId}");
    }

    if (DecisionStore.PendingDecisions.TryGetValue(targetId, out var tcs))
    {
        tcs.TrySetResult(resultJson);
        Console.WriteLine($"[API] Decision '{normalizedDecision}' sent to session: {targetId}");
        DecisionStore.PropagateToShadows(targetId, resultJson);
    }
    else
    {
        DecisionStore.RecentDecisions[targetId] = resultJson;
        DecisionStore.PropagateToShadows(targetId, resultJson);
        Console.WriteLine($"[API] Buffered early decision '{normalizedDecision}' for session: {targetId}");
        _ = Task.Delay(TimeSpan.FromSeconds(120)).ContinueWith(t =>
            DecisionStore.RecentDecisions.TryRemove(targetId, out string? _));
    }
    TrayFeedStore.Resolve(targetId, normalizedDecision);

    return Results.Ok(new { logged = true, decision = normalizedDecision, resolved_session_id = targetId });
});

// ─────────────────────────────────────────────────────────────────────────────
// POST /hook/ask-question  ─  hook fires this for AskUserQuestion; pushed over WS
// ─────────────────────────────────────────────────────────────────────────────
app.MapPost("/hook/ask-question", async (HttpContext ctx, AskQuestionRequest request) =>
{
    if (!ValidateToken(ctx)) return Results.Unauthorized();
    if (!IsGatewayActive) return Results.Problem("Gateway inactive", statusCode: 503);

    Console.WriteLine($"[AskQuestion] Received question(s) for Session: {request.SessionId}");

    // Register TCS immediately so wait-answer doesn't miss the answer
    var tcs = QuestionStore.PendingAnswers.GetOrAdd(request.SessionId, _ => new TaskCompletionSource<string>());

    var answerUrl = $"{ApiBaseUrl}/hook/answer-question";
    var payload = JsonSerializer.Serialize(new
    {
        type       = "ask_question",
        session_id = request.SessionId,
        questions  = request.Questions,
        answer_url = answerUrl
    });

    // Store payload so a phone that connects late can receive it on join
    QuestionStore.PendingQuestionPayloads[request.SessionId] = (payload, DateTime.UtcNow);

    // ── 1. Try WebSocket first (LAN, <100ms) ─────────────────────────────
    bool wsSent = false;
    if (WsClientStore.HasClients)
    {
        try
        {
            wsSent = await WsClientStore.BroadcastAsync(payload);
            if (wsSent)
                Console.WriteLine($"[WS] Pushed question to phone for session {request.SessionId}");
        }
        catch (Exception ex)
        {
            Console.WriteLine($"[WS] Question push failed: {ex.Message}");
        }
    }
    else
    {
        Console.WriteLine($"[WS] No open sockets - question for session {request.SessionId} will fail open on timeout");
    }

    return Results.Ok(new { success = true, queued = true, ws_delivered = wsSent });
});

// ─────────────────────────────────────────────────────────────────────────────
// GET /hook/wait-answer/{sessionId}  ─  hook long-polls here for the phone's answer
// ─────────────────────────────────────────────────────────────────────────────
app.MapGet("/hook/wait-answer/{sessionId}", async (HttpContext ctx, string sessionId) =>
{
    if (!ValidateToken(ctx)) return Results.Unauthorized();
    // Fast path: answer already arrived.
    // NOTE: wrap the stored raw JSON directly via Results.Content rather than parsing it into
    // a JsonDocument and returning `doc.RootElement` inside an anonymous object - ASP.NET
    // serializes the response after the handler returns, by which point a `using`-disposed
    // JsonDocument throws ObjectDisposedException on its RootElement. Both producers of this
    // string (WS handler, /hook/answer-question) always write validated JSON via GetRawText(),
    // so string-wrapping here is safe without re-parsing.
    if (QuestionStore.RecentAnswers.TryRemove(sessionId, out var earlyAnswer))
    {
        Console.WriteLine($"[API] Pre-arrived answer for Session {sessionId}");
        return Results.Content($"{{\"answers\":{earlyAnswer}}}", "application/json; charset=utf-8");
    }

    Console.WriteLine($"[API] Waiting for answer on Session: {sessionId}...");
    var tcs = QuestionStore.PendingAnswers.GetOrAdd(sessionId, _ => new TaskCompletionSource<string>());

    var completedTask = await Task.WhenAny(tcs.Task, Task.Delay(TimeSpan.FromSeconds(300)));
    QuestionStore.PendingAnswers.TryRemove(sessionId, out _);
    QuestionStore.PendingQuestionPayloads.TryRemove(sessionId, out _);

    if (completedTask == tcs.Task)
    {
        var resultJson = await tcs.Task;
        Console.WriteLine($"[API] Resolved answer for Session {sessionId}");
        return Results.Content($"{{\"answers\":{resultJson}}}", "application/json; charset=utf-8");
    }
    else
    {
        Console.WriteLine($"[API] Timeout waiting for answer on Session {sessionId}.");
        return Results.Ok(new { answers = new { } });
    }
});

// ─────────────────────────────────────────────────────────────────────────────
// POST /hook/answer-question  ─  phone posts its answer via HTTP
// ─────────────────────────────────────────────────────────────────────────────
app.MapPost("/hook/answer-question", (HttpContext ctx, AnswerQuestionRequest request) =>
{
    if (!ValidateToken(ctx)) return Results.Unauthorized();
    if (string.IsNullOrWhiteSpace(request.SessionId))
        return Results.BadRequest(new { error = "session_id is required" });

    Console.WriteLine($"[AskQuestion] Answer received via HTTP for Session: {request.SessionId}");

    var answersJson = request.Answers.GetRawText();
    var targetId     = request.SessionId;

    if (targetId.Equals("latest", StringComparison.OrdinalIgnoreCase) && !QuestionStore.PendingAnswers.IsEmpty)
    {
        targetId = System.Linq.Enumerable.First(QuestionStore.PendingAnswers.Keys);
        Console.WriteLine($"[API] Resolved 'latest' to: {targetId}");
    }

    if (QuestionStore.PendingAnswers.TryGetValue(targetId, out var tcs))
    {
        tcs.TrySetResult(answersJson);
        Console.WriteLine($"[API] Answer sent to session: {targetId}");
    }
    else
    {
        QuestionStore.RecentAnswers[targetId] = answersJson;
        Console.WriteLine($"[API] Buffered early answer for session: {targetId}");
        _ = Task.Delay(TimeSpan.FromSeconds(120)).ContinueWith(t =>
            QuestionStore.RecentAnswers.TryRemove(targetId, out string? _));
    }

    return Results.Ok(new { logged = true, resolved_session_id = targetId });
});

// ─────────────────────────────────────────────────────────────────────────────
// POST /hook/plan-request  ─  on_exit_plan.ps1 fires this for ExitPlanMode ("Accept
//                              this plan?"); pushed over WS (notification only - the
//                              phone fetches the full plan via GET /hook/plan/{id}).
// ─────────────────────────────────────────────────────────────────────────────
app.MapPost("/hook/plan-request", async (HttpContext ctx, PlanRequest request) =>
{
    if (!ValidateToken(ctx)) return Results.Unauthorized();

    Console.WriteLine($"[PlanReview] Received plan for Session: {request.SessionId}");

    var tcs = PlanReviewStore.PendingDecisions.GetOrAdd(request.SessionId, _ => new TaskCompletionSource<string>());
    PlanReviewStore.PendingPlans[request.SessionId] = (request.Plan, DateTime.UtcNow);

    // Backstop TTL so a plan can never be stuck in memory forever if the phone never
    // responds and the hook's long-poll doesn't clear it (see wait-plan-decision above).
    _ = Task.Delay(TimeSpan.FromMinutes(15)).ContinueWith(t =>
        PlanReviewStore.PendingPlans.TryRemove(request.SessionId, out _));

    // Tailscale first (works off the PC's LAN), LAN second - mirrors the phone's own
    // LAN-then-Tailscale auto-switch in AethelHookWebSocket.kt.
    var candidateBases = tailscaleIp != null
        ? new[] { $"https://{tailscaleIp}:5264", ApiBaseUrl }
        : new[] { ApiBaseUrl };
    var planUrls    = candidateBases.Select(b => $"{b}/hook/plan/{request.SessionId}").ToArray();
    var respondUrls = candidateBases.Select(b => $"{b}/hook/plan-decision").ToArray();
    var respondUrl  = respondUrls[0];
    var planUrl     = planUrls[0];
    var preview     = Truncate(request.Plan, 150);
    var payload = JsonSerializer.Serialize(new
    {
        type         = "plan_review",
        session_id   = request.SessionId,
        plan         = request.Plan,
        plan_preview = preview,
        respond_url  = respondUrl,
        plan_url     = planUrl,
        respond_urls = respondUrls,
        plan_urls    = planUrls
    });

    bool wsSent = false;
    if (WsClientStore.HasClients)
    {
        try
        {
            wsSent = await WsClientStore.BroadcastAsync(payload);
            if (wsSent)
                Console.WriteLine($"[WS] Pushed plan review to phone for session {request.SessionId}");
        }
        catch (Exception ex)
        {
            Console.WriteLine($"[WS] Plan push failed: {ex.Message}");
        }
    }
    else
    {
        Console.WriteLine($"[WS] No open sockets - plan review for session {request.SessionId} will fail open on timeout");
    }

    return Results.Ok(new { success = true, queued = true, ws_delivered = wsSent });
});

// ─────────────────────────────────────────────────────────────────────────────
// GET /hook/plan/{sessionId}  ─  phone fetches the full plan text (never sent through
//                                 a notification payload, to stay clear of size limits).
// ─────────────────────────────────────────────────────────────────────────────
app.MapGet("/hook/plan/{sessionId}", (HttpContext ctx, string sessionId) =>
{
    if (!ValidateToken(ctx)) return Results.Unauthorized();
    if (!PlanReviewStore.PendingPlans.TryGetValue(sessionId, out var entry))
        return Results.NotFound();
    return Results.Ok(new { plan = entry.Plan });
});

// ─────────────────────────────────────────────────────────────────────────────
// GET /hook/wait-plan-decision/{sessionId}  ─  hook long-polls here (up to 600s - a
//                                               plan takes longer to read than a command).
// ─────────────────────────────────────────────────────────────────────────────
app.MapGet("/hook/wait-plan-decision/{sessionId}", async (HttpContext ctx, string sessionId) =>
{
    if (!ValidateToken(ctx)) return Results.Unauthorized();
    if (PlanReviewStore.RecentDecisions.TryRemove(sessionId, out var earlyDecision))
    {
        Console.WriteLine($"[API] Pre-arrived plan decision for Session {sessionId}");
        return Results.Content(earlyDecision, "application/json; charset=utf-8");
    }

    Console.WriteLine($"[API] Waiting for plan decision on Session: {sessionId}...");
    var tcs = PlanReviewStore.PendingDecisions.GetOrAdd(sessionId, _ => new TaskCompletionSource<string>());

    var completedTask = await Task.WhenAny(tcs.Task, Task.Delay(TimeSpan.FromSeconds(600)));
    PlanReviewStore.PendingDecisions.TryRemove(sessionId, out _);
    // Note: PendingPlans is intentionally NOT removed here - a long-poll completing
    // (including by timeout) doesn't mean the plan was resolved. It's only evicted on
    // an actual decision (/hook/plan-decision, WS plan_review_decision) or by the TTL
    // sweep below, so a stray/duplicate poll can't 404 a still-pending plan fetch.

    if (completedTask == tcs.Task)
    {
        var resultJson = await tcs.Task;
        Console.WriteLine($"[API] Resolved plan decision for Session {sessionId}");
        return Results.Content(resultJson, "application/json; charset=utf-8");
    }
    else
    {
        Console.WriteLine($"[API] Timeout waiting for plan decision on Session {sessionId}.");
        return Results.Ok(new { decision = "", feedback = "" });
    }
});

// ─────────────────────────────────────────────────────────────────────────────
// POST /hook/plan-decision  ─  phone posts its decision via HTTP (WS-unavailable fallback)
// ─────────────────────────────────────────────────────────────────────────────
app.MapPost("/hook/plan-decision", (HttpContext ctx, PlanDecisionRequest request) =>
{
    if (!ValidateToken(ctx)) return Results.Unauthorized();
    if (string.IsNullOrWhiteSpace(request.SessionId) || string.IsNullOrWhiteSpace(request.Decision))
        return Results.BadRequest(new { error = "session_id and decision are required" });

    Console.WriteLine($"[PlanReview] Decision received via HTTP for Session: {request.SessionId}: {request.Decision}");

    var resultJson = JsonSerializer.Serialize(new { decision = request.Decision, feedback = request.Feedback ?? "" });
    PlanReviewStore.PendingPlans.TryRemove(request.SessionId, out _);

    if (PlanReviewStore.PendingDecisions.TryGetValue(request.SessionId, out var tcs))
    {
        tcs.TrySetResult(resultJson);
    }
    else
    {
        PlanReviewStore.RecentDecisions[request.SessionId] = resultJson;
        _ = Task.Delay(TimeSpan.FromSeconds(60)).ContinueWith(t =>
            PlanReviewStore.RecentDecisions.TryRemove(request.SessionId, out string? _));
    }

    return Results.Ok(new { logged = true });
});

// ─────────────────────────────────────────────────────────────────────────────
// QR pairing - replaces the old beacon-broadcasts-the-token flow.
// GET /pair is opened on the PC itself (loopback-only), renders a QR encoding a
// one-time sid+psk, and the phone POSTs /pair/claim (LAN-reachable) to redeem it
// for a real per-device token.
// ─────────────────────────────────────────────────────────────────────────────

const string PairingPageHtml = """
<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>AethelHook - Pair a device</title>
<style>
  body { font-family: -apple-system, Segoe UI, sans-serif; background: #0d1117; color: #e6edf3;
         display: flex; align-items: center; justify-content: center; min-height: 100vh; margin: 0; }
  .card { background: #161b22; border: 1px solid #30363d; border-radius: 16px; padding: 32px 40px;
          text-align: center; max-width: 360px; }
  h1 { font-size: 18px; margin: 0 0 20px; }
  img { width: 260px; height: 260px; border-radius: 8px; background: #fff; padding: 8px; }
  .status { margin-top: 16px; font-size: 14px; color: #8b949e; }
  .paired { font-size: 20px; color: #3fb950; margin: 40px 0; }
  button { margin-top: 16px; background: #21262d; color: #e6edf3; border: 1px solid #30363d;
           border-radius: 6px; padding: 8px 16px; cursor: pointer; font-size: 13px; }
  button:hover { background: #30363d; }
</style>
</head>
<body>
  <div class="card">
    <h1>Scan with the AethelHook app</h1>
    <div id="content">Generating code…</div>
    <div class="status" id="status"></div>
  </div>
<script>
  let sid = null, expiresAt = 0, pollTimer = null, tickTimer = null;

  async function newSession() {
    const res = await fetch('/pair/session', { method: 'POST' });
    const data = await res.json();
    sid = data.sid;
    expiresAt = Date.now() + data.expiresInSec * 1000;
    document.getElementById('content').innerHTML =
      '<img src="/pair/qr.png?sid=' + sid + '&t=' + Date.now() + '">';
    restartPolling();
    restartCountdown();
  }

  function restartCountdown() {
    clearInterval(tickTimer);
    tickTimer = setInterval(() => {
      const secsLeft = Math.max(0, Math.round((expiresAt - Date.now()) / 1000));
      document.getElementById('status').textContent = 'Expires in ' + secsLeft + 's';
      if (secsLeft <= 0) { clearInterval(tickTimer); newSession(); }
    }, 1000);
  }

  function restartPolling() {
    clearInterval(pollTimer);
    pollTimer = setInterval(async () => {
      const res = await fetch('/pair/status?sid=' + sid);
      const data = await res.json();
      if (data.status === 'claimed') {
        clearInterval(pollTimer);
        clearInterval(tickTimer);
        document.getElementById('content').innerHTML = '<div class="paired">✅ Paired</div>';
        document.getElementById('status').innerHTML =
          '<button onclick="newSession()">Pair another device</button>';
      }
    }, 1500);
  }

  newSession();
</script>
</body>
</html>
""";

app.MapGet("/pair", (HttpContext ctx) =>
{
    if (!IsLocalRequest(ctx)) return Results.StatusCode(403);
    return Results.Content(PairingPageHtml, "text/html");
});

app.MapPost("/pair/session", (HttpContext ctx) =>
{
    if (!IsLocalRequest(ctx)) return Results.StatusCode(403);
    var (sid, expiresInSec) = PairingStore.CreateSession();
    return Results.Ok(new { sid, expiresInSec });
});

app.MapGet("/pair/qr.png", (HttpContext ctx, string sid) =>
{
    if (!IsLocalRequest(ctx)) return Results.StatusCode(403);
    if (!PairingStore.TryGetPayload(sid, localIp, 5264, CertFingerprint, out var payloadJson))
        return Results.NotFound();

    using var generator = new QRCodeGenerator();
    using var data = generator.CreateQrCode(payloadJson, QRCodeGenerator.ECCLevel.M);
    var png = new PngByteQRCode(data).GetGraphic(8);
    return Results.File(png, "image/png");
});

app.MapGet("/pair/status", (HttpContext ctx, string sid) =>
{
    if (!IsLocalRequest(ctx)) return Results.StatusCode(403);
    return Results.Ok(new { status = PairingStore.GetStatus(sid) });
});

app.MapPost("/pair/claim", (HttpContext ctx, PairClaimRequest request) =>
{
    // Intentionally NOT loopback-restricted - the phone calls this from its own LAN IP.
    // Security comes from sid+psk being a single-use, 120s-lived, 128-bit secret pair
    // that only ever exists on-screen in the QR shown by the loopback-only /pair page.
    var (ok, deviceId, token, error) = PairingStore.TryClaim(request.Sid, request.Psk);
    if (!ok)
    {
        Console.WriteLine($"[Pairing] Claim failed from {ctx.Connection.RemoteIpAddress}: {error}");
        return Results.Ok(new { ok = false, error });
    }
    Console.WriteLine($"[Pairing] Device {deviceId} paired from {ctx.Connection.RemoteIpAddress}");
    return Results.Ok(new { ok = true, deviceId, token, ip = localIp, port = 5264 });
});

app.MapGet("/pair/devices", (HttpContext ctx) =>
{
    if (!IsLocalRequest(ctx)) return Results.StatusCode(403);
    return Results.Ok(DeviceRegistry.List());
});

app.MapDelete("/pair/devices/{id}", (HttpContext ctx, string id) =>
{
    if (!IsLocalRequest(ctx)) return Results.StatusCode(403);
    if (!Guid.TryParse(id, out var guid)) return Results.BadRequest(new { error = "invalid id" });
    var token = DeviceRegistry.GetToken(guid);
    var removed = DeviceRegistry.Revoke(guid);
    if (removed && token != null) WsClientStore.DisconnectByToken(token);
    return Results.Ok(new { removed });
});

// Loopback-only self-registration for the Windows tray app - no QR/psk ceremony
// needed since being on loopback already proves same-machine trust. Mints once and
// reuses the same "tray" device on subsequent calls (e.g. every tray app launch).
app.MapPost("/pair/local-token", (HttpContext ctx) =>
{
    if (!IsLocalRequest(ctx)) return Results.StatusCode(403);
    var existing = DeviceRegistry.List().FirstOrDefault(d => d.Label == "tray");
    if (existing != null) return Results.Ok(new { id = existing.Id, token = existing.Token });

    var (id, token) = DeviceRegistry.Register("tray");
    Console.WriteLine($"[Pairing] Tray app self-registered as device {id}");
    return Results.Ok(new { id, token });
});

// Polling-based live feed for the tray app (see TrayFeedStore).
app.MapGet("/tray/feed", (HttpContext ctx, int? limit) =>
{
    if (!IsLocalRequest(ctx)) return Results.StatusCode(403);
    return Results.Ok(TrayFeedStore.Recent(limit ?? 50));
});

// UDP beacon - broadcasts every 3s so the Android app can auto-discover the PC's IP.
// No longer includes the token (that's only ever handed out via QR pairing above).
// Format: "AETHELHOOK:5264" or "AETHELHOOK:5264:{tailscaleIp}"
_ = Task.Run(async () =>
{
    using var udp = new UdpClient();
    udp.EnableBroadcast = true;
    var endpoint = new IPEndPoint(IPAddress.Broadcast, 47263);
    while (true)
    {
        var tsIp    = GetTailscaleIpAddress();
        var payload = tsIp != null
            ? $"AETHELHOOK:5264:{tsIp}"
            : $"AETHELHOOK:5264";
        var bytes   = Encoding.UTF8.GetBytes(payload);
        try { await udp.SendAsync(bytes, bytes.Length, endpoint); }
        catch (Exception ex) { Console.WriteLine($"[Beacon] {ex.Message}"); }
        await Task.Delay(3000);
    }
});

// Restore hooks on startup; remove them when the service stops (so native dialogs reappear)
RestoreClaudeCodeHooks();
RestoreCodexHooks();
RestoreAntigravityHooks();
app.Lifetime.ApplicationStopping.Register(RemoveClaudeCodeHooks);
app.Lifetime.ApplicationStopping.Register(RemoveCodexHooks);
app.Lifetime.ApplicationStopping.Register(RemoveAntigravityHooks);

app.Run();

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────
static string LoadOrCreateApiToken()
{
    var dir  = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "AethelHook");
    Directory.CreateDirectory(dir);
    var path = Path.Combine(dir, "api_token.txt");
    if (File.Exists(path))
    {
        var t = File.ReadAllText(path).Trim();
        if (t.Length == 64) return t;
    }
    var bytes = new byte[32];
    System.Security.Cryptography.RandomNumberGenerator.Fill(bytes);
    var token = Convert.ToHexString(bytes).ToLower();
    File.WriteAllText(path, token);
    CryptoUtil.RestrictToAdminSystem(path);
    Console.WriteLine($"[Security] New API token generated. Token file: {path}");
    return token;
}

// Returns the Tailscale IP (100.x.x.x) if Tailscale is running, otherwise null.
static string? GetTailscaleIpAddress()
{
    foreach (var iface in NetworkInterface.GetAllNetworkInterfaces())
    {
        if (iface.OperationalStatus != OperationalStatus.Up) continue;
        if (iface.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
        foreach (var addr in iface.GetIPProperties().UnicastAddresses)
        {
            if (addr.Address.AddressFamily != AddressFamily.InterNetwork) continue;
            var ip = addr.Address.ToString();
            // Tailscale allocates from the 100.64.0.0/10 CGNAT range
            if (ip.StartsWith("100.")) return ip;
        }
    }
    return null;
}

// Loads the self-signed cert AethelHook uses for its phone-facing HTTPS listener,
// generating one on first run. The phone never validates this against a CA (there
// isn't one) - it pins the exact SHA-256 fingerprint it received out-of-band via the
// QR pairing flow instead, so the cert itself just needs to be a valid, working TLS
// server cert; a 20-year self-signed one is fine since nothing here chains to a
// public root and the phone-side check never looks at expiry, only the hash.
static X509Certificate2 LoadOrCreateHttpsCertificate(string localIp)
{
    var certDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "AethelHook");
    var certPath = Path.Combine(certDir, "aethelhook-cert.pfx");
    var certPasswordPath = Path.Combine(certDir, "aethelhook-cert.pwd");

    // Older versions of this file used a hardcoded PFX export password ("aethelhook"),
    // now public since this project is open source. A password baked into a public repo
    // is no password at all, so any cert generated under that scheme is force-regenerated
    // here (one extra forced re-pair on upgrade, same precedent as gotcha #20's original
    // rollout) rather than silently keeping the now-known password around.
    if (File.Exists(certPath) && !File.Exists(certPasswordPath))
    {
        Console.WriteLine("[TLS] Found a certificate from before per-install password randomization - regenerating");
        File.Delete(certPath);
    }

    string certPassword;
    if (File.Exists(certPasswordPath))
    {
        certPassword = File.ReadAllText(certPasswordPath).Trim();
    }
    else
    {
        var pwBytes = new byte[32];
        RandomNumberGenerator.Fill(pwBytes);
        certPassword = Convert.ToBase64String(pwBytes);
        Directory.CreateDirectory(certDir);
        File.WriteAllText(certPasswordPath, certPassword);
        CryptoUtil.RestrictToAdminSystem(certPasswordPath);
    }

    if (!File.Exists(certPath))
    {
        Console.WriteLine("[TLS] No certificate found - generating a new self-signed cert");
        using var rsa = RSA.Create(2048);
        var req = new CertificateRequest("CN=AethelHook", rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);

        var san = new SubjectAlternativeNameBuilder();
        san.AddIpAddress(IPAddress.Loopback);
        if (IPAddress.TryParse(localIp, out var parsedIp)) san.AddIpAddress(parsedIp);
        req.CertificateExtensions.Add(san.Build());
        req.CertificateExtensions.Add(new X509KeyUsageExtension(
            X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment, critical: true));
        req.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(
            new OidCollection { new Oid("1.3.6.1.5.5.7.3.1") }, critical: false)); // Server Authentication

        var notBefore = DateTimeOffset.UtcNow.AddDays(-1); // clock-skew safety margin
        var notAfter  = DateTimeOffset.UtcNow.AddYears(20);
        using var cert = req.CreateSelfSigned(notBefore, notAfter);

        Directory.CreateDirectory(Path.GetDirectoryName(certPath)!);
        File.WriteAllBytes(certPath, cert.Export(X509ContentType.Pfx, certPassword));
        CryptoUtil.RestrictToAdminSystem(certPath);
    }

    // Always reload from disk rather than handing Kestrel the freshly-created
    // in-memory cert directly, so first-run and every later restart behave
    // identically. MachineKeySet since this runs as a LocalSystem Windows Service.
    return X509CertificateLoader.LoadPkcs12FromFile(certPath, certPassword, X509KeyStorageFlags.MachineKeySet);
}

static string GetLocalIpAddress()
{
    // This runs once at service startup, and whatever it returns here gets baked into
    // every QR pairing code and status display for the rest of the process's life -
    // there's no later re-detection. AethelHook is a LocalSystem auto-start Windows
    // Service, which can start before Windows' network stack is fully up after a
    // reboot; confirmed live that a restart right at boot found no NIC with a real IP
    // yet and fell all the way through to the hardcoded 127.0.0.1 fallback below,
    // silently breaking QR pairing (the phone got a QR encoding "connect to
    // 127.0.0.1") until the service was restarted again later once the network was
    // actually up. Retry for up to a minute before accepting that fallback.
    for (var attempt = 0; attempt < 60; attempt++)
    {
        var ip = TryDetectLocalIp();
        if (ip != null) return ip;
        Thread.Sleep(1000);
    }
    return "127.0.0.1";
}

static string? TryDetectLocalIp()
{
    // UDP route-lookup trick: Connect() doesn't send anything, just resolves the source IP
    foreach (var target in new[] { "8.8.8.8", "192.168.43.1", "192.168.1.1" })
    {
        try
        {
            using var s = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, 0);
            s.Connect(target, 53);
            var ip = ((IPEndPoint)s.LocalEndPoint!).Address.ToString();
            if (!ip.StartsWith("127.")) return ip;
        }
        catch { }
    }
    // Fallback: iterate NICs
    foreach (var iface in NetworkInterface.GetAllNetworkInterfaces())
    {
        if (iface.OperationalStatus != OperationalStatus.Up) continue;
        if (iface.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
        foreach (var addr in iface.GetIPProperties().UnicastAddresses)
        {
            if (addr.Address.AddressFamily != AddressFamily.InterNetwork) continue;
            if (addr.Address.ToString().StartsWith("169.254")) continue; // APIPA
            return addr.Address.ToString();
        }
    }
    return null;
}

static string? FindClaudeSettingsPath()
{
    // When running as LocalSystem, UserProfile resolves to the system profile
    // (C:\Windows\...) rather than a real user's profile. Always prefer a profile
    // found under C:\Users\ so we write to the correct settings.json.
    var usersRoot = @"C:\Users";
    if (Directory.Exists(usersRoot))
    {
        foreach (var dir in Directory.GetDirectories(usersRoot))
        {
            var claudeDir = Path.Combine(dir, ".claude");
            if (Directory.Exists(claudeDir))
                return Path.Combine(claudeDir, "settings.json");
        }
    }

    // Fallback for dev/console mode where the process runs as the actual user
    var direct = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
        ".claude", "settings.json");
    if (Directory.Exists(Path.GetDirectoryName(direct)!))
        return direct;

    return null;
}

// Phase 2 (Session Access): no standalone `claude` CLI is guaranteed to be on
// PATH - confirmed on the dev machine it isn't, even with the VS Code extension
// installed and in daily use. The extension bundles a fully-functional copy of
// the same binary at <extension>\resources\native-binary\claude.exe, which is
// what headless `-p` runs actually use. Same "scan C:\Users\* for a real
// profile" pattern as FindClaudeSettingsPath, since this runs as SYSTEM.
//
// Also returns the owning user's profile directory - this service runs as
// LocalSystem, so a plain Process.Start of claude.exe inherits SYSTEM's own
// profile/environment, not the real user's. Without HOME/USERPROFILE/APPDATA
// pointed at the real profile, claude.exe can't find the user's authentication
// under <profile>\.claude\ and fails immediately (confirmed live: is_error=true
// within seconds). RunHeadlessPromptAsync uses this to override those env vars
// on the child process.
static (string? CliPath, string? UserProfile) FindClaudeCliInfo()
{
    var usersRoot = @"C:\Users";
    if (!Directory.Exists(usersRoot)) return (null, null);

    foreach (var dir in Directory.GetDirectories(usersRoot))
    {
        var extensionsDir = Path.Combine(dir, ".vscode", "extensions");
        if (!Directory.Exists(extensionsDir)) continue;

        var claudeExtDir = Directory.GetDirectories(extensionsDir, "anthropic.claude-code-*")
            .OrderByDescending(d => d)
            .FirstOrDefault();
        if (claudeExtDir == null) continue;

        var exePath = Path.Combine(claudeExtDir, "resources", "native-binary", "claude.exe");
        if (File.Exists(exePath)) return (exePath, dir);
    }

    return (null, null);
}

// Codex ships as its own desktop app (not a VS Code extension bundle like Claude Code),
// installing codex.exe under <profile>\AppData\Local\OpenAI\Codex\bin\<hash>\. Same
// "scan C:\Users\* for a real profile" pattern as FindClaudeCliInfo, since this runs as
// LocalSystem and needs the real user's profile dir for the same env-var override reason.
static (string? CliPath, string? UserProfile) FindCodexCliInfo()
{
    var usersRoot = @"C:\Users";
    if (!Directory.Exists(usersRoot)) return (null, null);

    foreach (var dir in Directory.GetDirectories(usersRoot))
    {
        var codexBinRoot = Path.Combine(dir, "AppData", "Local", "OpenAI", "Codex", "bin");
        if (!Directory.Exists(codexBinRoot)) continue;

        var exePath = Directory.GetDirectories(codexBinRoot)
            .Select(d => Path.Combine(d, "codex.exe"))
            .Where(File.Exists)
            .OrderByDescending(File.GetLastWriteTimeUtc)
            .FirstOrDefault();
        if (exePath != null) return (exePath, dir);
    }

    return (null, null);
}

// Shared by /hook/notify-style endpoints: broadcasts a fire-and-forget event to the
// phone over WS. Extracted here since /hook/session-update and the headless prompt
// runner (/hook/send-prompt) both need to push the identical shape of event.
static async Task<bool> BroadcastSessionEventAsync(string type, string message, string detail, string toolName = "", string cwd = "", string agent = "claude")
{
    var payload = JsonSerializer.Serialize(new { type, message, detail, tool_name = toolName, cwd, agent });
    Console.WriteLine($"[{type}] {message} ({toolName}) [{cwd}] <{agent}>");

    var wsSent = false;
    if (WsClientStore.HasClients)
    {
        try   { wsSent = await WsClientStore.BroadcastAsync(payload); }
        catch (Exception ex) { Console.WriteLine($"[{type}] WS error: {ex.Message}"); }
    }

    return wsSent;
}

static void RestoreClaudeCodeHooks()
{
    try
    {
        var settingsPath = FindClaudeSettingsPath();
        if (settingsPath == null)
        {
            Console.WriteLine("[API] Warning: could not locate .claude/settings.json - hooks not restored");
            return;
        }

        // Points at the installed, portable hook location (ProgramData) - never the dev
        // repo checkout - so this works identically on any machine the installer targets.
        const string hookCmd    = @"powershell.exe -ExecutionPolicy Bypass -Command ""& 'C:\ProgramData\AethelHook\hooks\on_approval_request.ps1'""";
        const string doneCmd    = @"powershell.exe -ExecutionPolicy Bypass -Command ""& 'C:\ProgramData\AethelHook\hooks\on_agent_done.ps1'""";
        const string askQCmd    = @"powershell.exe -ExecutionPolicy Bypass -Command ""& 'C:\ProgramData\AethelHook\hooks\on_ask_question.ps1'""";
        const string exitPlanCmd = @"powershell.exe -ExecutionPolicy Bypass -Command ""& 'C:\ProgramData\AethelHook\hooks\on_exit_plan.ps1'""";
        const string sessionStartCmd = @"powershell.exe -ExecutionPolicy Bypass -Command ""& 'C:\ProgramData\AethelHook\hooks\on_session_start.ps1'""";
        // Phase 2 (Session Access) chunked-progress heartbeat - fires after every tool
        // call, never blocks (on_tool_done.ps1 is fire-and-forget, short timeout).
        const string toolDoneCmd = @"powershell.exe -ExecutionPolicy Bypass -Command ""& 'C:\ProgramData\AethelHook\hooks\on_tool_done.ps1'""";
        var aethelAllow = new[] { "PowerShell(*)", "Write(*)", "Edit(*)", "Read(*)", "Bash(*)", "Grep(*)", "Glob(*)" };

        JsonObject settings;
        if (File.Exists(settingsPath))
        {
            settings = JsonNode.Parse(File.ReadAllText(settingsPath)) as JsonObject ?? new JsonObject();
        }
        else
        {
            settings = new JsonObject();
            Directory.CreateDirectory(Path.GetDirectoryName(settingsPath)!);
        }

        // Merge AethelHook entries into permissions.allow (preserve any user entries)
        if (settings["permissions"] is not JsonObject perms)
        {
            perms = new JsonObject();
            settings["permissions"] = perms;
        }
        if (perms["allow"] is not JsonArray currentAllow)
        {
            currentAllow = new JsonArray();
            perms["allow"] = currentAllow;
        }
        var existing = currentAllow.Select(x => x?.GetValue<string>() ?? "").ToHashSet();
        foreach (var entry in aethelAllow)
            if (!existing.Contains(entry))
                currentAllow.Add(JsonValue.Create(entry));

        // Always replace the hooks block to ensure it's correct
        JsonObject MakeMatcherHook(string matcher, string cmd) => new JsonObject
        {
            ["matcher"] = matcher,
            ["hooks"]   = new JsonArray { new JsonObject { ["type"] = "command", ["command"] = cmd } }
        };

        settings["hooks"] = new JsonObject
        {
            ["SessionStart"] = new JsonArray
            {
                new JsonObject
                {
                    ["hooks"] = new JsonArray { new JsonObject { ["type"] = "command", ["command"] = sessionStartCmd } }
                }
            },
            ["PreToolUse"] = new JsonArray
            {
                MakeMatcherHook("Write", hookCmd),
                MakeMatcherHook("Edit", hookCmd),
                MakeMatcherHook("Read", hookCmd),
                MakeMatcherHook("Grep", hookCmd),
                MakeMatcherHook("Glob", hookCmd),
                MakeMatcherHook("NotebookEdit", hookCmd),
                MakeMatcherHook("CronCreate", hookCmd),
                MakeMatcherHook("CronDelete", hookCmd),
                MakeMatcherHook("WebFetch", hookCmd),
                MakeMatcherHook("WebSearch", hookCmd),
                MakeMatcherHook("Bash", hookCmd),
                MakeMatcherHook("PowerShell", hookCmd),
                MakeMatcherHook("AskUserQuestion", askQCmd),
                MakeMatcherHook("ExitPlanMode", exitPlanCmd)
            },
            ["PostToolUse"] = new JsonArray
            {
                new JsonObject
                {
                    ["hooks"] = new JsonArray { new JsonObject { ["type"] = "command", ["command"] = toolDoneCmd } }
                }
            },
            ["Stop"] = new JsonArray
            {
                new JsonObject
                {
                    ["hooks"] = new JsonArray { new JsonObject { ["type"] = "command", ["command"] = doneCmd } }
                }
            }
        };

        var json = settings.ToJsonString(new JsonSerializerOptions { WriteIndented = true });
        Console.WriteLine($"[API] Writing {json.Length} chars to {settingsPath}");
        File.WriteAllText(settingsPath, json);
        Console.WriteLine("[API] Claude Code hooks restored in settings.json");
    }
    catch (Exception ex)
    {
        Console.WriteLine($"[API] Warning: could not restore Claude Code hooks: {ex.Message}");
    }
}

static void RemoveClaudeCodeHooks()
{
    try
    {
        var settingsPath = FindClaudeSettingsPath();
        if (settingsPath == null || !File.Exists(settingsPath)) return;

        var aethelAllow = new[] { "PowerShell(*)", "Write(*)", "Edit(*)", "Read(*)", "Bash(*)", "Grep(*)", "Glob(*)" };
        var settings = JsonNode.Parse(File.ReadAllText(settingsPath)) as JsonObject;
        if (settings == null) return;

        settings.Remove("hooks");

        if (settings["permissions"] is JsonObject perms && perms["allow"] is JsonArray allow)
        {
            var kept = allow.Select(x => x?.GetValue<string>() ?? "")
                            .Where(s => !aethelAllow.Contains(s))
                            .ToList();
            if (kept.Count == 0)
                settings.Remove("permissions");
            else
            {
                var newAllow = new JsonArray();
                foreach (var s in kept) newAllow.Add(JsonValue.Create(s));
                perms["allow"] = newAllow;
            }
        }

        File.WriteAllText(settingsPath,
            settings.ToJsonString(new JsonSerializerOptions { WriteIndented = true }));
        Console.WriteLine("[API] Claude Code hooks removed from settings.json - native dialogs active");
    }
    catch (Exception ex)
    {
        Console.WriteLine($"[API] Warning: could not remove Claude Code hooks: {ex.Message}");
    }
}

static string? FindCodexHooksPath()
{
    var usersRoot = @"C:\Users";
    if (Directory.Exists(usersRoot))
    {
        foreach (var dir in Directory.GetDirectories(usersRoot))
        {
            var codexDir = Path.Combine(dir, ".codex");
            if (Directory.Exists(codexDir))
                return Path.Combine(codexDir, "hooks.json");
        }
    }

    var direct = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
        ".codex", "hooks.json");
    return direct;
}

static void RestoreCodexHooks()
{
    try
    {
        var hooksPath = FindCodexHooksPath();
        if (hooksPath == null)
        {
            Console.WriteLine("[Codex] Warning: could not locate .codex directory - hooks not restored");
            return;
        }

        Directory.CreateDirectory(Path.GetDirectoryName(hooksPath)!);

        // Codex-specific subfolder - its on_approval_request.ps1/on_agent_done.ps1 are
        // distinct scripts from Claude Code's same-named files, so they can't share the
        // flat hooks\ folder without colliding.
        const string approvalCmd = @"powershell.exe -ExecutionPolicy Bypass -File C:\ProgramData\AethelHook\hooks\codex\on_approval_request.ps1";
        const string doneCmd     = @"powershell.exe -ExecutionPolicy Bypass -File C:\ProgramData\AethelHook\hooks\codex\on_agent_done.ps1";

        var hooks = new JsonObject
        {
            ["hooks"] = new JsonObject
            {
                ["PreToolUse"] = new JsonArray
                {
                    new JsonObject
                    {
                        ["matcher"] = "Bash",
                        ["hooks"]   = new JsonArray { new JsonObject { ["type"] = "command", ["command"] = approvalCmd, ["timeout"] = 90 } }
                    },
                    new JsonObject
                    {
                        ["matcher"] = "apply_patch",
                        ["hooks"]   = new JsonArray { new JsonObject { ["type"] = "command", ["command"] = approvalCmd, ["timeout"] = 90 } }
                    }
                },
                ["Stop"] = new JsonArray
                {
                    new JsonObject
                    {
                        ["hooks"] = new JsonArray { new JsonObject { ["type"] = "command", ["command"] = doneCmd, ["timeout"] = 30 } }
                    }
                }
            }
        };

        File.WriteAllText(hooksPath, hooks.ToJsonString(new JsonSerializerOptions { WriteIndented = true }));
        Console.WriteLine($"[Codex] hooks.json written to {hooksPath}");
    }
    catch (Exception ex)
    {
        Console.WriteLine($"[Codex] Warning: could not restore hooks: {ex.Message}");
    }
}

static void RemoveCodexHooks()
{
    try
    {
        var hooksPath = FindCodexHooksPath();
        if (hooksPath == null || !File.Exists(hooksPath)) return;
        File.Delete(hooksPath);
        Console.WriteLine("[Codex] hooks.json removed - native Codex approvals active");
    }
    catch (Exception ex)
    {
        Console.WriteLine($"[Codex] Warning: could not remove hooks: {ex.Message}");
    }
}

// Antigravity (Gemini) reads its GLOBAL hooks config from
// C:\Users\<user>\.gemini\config\hooks.json - see ANTIGRAVITY_HOOKS.md. Distinct from
// this repo's own dev-only project-level .agents\hooks.json, which stays untouched.
static string? FindAntigravityHooksPath()
{
    var usersRoot = @"C:\Users";
    if (Directory.Exists(usersRoot))
    {
        foreach (var dir in Directory.GetDirectories(usersRoot))
        {
            var geminiDir = Path.Combine(dir, ".gemini");
            if (Directory.Exists(geminiDir))
                return Path.Combine(geminiDir, "config", "hooks.json");
        }
    }

    var direct = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
        ".gemini", "config", "hooks.json");
    return direct;
}

static void RestoreAntigravityHooks()
{
    try
    {
        var hooksPath = FindAntigravityHooksPath();
        if (hooksPath == null)
        {
            Console.WriteLine("[Antigravity] Warning: could not locate .gemini directory - hooks not restored");
            return;
        }

        Directory.CreateDirectory(Path.GetDirectoryName(hooksPath)!);

        const string approvalCmd = @"powershell.exe -ExecutionPolicy Bypass -File C:\ProgramData\AethelHook\hooks\gemini\on_approval_request.ps1";
        const string doneCmd     = @"powershell.exe -ExecutionPolicy Bypass -File C:\ProgramData\AethelHook\hooks\gemini\on_task_complete.ps1";

        JsonObject MakeMatcherHook(string matcher, string cmd, int timeout) => new JsonObject
        {
            ["matcher"] = matcher,
            ["hooks"]   = new JsonArray { new JsonObject { ["type"] = "command", ["command"] = cmd, ["timeout"] = timeout } }
        };
        JsonArray SingleHook(string cmd, int timeout) => new JsonArray
        {
            new JsonObject { ["hooks"] = new JsonArray { new JsonObject { ["type"] = "command", ["command"] = cmd, ["timeout"] = timeout } } }
        };

        var hooks = new JsonObject
        {
            ["hooks"] = new JsonObject
            {
                ["PreToolUse"] = new JsonArray
                {
                    MakeMatcherHook("run_command", approvalCmd, 90),
                    MakeMatcherHook("write_file", approvalCmd, 90),
                    MakeMatcherHook("replace_file_content", approvalCmd, 90),
                    MakeMatcherHook("multi_replace_file_content", approvalCmd, 90),
                    MakeMatcherHook("write_to_file", approvalCmd, 90)
                },
                ["SessionEnd"] = SingleHook(doneCmd, 5),
                ["AfterAgent"] = SingleHook(doneCmd, 5),
                ["Stop"]       = SingleHook(doneCmd, 5)
            }
        };

        File.WriteAllText(hooksPath, hooks.ToJsonString(new JsonSerializerOptions { WriteIndented = true }));
        Console.WriteLine($"[Antigravity] hooks.json written to {hooksPath}");
    }
    catch (Exception ex)
    {
        Console.WriteLine($"[Antigravity] Warning: could not restore hooks: {ex.Message}");
    }
}

static void RemoveAntigravityHooks()
{
    try
    {
        var hooksPath = FindAntigravityHooksPath();
        if (hooksPath == null || !File.Exists(hooksPath)) return;
        File.Delete(hooksPath);
        Console.WriteLine("[Antigravity] hooks.json removed - native Antigravity approvals active");
    }
    catch (Exception ex)
    {
        Console.WriteLine($"[Antigravity] Warning: could not remove hooks: {ex.Message}");
    }
}

static string NormalizeDecision(string raw) => raw.ToLower() switch
{
    "approve"              => "allow_once",
    "allow"                => "allow_once",
    "allow_once"           => "allow_once",
    "always_allow_project" => "always_allow_project",
    "always_allow_global"  => "always_allow_global",
    "deny_with_reason"     => "deny_with_reason",
    "decline"              => "deny",
    "deny"                 => "deny",
    _                      => "ask"
};

static string Truncate(string? s, int maxLen) =>
    string.IsNullOrEmpty(s) || s.Length <= maxLen ? (s ?? "") : s.Substring(0, maxLen) + "…";

// ─────────────────────────────────────────────────────────────────────────────
// Static stores
// ─────────────────────────────────────────────────────────────────────────────

// Wraps a WebSocket with a send semaphore and per-send timeout so that:
//   1. Concurrent sends from the receive loop and BroadcastAsync don't race.
//   2. A dead/half-open TCP connection doesn't hang BroadcastAsync for minutes.
public class WsConnection(WebSocket ws, string token)
{
    private readonly SemaphoreSlim _lock = new(1, 1);

    // The token this connection authenticated with at upgrade time - the only point
    // it's ever checked, since a WS upgrade can't be re-validated mid-session. Kept
    // here so a revoke can find and close this specific connection.
    public string Token => token;

    public WebSocketState State => ws.State;

    // Hard-aborts the underlying socket immediately (vs. a graceful close) so an
    // evicted connection can never linger as "Open" and receive a stray broadcast.
    public void Abort()
    {
        try { ws.Abort(); } catch { }
    }

    // Returns false if socket is not open, timed out, or threw.
    public async Task<bool> TrySendAsync(string json)
    {
        if (ws.State != WebSocketState.Open) return false;
        if (!await _lock.WaitAsync(TimeSpan.FromSeconds(5))) return false;
        try
        {
            var bytes = Encoding.UTF8.GetBytes(json);
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(5));
            await ws.SendAsync(bytes, WebSocketMessageType.Text, true, cts.Token);
            return true;
        }
        catch { return false; }
        finally { _lock.Release(); }
    }
}

public static class WsClientStore
{
    private static readonly object _gate = new();
    private static readonly ConcurrentDictionary<Guid, WsConnection> _clients = new();

    public static bool HasClients => !_clients.IsEmpty;

    public static WsConnection Register(WebSocket ws, string token)
    {
        // Single-user: evict stale connections so BroadcastAsync never sends doubles.
        // Evict-then-add must be atomic - two /ws upgrades racing (e.g. a phone reconnect
        // storm triggered by Tailscale's tun interface flapping) could otherwise both slip
        // past the eviction and stay registered, so every event gets broadcast twice.
        var conn = new WsConnection(ws, token);
        lock (_gate)
        {
            foreach (var kv in _clients.ToList())
            {
                kv.Value.Abort();
                _clients.TryRemove(kv.Key, out _);
            }
            _clients[Guid.NewGuid()] = conn;
        }
        return conn;
    }

    public static void Unregister(WsConnection conn)
    {
        foreach (var kv in _clients)
            if (ReferenceEquals(kv.Value, conn))
                _clients.TryRemove(kv.Key, out _);
    }

    // Force-closes any live connection authenticated with this token. The token is
    // otherwise only checked once, at the WS upgrade (see /ws) - without this, a
    // revoked device's already-open socket would keep working until it disconnects
    // on its own.
    public static void DisconnectByToken(string token)
    {
        foreach (var kv in _clients.ToList())
        {
            if (CryptoUtil.ConstantTimeEquals(kv.Value.Token, token))
            {
                kv.Value.Abort();
                _clients.TryRemove(kv.Key, out _);
            }
        }
    }

    // Returns true if at least one client received the message.
    public static async Task<bool> BroadcastAsync(string json)
    {
        var dead = new List<Guid>();
        bool sent = false;
        foreach (var kv in _clients)
        {
            if (await kv.Value.TrySendAsync(json))
                sent = true;
            else
                dead.Add(kv.Key);
        }
        foreach (var id in dead) _clients.TryRemove(id, out _);
        return sent;
    }
}

public static class DecisionStore
{
    public static readonly ConcurrentDictionary<string, TaskCompletionSource<string>> PendingDecisions = new();
    public static readonly ConcurrentDictionary<string, string> RecentDecisions = new();
    // Full event JSON per pending session - re-pushed to phones that connect late
    // Stores payload + the time it was queued; re-pushed on reconnect only if recent (< 90s)
    public static readonly ConcurrentDictionary<string, (string Payload, DateTime StoredAt)> PendingEventPayloads = new();

    // Codex parallel-tool-call deduplication:
    // CodexTurnPrimary maps "(turnId|cmdName)" → primary sessionId (first to register wins)
    // LinkedSessions maps primarySessionId → bag of shadow sessionIds that share its decision
    public static readonly ConcurrentDictionary<string, string> CodexTurnPrimary = new();
    public static readonly ConcurrentDictionary<string, ConcurrentBag<string>> LinkedSessions = new();

    // Propagate a resolved decision to all shadow sessions linked to a primary.
    public static void PropagateToShadows(string primaryId, string resultJson)
    {
        if (!LinkedSessions.TryRemove(primaryId, out var shadows)) return;
        foreach (var shadowId in shadows)
        {
            if (PendingDecisions.TryGetValue(shadowId, out var stcs))
                stcs.TrySetResult(resultJson);
            else
                RecentDecisions[shadowId] = resultJson;
        }
    }
}


public record DeviceRecord(Guid Id, string Token, string Label, DateTime PairedAt);

public static class CryptoUtil
{
    // Avoids a timing side-channel when comparing device tokens / pairing psks - plain
    // string equality (`==`) short-circuits on the first mismatched byte, so response
    // time can leak how many leading characters an attacker's guess got right.
    public static bool ConstantTimeEquals(string a, string b) =>
        CryptographicOperations.FixedTimeEquals(Encoding.UTF8.GetBytes(a), Encoding.UTF8.GetBytes(b));

    // Strips inherited permissions from a sensitive file (device tokens, TLS private key,
    // session state) and grants access only to Administrators + SYSTEM. Without this, these
    // files sit directly under C:\ProgramData\AethelHook and inherit whatever ACL the
    // installer/OS placed on that folder - on a multi-user PC that previously meant every
    // other local Windows account (no admin rights needed) could read every paired device's
    // bearer token or the TLS private key straight off disk.
#pragma warning disable CA1416 // this app only ever runs on Windows (LocalSystem service)
    public static void RestrictToAdminSystem(string path)
    {
        try
        {
            var security = new FileSecurity();
            security.SetAccessRuleProtection(isProtected: true, preserveInheritance: false);
            security.AddAccessRule(new FileSystemAccessRule(
                new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null),
                FileSystemRights.FullControl, AccessControlType.Allow));
            security.AddAccessRule(new FileSystemAccessRule(
                new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null),
                FileSystemRights.FullControl, AccessControlType.Allow));
            new FileInfo(path).SetAccessControl(security);
        }
        catch (Exception ex)
        {
            Console.WriteLine($"[Security] Failed to restrict permissions on {path}: {ex.Message}");
        }
    }
#pragma warning restore CA1416
}

// Per-device API tokens, replacing the old single-shared-token model. Device count is
// always tiny for personal use, so a plain ConcurrentDictionary + linear-scan lookup
// is simpler than maintaining a second token->id index.
public static class DeviceRegistry
{
    private static readonly ConcurrentDictionary<Guid, DeviceRecord> _devices = new();
    private static readonly object _fileLock = new();
    private static string DevicesPath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "AethelHook", "devices.json");

    // Loads devices.json, or seeds it with the legacy shared token (via the given
    // fallback loader) the very first time this runs so an already-paired phone
    // doesn't need to re-pair.
    public static void Initialize(Func<string> loadLegacyToken)
    {
        if (File.Exists(DevicesPath))
        {
            try
            {
                var json = File.ReadAllText(DevicesPath);
                var list = JsonSerializer.Deserialize<List<DeviceRecord>>(json) ?? new();
                foreach (var d in list) _devices[d.Id] = d;
                return;
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[DeviceRegistry] Failed to load devices.json: {ex.Message}");
            }
        }

        var legacyToken = loadLegacyToken();
        var legacyId = Guid.NewGuid();
        _devices[legacyId] = new DeviceRecord(legacyId, legacyToken, "legacy", DateTime.UtcNow);
        Save();
    }

    public static bool IsValidToken(string? token)
    {
        if (string.IsNullOrEmpty(token)) return false;
        foreach (var d in _devices.Values)
            if (CryptoUtil.ConstantTimeEquals(d.Token, token)) return true;
        return false;
    }

    public static (Guid id, string token) Register(string label)
    {
        var bytes = new byte[32];
        RandomNumberGenerator.Fill(bytes);
        var token = Convert.ToHexString(bytes).ToLower();
        var id = Guid.NewGuid();
        _devices[id] = new DeviceRecord(id, token, label, DateTime.UtcNow);
        Save();
        return (id, token);
    }

    public static string? GetToken(Guid id) => _devices.TryGetValue(id, out var d) ? d.Token : null;

    public static bool Revoke(Guid id)
    {
        var removed = _devices.TryRemove(id, out _);
        if (removed) Save();
        return removed;
    }

    public static List<DeviceRecord> List() => _devices.Values.ToList();

    private static void Save()
    {
        lock (_fileLock)
        {
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(DevicesPath)!);
                var json = JsonSerializer.Serialize(_devices.Values.ToList(), new JsonSerializerOptions { WriteIndented = true });
                File.WriteAllText(DevicesPath, json);
                CryptoUtil.RestrictToAdminSystem(DevicesPath);
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[DeviceRegistry] Failed to save devices.json: {ex.Message}");
            }
        }
    }
}

public class FeedEntry
{
    public string SessionId { get; init; } = "";
    public string ToolName { get; init; } = "";
    public string CommandName { get; init; } = "";
    public string Detail { get; init; } = "";
    public DateTime CreatedAt { get; init; }
    public string? Decision { get; set; }
    public DateTime? DecidedAt { get; set; }
}

// Bounded in-memory history of approval requests, for the tray app's live feed.
// Deliberately polling-based (GET /tray/feed) rather than pushed over /ws:
// WsClientStore evicts any existing connection the moment a new one registers
// (single-client by design - see its own comment), so a second listener there
// would fight the phone's connection instead of coexisting with it.
public static class TrayFeedStore
{
    private const int MaxEntries = 50;
    private static readonly object _gate = new();
    private static readonly LinkedList<FeedEntry> _entries = new();

    public static void Add(string sessionId, string toolName, string commandName, string detail)
    {
        lock (_gate)
        {
            _entries.AddFirst(new FeedEntry
            {
                SessionId = sessionId,
                ToolName = toolName,
                CommandName = commandName,
                Detail = detail,
                CreatedAt = DateTime.UtcNow
            });
            while (_entries.Count > MaxEntries) _entries.RemoveLast();
        }
    }

    public static void Resolve(string sessionId, string decision)
    {
        lock (_gate)
        {
            var entry = _entries.FirstOrDefault(e => e.SessionId == sessionId);
            if (entry == null) return;
            entry.Decision = decision;
            entry.DecidedAt = DateTime.UtcNow;
        }
    }

    public static List<FeedEntry> Recent(int limit)
    {
        lock (_gate)
        {
            return _entries.Take(Math.Clamp(limit, 1, MaxEntries)).ToList();
        }
    }
}

public class PairingSession
{
    public required string Psk { get; init; }
    public required DateTime ExpiresAt { get; init; }
    public bool Claimed { get; set; }
}

// Short-lived, single-use QR pairing sessions. A session's sid+psk are only ever
// displayed in the QR rendered by the loopback-only /pair page.
public static class PairingStore
{
    private const int TtlSeconds = 120;
    private static readonly ConcurrentDictionary<string, PairingSession> _sessions = new();

    public static (string sid, int expiresInSec) CreateSession()
    {
        // Opportunistic cleanup - session count is always tiny, no background timer needed.
        foreach (var kv in _sessions)
            if (kv.Value.ExpiresAt < DateTime.UtcNow) _sessions.TryRemove(kv.Key, out _);

        var sid = RandomHex(16);
        var psk = RandomHex(16);
        var expiresAt = DateTime.UtcNow.AddSeconds(TtlSeconds);
        _sessions[sid] = new PairingSession { Psk = psk, ExpiresAt = expiresAt };
        return (sid, TtlSeconds);
    }

    public static bool TryGetPayload(string sid, string ip, int port, string certFingerprint, out string payloadJson)
    {
        payloadJson = "";
        if (!_sessions.TryGetValue(sid, out var session) || session.ExpiresAt < DateTime.UtcNow) return false;
        // v=2 adds `c` (the HTTPS cert's SHA-256 fingerprint) so the phone can pin it -
        // the QR scan is this system's actual root of trust, not a certificate authority.
        payloadJson = JsonSerializer.Serialize(new { v = 2, i = ip, p = port, s = sid, k = session.Psk, c = certFingerprint });
        return true;
    }

    public static string GetStatus(string sid)
    {
        if (!_sessions.TryGetValue(sid, out var session)) return "not_found";
        if (session.Claimed) return "claimed";
        if (session.ExpiresAt < DateTime.UtcNow) return "expired";
        return "pending";
    }

    public static (bool ok, string? deviceId, string? token, string? error) TryClaim(string sid, string psk)
    {
        if (!_sessions.TryGetValue(sid, out var session)) return (false, null, null, "not_found");
        if (session.Claimed) return (false, null, null, "already_claimed");
        if (session.ExpiresAt < DateTime.UtcNow) return (false, null, null, "expired");
        if (!CryptoUtil.ConstantTimeEquals(session.Psk, psk)) return (false, null, null, "invalid");

        session.Claimed = true;
        var (id, token) = DeviceRegistry.Register("phone");
        return (true, id.ToString(), token, null);
    }

    private static string RandomHex(int byteLength)
    {
        var bytes = new byte[byteLength];
        RandomNumberGenerator.Fill(bytes);
        return Convert.ToHexString(bytes).ToLower();
    }
}

// Mirrors DecisionStore's TCS-wait pattern for AskUserQuestion answers. No Codex
// shadow/dedup logic needed here - AskUserQuestion isn't called in parallel duplicate ways.
public static class QuestionStore
{
    public static readonly ConcurrentDictionary<string, TaskCompletionSource<string>> PendingAnswers = new();
    public static readonly ConcurrentDictionary<string, string> RecentAnswers = new();
    public static readonly ConcurrentDictionary<string, (string Payload, DateTime StoredAt)> PendingQuestionPayloads = new();
}

// Mirrors QuestionStore's TCS-wait pattern for the ExitPlanMode ("Accept this plan?")
// dialog. PendingPlans holds the raw plan markdown so PlanReviewActivity can fetch it
// via GET /hook/plan/{sessionId} instead of stuffing long plan text into a notification.
public static class PlanReviewStore
{
    public static readonly ConcurrentDictionary<string, TaskCompletionSource<string>> PendingDecisions = new();
    public static readonly ConcurrentDictionary<string, string> RecentDecisions = new();
    public static readonly ConcurrentDictionary<string, (string Plan, DateTime StoredAt)> PendingPlans = new();
}

// Writes every Console.WriteLine to both stdout and a log file simultaneously.
public class TeeWriter(TextWriter console, TextWriter file) : TextWriter
{
    public override Encoding Encoding => console.Encoding;
    public override void WriteLine(string? value) { console.WriteLine(value); file.WriteLine(value); }
    public override void Write(char value)         { console.Write(value);     file.Write(value); }
    public override void Flush()                   { console.Flush();          file.Flush(); }
    protected override void Dispose(bool d)        { if (d) { console.Dispose(); file.Dispose(); } base.Dispose(d); }
}

// Request payloads
public record PairClaimRequest(
    [property: JsonPropertyName("sid")] string Sid,
    [property: JsonPropertyName("psk")] string Psk
);

public record EventRequest(
    [property: JsonPropertyName("event_type")]    string EventType,
    [property: JsonPropertyName("message")]       string Message,
    [property: JsonPropertyName("detail")]        string Detail,
    [property: JsonPropertyName("session_id")]    string SessionId,
    [property: JsonPropertyName("timestamp")]     string Timestamp,
    [property: JsonPropertyName("command_name")]  string? CommandName,
    [property: JsonPropertyName("tool_name")]     string? ToolName,
    [property: JsonPropertyName("codex_turn_id")] string? CodexTurnId
);

public record RespondRequest(
    [property: JsonPropertyName("session_id")] string SessionId,
    [property: JsonPropertyName("decision")]   string Decision,
    [property: JsonPropertyName("reason")]     string? Reason
);

public record NotifyRequest(
    [property: JsonPropertyName("message")] string? Message,
    [property: JsonPropertyName("detail")]  string? Detail,
    [property: JsonPropertyName("cwd")]     string? Cwd
);

// Questions/Answers are kept as raw JsonElement - the API is a dumb relay that never
// needs to understand AskUserQuestion's schema, just pass it through unmodified.
public record AskQuestionRequest(
    [property: JsonPropertyName("session_id")] string SessionId,
    [property: JsonPropertyName("questions")]  JsonElement Questions
);
public record AnswerQuestionRequest(
    [property: JsonPropertyName("session_id")] string SessionId,
    [property: JsonPropertyName("answers")]    JsonElement Answers
);

public record PlanRequest(
    [property: JsonPropertyName("session_id")] string SessionId,
    [property: JsonPropertyName("plan")]       string Plan
);

public record PlanDecisionRequest(
    [property: JsonPropertyName("session_id")] string SessionId,
    [property: JsonPropertyName("decision")]   string Decision,
    [property: JsonPropertyName("feedback")]   string? Feedback
);

public record SessionUpdateRequest(
    [property: JsonPropertyName("message")]   string? Message,
    [property: JsonPropertyName("detail")]    string? Detail,
    [property: JsonPropertyName("tool_name")] string? ToolName,
    [property: JsonPropertyName("cwd")]       string? Cwd
);

public record SendPromptRequest(
    [property: JsonPropertyName("prompt")] string Prompt,
    [property: JsonPropertyName("project_dir")] string? ProjectDir,
    [property: JsonPropertyName("agent")] string? Agent
);
