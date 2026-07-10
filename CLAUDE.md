# AethelHook - Working Notes for Claude Code

AI agent permission gateway: routes dangerous tool calls (and now phone-initiated
prompts) between Claude Code / Codex / Antigravity and an Android phone. See
`README.md` for the public-facing product description; this file is the current,
continuously-maintained technical reference - keep it up to date as things change,
don't let it go stale like the archived docs in `docs/archive/` (including the old
`PROJECT.md`, retired 2026-07-10 once its FCM/Firebase-era content contradicted the
security work since done - see README.md instead).

## Architecture

- **`AethelHook.API/`** - .NET 9 ASP.NET Core, port 5264, runs as a **Windows Service
  under LocalSystem**. Routes hook events to the phone via WebSocket (LAN or Tailscale)
  - the only transport now; FCM was removed entirely (2026-07-09, see gotcha #17 and
  Current status below). See "Critical gotchas" below - running as LocalSystem causes
  real, non-obvious bugs.
- **`AethelHook.Tray/`** - WPF tray app, runs as the interactive user (not a service).
  This is the official PC-side UI: status, gateway toggle, device pairing, live feed.
  Anything needing to interact with the desktop must go through here, not the API
  service (see Session 0 isolation below).
- **`app/`** - Android Kotlin/Compose app. 4 tabs: Dashboard, Session, History, Settings.
- **Hooks** - PowerShell scripts per IDE: `.claude/hooks/` (Claude Code), `.codex/hooks/`
  (Codex), `.gemini/hooks/` (Antigravity). Dev copies live in the repo; the API's
  `Restore*Hooks()` functions (in `Program.cs`) rewrite each IDE's global hook config
  on every service start, pointing at `C:\ProgramData\AethelHook\hooks\` - **not** the
  repo path. Keep dev (`.claude\hooks\`), installer staging (`dist\hooks\`), and the
  live deployed copy (`C:\ProgramData\AethelHook\hooks\`) in sync manually after any edit.

## Critical gotchas (read before touching PC-side automation)

1. **The service runs as LocalSystem - it cannot interact with the interactive desktop
   at all** (Windows Session 0 isolation, not a bug to work around). Any feature needing
   to see/focus a window must run in the Tray app instead. Confirmed the hard way: an
   early Session Access design had the service directly inject keystrokes into VS Code;
   it silently no-op'd because a service-spawned process is *itself* in Session 0.
2. **A process the service spawns also inherits LocalSystem's profile, not the real
   user's** - different from #1, and easy to conflate. `claude -p` spawned directly by
   the service can't find the user's `.claude\` auth/config and fails immediately. Fix:
   explicitly set `USERPROFILE`/`HOME`/`APPDATA`/`LOCALAPPDATA` env vars on the child
   process to the real user's profile (found via the same "scan `C:\Users\*`" pattern
   used by `FindClaudeSettingsPath()`/`FindClaudeCliInfo()`).
3. **`dotnet build` output ≠ what the live service runs.** The service runs
   `C:\Program Files\AethelHook\AethelHook.API.exe` (a `dotnet publish` output),
   completely separate from `AethelHook.API\bin\Debug\...`. Only `install.ps1`
   (`#Requires -RunAsAdministrator`) actually redeploys the live service - building
   alone proves nothing about what's running. Same applies to the Tray app.
   `install.ps1` needs to be run from an elevated PowerShell window by the user; this
   session's tools can't do it directly.
4. **Claude Code's `PreToolUse`/`PostToolUse`/`SessionStart` hooks are global
   (`.claude\settings.json`) and apply to headless `claude -p` runs too - but `Stop`
   does NOT fire in headless mode.** Detect headless completion from the `"type":
   "result"` line in `--output-format stream-json` output, not by relying on a Stop hook.
5. **PowerShell hook scripts must read stdin via an explicit UTF-8 `StreamReader`**
   (`[System.IO.StreamReader]::new([Console]::OpenStandardInput(), [System.Text.Encoding]::UTF8)`),
   never bare `[Console]::In` - the latter decodes with the console's default encoding,
   corrupting any em dash/curly quote/non-ASCII text (confirmed live, was silently
   broken for a while).
6. **Any per-call session identifier passed to a PowerShell hook must be a fresh
   `[System.Guid]::NewGuid()`, never Claude Code's conversation-level `session_id`** -
   that ID is identical across every call in one conversation, so reusing it lets a
   late-arriving phone answer contaminate the next unrelated question/plan call.
7. **JAVA_HOME must be set for Gradle** (not on PATH by default in this environment):
   `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"` (Git Bash) before
   any `./gradlew.bat` command.
8. **`claude -p --resume <id>` fails immediately ("No conversation found") if the
   working directory at resume time doesn't exactly match the directory the session
   was originally created in** - confirmed live. `LastKnownCwd` (used for brand-new
   phone conversations) drifts on every tool call anywhere, including unrelated work
   in a different directory, so a resumable session's cwd must be pinned as its own
   dictionary key (`ProjectSessions: cwd -> session_id`, one entry per project
   directory) and always reused verbatim for that project's resume calls, never the
   live-drifting `LastKnownCwd`.
9. **A literal non-ASCII character (em dash, ellipsis, curly quote, etc.) embedded
   directly in a `.ps1` file's own source code gets corrupted** - PowerShell 5.1 reads
   a BOM-less script file using the system's active code page, not UTF-8, so the
   literal character is already wrong before any of the script's own UTF-8-encoding-
   for-transmission logic runs. Confirmed live: `"…"` in source became `"â€¦"` on the
   phone. Fix: build such characters numerically, e.g. `[char]0x2026`, never paste
   the literal glyph into a hook script.
10. **`~/.claude/` (settings, credentials) is shared globally across every Claude Code
    host** - confirmed live: Cursor has its own separate extension install
    (`.cursor\extensions\anthropic.claude-code-*`, distinct from `.vscode\extensions\`),
    but both point at the same `.claude` config/auth, so hooks fire identically
    regardless of which editor hosts the session. The practical catch: `LastKnownCwd`
    (which project a *brand-new* phone message targets) is one global tracker shared
    across every open Claude Code window in every editor - whichever you touched most
    recently wins. An *ongoing* phone conversation is safe from this (pinned via
    `LastPhoneSessionCwd`, gotcha #8), but starting a fresh phone message while your
    most recent activity was in a different project/editor will target that project,
    not the one you meant. (Mitigated by the project picker - see Current status.)
11. **`ConcurrentDictionary<string,...>` keyed by a Windows path must use
    `StringComparer.OrdinalIgnoreCase`** - different tools report `cwd` with different
    drive-letter casing (Cursor sends `c:\ERP`, Claude Code's own hook sends `C:\ERP`),
    and the default ordinal comparer treats those as two different keys. Confirmed
    live: `ProjectSessions`/`KnownProjects` without this showed the same folder twice
    in the phone's project picker and could split one directory's resumable session
    across two unrelated entries.
12. **A Compose composable that's one branch of a manual `when`-based tab switcher
    (not a NavHost) is fully disposed when you navigate away from it** - any state
    declared with plain `remember { }` inside it (e.g. a chat message list) resets to
    its initial value every time you switch back, because it's a brand-new composable
    call, not a recomposition of the same instance. Confirmed live: Session tab's chat
    history vanished on every switch to another tab and back. Fix: hoist state that
    must survive tab switches into a top-level singleton object (same pattern as
    `AethelHookWebSocket`'s own `MutableStateFlow`), not a local `remember`. Relatedly,
    a `StateFlow` feeding such a composable via `collectAsState()` retains its last
    value forever - if the consuming effect doesn't reset it after consuming (e.g.
    `.value = null`), re-entering the screen replays the same stale event again.
13. **`send_answer_key.ps1`/`send_plan_key.ps1`'s window-matching only ever worked for
    the AethelHook project itself.** Both scripts locate the target window by
    `EnumWindows` + title `-like` match, but hardcoded the literal string `"AethelHook"`
    - and `on_ask_question.ps1`/`on_exit_plan.ps1` never passed any workspace/project
    parameter through. So for any *other* project (which is most real usage), the
    window title never contains "AethelHook" and the script always hit "No AethelHook
    window found - aborting" - the phone would show and answer the question/plan
    correctly, but the CLI session would never pick it up. This is the "AskUserQuestion
    updatedInput/ExitPlanMode permissionDecision no longer honored" gap these two
    scripts exist to paper over (see their own header comments) - so on any project
    other than AethelHook, that gap was never actually closed. Confirmed live in the
    log: `[SendAnswerKey]`/`[SendPlanKey] No AethelHook window found` while working in
    `C:\ERP` via Cursor. Fix (2026-07-07): both hook scripts now derive the real
    workspace name from the hook's own `cwd` (`Split-Path $inputData.cwd -Leaf`) and
    pass it as `-WorkspaceName` to the send-key scripts, which match against that
    instead of the hardcoded literal (default still `"AethelHook"` if `cwd` is somehow
    missing, for backward compatibility).
14. **`Out-File -Encoding utf8` in Windows PowerShell 5.1 (not PowerShell 7) silently
    prepends a UTF-8 BOM** - and Codex's `hooks.json` parser does not skip it, so a
    file written this way fails to load with `failed to parse hooks config ...:
    expected value at line 1 column 1` (the classic BOM-at-byte-0 JSON error). Confirmed
    live: `dist\install_hooks.ps1` wrote `hooks.json`/`settings.json` this way; on a
    fresh install the installer's `[Run]` order starts the service *before* running
    `install_hooks.ps1` (see `AethelHook.iss`), so this BOM'd write is the last one -
    it only self-heals once the service happens to restart again, since `Program.cs`'s
    `RestoreCodexHooks()` writes via .NET's `File.WriteAllText()`, which is BOM-less by
    default. Immediate fix on an affected PC: `Restart-Service AethelHook`. Root-cause
    fix (2026-07-07): changed all three `Out-File -Encoding utf8` calls in
    `dist\install_hooks.ps1` (Claude settings.json, Codex hooks.json, Gemini hooks.json)
    to `-Encoding utf8NoBOM` - fixed and baked into a rebuilt `AethelHook-Setup.exe`
    as of 2026-07-08.
15. **A hook script's own internal wait budgets can silently exceed the outer
    `timeout` declared for it in `hooks.json`.** Codex's `on_agent_done.ps1` (Stop
    hook) waited up to 3000ms for stdin plus up to 3000ms for its `/hook/notify` POST
    - a worst case of ~6s+ against a declared `"timeout": 5`. On a fast PC actual
    runtime was ~2s so it never showed up; on a genuinely slow PC (slow
    `powershell.exe` process-startup, no persistent runtime, worse under AV
    scanning/system load) the combined time exceeded even a bumped `15`, and Codex
    reported `Stop hook timed out after 5s` / killed the process before the phone
    notification ever sent - confirmed live via `hook_debug_codex.log`. Fix
    (2026-07-08), two parts, both needed for a slow machine: (1) bumped the declared
    timeout 5 → 15 → **30** in both `Program.cs`'s `RestoreCodexHooks()` and
    `dist\install_hooks.ps1`; (2) rewrote `on_agent_done.ps1` to be fire-and-forget -
    it now only reads stdin, writes the notify JSON to a temp file, and
    `Start-Process`-launches a fully detached `notify_async.ps1` (new file) to
    actually do the `Invoke-WebRequest` POST, so the Stop hook's own critical path no
    longer includes a network round-trip at all and can't be blown by a slow/loaded
    API. Payload travels via a temp file rather than an encoded command-line string
    specifically to avoid needing to shell-escape arbitrary AI-generated summary text
    (this codebase has been bitten by encoding/escaping edge cases before - see
    gotchas #5, #9, #14). Live-verified end-to-end on the originally-affected slow PC
    after reinstalling with the rebuilt installer - works perfectly.
16. **`RunHeadlessCodexPromptAsync` must not assume the user's `~/.codex/config.toml`
    already has `sandbox_mode = "danger-full-access"` / `approval_policy = "never"`
    set** - that's true on this dev machine (set manually at some point) but wasn't on
    two freshly-installed PCs, so `codex exec` tried to initialize Codex's own Windows
    sandbox helper and failed with `windows sandbox: helper_sandbox_lock_failed: lock
    sandbox bin dir <profile>\.codex\.sandbox-bin failed` - before AethelHook's own
    Codex hook (the actual phone approval gate) ever ran. Confirmed live on both
    affected PCs. Fix (2026-07-09): both branches of `RunHeadlessCodexPromptAsync` now
    always pass `-c sandbox_mode="danger-full-access" -c approval_policy="never"`
    explicitly on the `codex exec`/`codex exec resume` command line (verified valid via
    `codex.exe exec --help` / `exec resume --help` - `-c key=value` is a real override
    flag on both subcommands), instead of relying on `config.toml`. This does not weaken
    the phone approval gate - that's enforced entirely by `on_approval_request.ps1`
    (the Codex `PreToolUse` hook), not Codex's own native sandbox/approval UI, same as
    the pre-existing config.toml-based setup on this dev machine. Immediate workaround
    for an already-affected PC, if a rebuild hasn't reached it yet: kill any running
    `codex` processes, delete the stale `<profile>\.codex\.sandbox-bin` directory, and
    either wait for the fixed installer or add the two lines above to that PC's
    `config.toml` directly. Live-verified on the dev machine after `install.ps1`, and
    on both originally-affected PCs after reinstalling the rebuilt
    `AethelHook-Setup.exe` - all three now confirmed working.
17. **`AethelHook.iss` shipped a live Firebase Admin SDK service-account private key
    (`aethelhook-firebase-adminsdk-fbsvc-5091700472.json`) inside every installer
    build**, used by `Program.cs` to send FCM push notifications as a fallback when
    the WebSocket path wasn't connected. Found during a pre-distribution security
    review (2026-07-09), before this had ever been posted publicly. Since the project
    uses one shared Firebase project (no per-user setup, see
    `docs`/distribution notes), this key was identical across every install - an
    Inno Setup package is trivially unpacked without even running it, so anyone who
    downloaded the installer could extract a project-wide GCP credential, not a
    scoped token. Made worse by the fact FCM was already dead code in practice: the
    Android app never actually called `/hook/register` to send its token to the
    server, so `DeviceTokenStore.DeviceToken` was always null and the fallback send
    path never fired. Fix: removed FCM entirely rather than harden it - WebSocket
    (LAN/Tailscale) was already the only transport that worked. Stripped from both
    sides: server (`FirebaseApp`/`FirebaseMessaging` init and all `SendAsync` call
    sites, `/hook/register`, `DeviceTokenStore`, the `FirebaseAdmin` NuGet package),
    Android (`AethelHookMessagingService`, its manifest `<service>` entry, the
    `com.google.gms.google-services` plugin, `firebase-bom`/`firebase-messaging`
    deps, `google-services.json`), and the installer (`AethelHook.iss`, `install.ps1`
    no longer copy the key file at all). **The leaked key itself must still be
    rotated/revoked in the Firebase console** - it was already installed on 2+ test
    PCs before this fix, so it's treated as burned regardless of whether it was ever
    publicly downloaded.
18. **`GetLocalIpAddress()` only runs once at service startup, with no retry - and
    whatever it returns gets baked into every QR pairing code and status display for
    the rest of that process's life.** Confirmed live via `api.log`: one
    `install.ps1` redeploy's restart logged `Detected local IP: 127.0.0.1` where every
    other restart correctly logged the real LAN IP. **Not a reboot** - the log
    immediately before it shows the old process's WebSocket actively pushing events
    right up until `install.ps1`'s stop-service step (`Application is shutting
    down...`), so the network was already up; this is a transient race in the
    detection itself (UDP route-lookup trick + NIC enumeration fallback both briefly
    finding nothing) rather than specifically "network not up yet after boot" as
    first assumed. Unrelated to the FCM removal work being done that same day -
    `GetLocalIpAddress()`'s own logic wasn't touched by that change - it surfaced
    then simply because iterating on the FCM fix meant restarting the service many
    times in quick succession, and one of those restarts happened to catch the
    narrow race window; under normal, infrequent restarts the odds of hitting it are
    low, which is presumably why it hadn't shown up before. Fix (2026-07-09):
    `GetLocalIpAddress()` now retries the whole detection (UDP trick + NIC fallback,
    split out into `TryDetectLocalIp()`) once a second for up to 60 seconds (widened
    from an initial 10s) before accepting the 127.0.0.1 fallback - zero added delay
    in the normal case (detection still succeeds instantly), bounded worst-case delay
    only when detection is still failing after a full minute. Live-verified:
    `.\install.ps1` after this fix, phone scanned the QR and paired successfully.
19. **CORS (`AllowAnyOrigin().AllowAnyMethod().AllowAnyHeader()`) combined with
    treating any loopback request as "physically at the PC" let any webpage silently
    steal a full device token.** Found in a broader pre-distribution security review
    (2026-07-09, four parallel agent passes: API/auth, transport, Android app, hook
    scripts). `IsLocalRequest` (`IPAddress.IsLoopback`) is true for a browser tab
    running on the same PC, not just a human sitting at it - so with permissive CORS,
    a malicious/compromised webpage's background JS could `fetch()`
    `POST /pair/local-token` (mints a fresh token, no QR/psk needed) or
    `GET /pair/devices` (returns every paired device's plaintext token) and actually
    read the response. A stolen token lets an attacker send arbitrary headless
    prompts via `/hook/send-prompt` and self-approve them - a real path to running
    commands on the victim's PC, just from visiting a bad link, not needing LAN
    access. Fix: removed CORS entirely - neither the Android app nor the Tray app are
    browsers (not subject to CORS), and the one browser consumer (`/pair`'s own HTML
    page) only ever makes same-origin fetches, which need no CORS headers at all.
    Also hardened while auditing the same area: token/psk comparisons
    (`DeviceRegistry.IsValidToken`, `PairingStore.TryClaim`) now use
    `CryptographicOperations.FixedTimeEquals` via a shared `CryptoUtil.ConstantTimeEquals`
    instead of plain `==`/`!=` (theoretical timing side-channel, low real-world risk
    given 128+ bit secrets, but free to fix); Android no longer logs the full
    WebSocket URL to Logcat (`AethelHookWebSocket.kt` - the URL carries the token as
    a `?token=` query param, since the WS upgrade can't send custom headers);
    `backup_rules.xml`/`data_extraction_rules.xml` now exclude `aethelhook_prefs.xml`
    (holds the token + LAN/Tailscale IPs) from Android backup/cloud-backup/device-transfer;
    `on_exit_plan.ps1`'s plan-feedback temp file now uses `GetTempFileName()` instead
    of a fixed, guessable name. **Deliberately not fixed**: tightening
    `network_security_config.xml` from a blanket `cleartextTrafficPermitted` to a
    specific-host `domain-config` - Android's NSC has no CIDR/IP-range matching, only
    exact hostnames, and the PC's LAN IP is discovered dynamically at runtime (QR
    pairing), so there's no fixed host to scope it to without breaking the core
    LAN-discovery flow; left as world-scoped cleartext, mitigated by the app only ever
    calling URLs it built itself from pairing data (no third-party hosts, no WebView).
    Two related findings, discussed with the user and then implemented the same day:
    `/hook/send-prompt` now rejects any `project_dir` not already in `KnownProjects`
    (the phone's own project picker can never send anything else, so this is free -
    it only closes off a stolen token pointing the headless runner at an arbitrary
    directory); and `WsConnection` is now tagged with the token it authenticated with
    at upgrade time, so `DELETE /pair/devices/{id}` (device revocation) calls the new
    `WsClientStore.DisconnectByToken()` to force-close that device's live socket
    immediately, instead of leaving it running until it disconnects on its own (the
    token was previously only ever checked once, at the WS upgrade). Also flagged
    that day: no TLS anywhere - initially deferred as a documented tradeoff, but the
    user changed their mind the same day and asked for it to be implemented properly
    before distribution; see gotcha #20 for what actually shipped.
20. **Added real transport encryption (TLS + certificate pinning) the same day as
    gotcha #19**, after initially deferring it. Two Kestrel listeners, not one: port
    5264 (phone-facing, LAN/Tailscale/WAN) serves HTTPS with a self-signed cert
    generated on first run (`C:\ProgramData\AethelHook\aethelhook-cert.pfx`,
    `LoadOrCreateHttpsCertificate()` in `Program.cs`); a **second, loopback-only**
    port 5266 stays plain HTTP, used by all 10 PowerShell hook scripts, the Tray app,
    and the `/pair` browser page - discovered during planning that these all talk to
    `localhost:5264` directly and would have broken entirely (plus hit real Windows
    PowerShell 5.1 cert-validation limits, no `-SkipCertificateCheck`) if 5264 became
    HTTPS-only. Since ASP.NET Core's minimal-API routes are reachable on every bound
    listener regardless of scheme, this needed zero per-route restriction - 5266 is
    simply unreachable from the network. The cert's SHA-256 fingerprint
    (`cert.GetCertHash(HashAlgorithmName.SHA256)`) rides along in the QR pairing
    payload (bumped to `v=2`, new `c` field) - the QR scan is this system's actual
    root of trust, not a CA. Android pins that exact fingerprint via a custom
    `X509TrustManager` (`PinnedTls.kt`) wired into every HTTP/WS client - the shared
    `newBoundHttpClient()` factory, the WS connection itself, `Pairing.kt`'s claim
    call (pinned to the fingerprint fresh off the QR scan, not `AppPrefs`, since
    pairing hasn't completed yet), and all 4 raw `HttpURLConnection` sites
    (`ApprovalActivity`, `QuestionActivity`, `PlanReviewActivity` ×2). If no
    fingerprint is stored (a device paired before this shipped), the client
    deliberately does **not** fall back to unpinned trust - it just uses OkHttp's
    normal defaults, which safely fail closed against the self-signed cert, prompting
    a re-pair rather than silently connecting insecurely. This is why every
    previously-paired device (3 test PCs + phone) needed a fresh QR re-pair after this
    shipped - expected and confirmed working live. Also removed the `--urls
    http://0.0.0.0:5264` argument from all three service-registration sites
    (`install.ps1`, `AethelHook.iss`'s `sc.exe create`, `dist\install_service.ps1`) -
    explicit `ConfigureKestrel`/`Listen` calls silently take precedence over `--urls`
    anyway, so leaving it in would've just been a stale, misleading artifact.
    Live-verified end-to-end: fresh install, re-pair via QR, phone connected over the
    pinned HTTPS/WSS connection successfully.
21. **`WebSearch`/`WebFetch` show a native Claude Code confirmation dialog regardless
    of this hook's exit code/decision - the same "native dialog ignores the hook's
    decision" behavior as gotcha #13's `AskUserQuestion`/`ExitPlanMode`, just not
    previously discovered for these two tools.** Confirmed live via `hook_debug.log`:
    the hook fires, posts to the API, and gets a real phone decision back in under a
    second, but the dialog ("Allow searching for this query?", `1`/`2`/`3` digit
    choices + a free-text "Tell Claude what to do instead" box) still renders and
    requires manual input. That dialog's shape is identical to the `ExitPlanMode`
    dialog `send_plan_key.ps1` already drives, so `on_approval_request.ps1` (Claude
    Code edition only - Codex/Antigravity don't have a `WebSearch` equivalent) now
    launches it after a phone decision arrives for these two tool names, mapped
    `allow`/`allow_once`→`1`, `always_allow_*`→`2` (no global option in this dialog,
    project-scoped is the closest available), `deny`→`3`, `deny_with_reason`→`3` +
    the reason typed into the free-text box. Hit one real bug while wiring this up:
    always passing `-FeedbackFile` (even as an empty string) to `send_plan_key.ps1`
    via `Start-Process -ArgumentList` made the empty element vanish from the actual
    command line, shifting `-WorkspaceName` into becoming `-FeedbackFile`'s value and
    silently breaking parameter binding before the script's first line ran - fixed by
    only appending `-FeedbackFile` to the argument array when feedback is actually
    present, matching `on_exit_plan.ps1`'s existing conditional pattern. Live-verified
    2026-07-10: triggered `WebSearch` multiple times, tested both `allow` and `deny`
    (twice each) - dialog dismissed itself correctly in sync with the phone tap every
    time, and `deny` genuinely blocked the tool call ("Denied via phone").

## Key file paths

| Path | Purpose |
|---|---|
| `AethelHook.API\Program.cs` | All API logic - endpoints, hook restoration, headless prompt runner |
| `.claude\hooks\*.ps1` | Dev copies of Claude Code hooks (sync to `dist\hooks\` + `C:\ProgramData\AethelHook\hooks\` after editing) |
| `C:\ProgramData\AethelHook\hooks\` | What the live deployed service actually points `settings.json` at |
| `C:\ProgramData\AethelHook\hook_debug.log` | Hook execution log (all IDEs) |
| `C:\ProgramData\AethelHook\api.log` | API service stdout/stderr |
| `install.ps1` | Redeploys the live dev service + Tray app (elevated, run by user) |
| `AethelHook.iss` / `dist\install_hooks.ps1` | End-user installer (Inno Setup) + first-install hook bootstrap |
| `.codex\hooks\notify_async.ps1` (+ `dist\hooks\codex\`, live) | Detached process launched by `on_agent_done.ps1` to actually POST the Stop-hook notification - see gotcha #15 |
| `app\...\MainActivity.kt`, `SessionActivity.kt`, `AethelHookWebSocket.kt` | Android - nav/dashboard, Session Access tab, WS client |

## Build / deploy quick reference

```powershell
# API
cd AethelHook.API && dotnet build          # compiles only - does NOT touch the live service
.\install.ps1                              # elevated; actually redeploys service + tray

# Android (JAVA_HOME required)
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew.bat assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Friend-facing installer
dotnet publish AethelHook.API\AethelHook.API.csproj -c Release -r win-x64 --self-contained true -o dist\publish
dotnet publish AethelHook.Tray\AethelHook.Tray.csproj -c Release -r win-x64 --self-contained true -o dist\publish-tray
& "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe" AethelHook.iss
```

## Current status

*This section is the rolling "where we left off" note - update it at the end of each
significant work session, the same way you'd update any other session/handoff file.
Older entries can be trimmed once they're no longer relevant; this isn't a full
changelog (see git history / memory for that), just enough to orient the next session.*

**As of 2026-07-10 (real TLS + certificate pinning shipped, live-verified):**

- **Added genuine transport encryption after initially deciding to defer it** - the
  user changed their mind mid-session ("I do not want to ship vulnerable software")
  and asked for it done properly before distribution. Full design in gotcha #20
  above: two Kestrel listeners (5264 HTTPS phone-facing with a self-signed cert,
  5266 loopback-only plain HTTP for hook scripts/Tray app/`/pair`), fingerprint
  pinned via the existing QR pairing flow (bumped to `v=2`), Android's
  `PinnedTls.kt` wired into every HTTP/WS client site.
- **Went through full plan-mode review before implementing** given the size/risk
  (cert generation, Kestrel config, pairing wire-format change, every Android
  client). A planning pass caught two gaps the initial design completely missed:
  server-generated URLs (`respond_url`/`answer_url`/`plan_url(s)`) handed to the
  phone were still hardcoded `http://`, and - much more serious - all 10 PowerShell
  hook scripts talk to `localhost:5264` directly and would have broken the entire
  approval pipeline if that port went HTTPS-only. The two-listener design exists
  specifically to avoid needing any TLS-bypass code in those 10 scripts or the Tray
  app.
- **Live-verified end-to-end**: `.\install.ps1` redeploy showed the cert generating
  fresh, both listeners binding (`https://[::]:5264`, `http://localhost:5266`), and
  hooks restoring correctly in `settings.json`/`hooks.json`. Reinstalled the release
  APK, re-paired via a fresh QR scan (required - old pairings have no fingerprint to
  pin, and the app deliberately fails closed rather than falling back to unpinned
  trust), and the phone connected successfully over the pinned HTTPS/WSS connection.
- **Not yet done**: the rebuilt installer/APK haven't been redeployed to the other 2
  test PCs yet (only the dev machine + dev phone are on the new build so far).

**As of 2026-07-09 (distribution finalized - FCM security fix fully closed out, fresh installer + APK built):**

- **The leaked Firebase key is fully closed out.** User rotated/deleted the key in
  Google Cloud IAM (confirmed via screenshot - the service account now shows "No
  keys") and deleted the local `aethelhook-firebase-adminsdk-fbsvc-5091700472.json`
  file. Combined with the FCM-removal code changes from earlier the same day, this
  issue is completely resolved - **distribution is now cleared** to post publicly.
- **Found and fixed a second, unrelated bug found while finalizing**: QR pairing
  failed with `Failed to connect to /127.0.0.1:5264` because the live service had
  detected `127.0.0.1` as its own LAN IP at its most recent startup (a boot-time race
  condition - see gotcha #18). Fixed with a retry loop in `GetLocalIpAddress()`;
  live-verified via `.\install.ps1` - phone paired successfully afterward.
- **Rebuilt the full distribution pipeline**: signed release APK
  (`app\build\outputs\apk\release\app-release.apk`, via `gradlew assembleRelease`,
  signed with `aethelhook-release.jks`) installed on the dev phone (required
  uninstalling the old debug build first - debug/release signing keys differ, so
  `adb install -r` alone would have failed with a signature mismatch; this wiped
  local app prefs, re-paired via QR afterward). Also hit and fixed a real lint error
  during this rebuild: `MainActivity` becoming a `FragmentActivity` (for the
  biometric-reveal feature) needs Fragment 1.3.0+ for its ActivityResult APIs to work
  correctly, but `zxing-android-embedded`'s `appcompat` and `biometric:1.1.0` both
  transitively resolved Fragment down to 1.2.5 - added an explicit
  `androidx.fragment:fragment-ktx:1.8.5` dependency to force the modern version.
  `AethelHook.API` and `AethelHook.Tray` published fresh to `dist\publish\` /
  `dist\publish-tray\`, `dist\hooks\` confirmed already in sync with the dev copies
  (diffed clean), and `AethelHook-Setup.exe` recompiled via `ISCC.exe` - all baked in
  fresh together (FCM removal, LAN-IP retry fix, fragment version fix).
- **Confirmed done**: user reinstalled the rebuilt `AethelHook-Setup.exe` on both
  other test PCs (2nd PC, brother's PC) - all three machines plus the dev phone now
  run the fully fixed build (FCM removed, LAN-IP retry widened to 60s, fragment
  version pinned). User confirmed everything working fine. Distribution-readiness
  work from this session is complete.

**As of 2026-07-09 (Codex sandbox-lock fix on fresh PCs, installer rebuilt again):**

- **Fixed a third Codex-on-other-PCs bug**, reported after installing on two more
  machines: mobile Session Access prompts to Codex failed with `windows sandbox:
  helper_sandbox_lock_failed: lock sandbox bin dir <profile>\.codex\.sandbox-bin
  failed` before the shell could even run. Root cause and fix are gotcha #16 above -
  `RunHeadlessCodexPromptAsync` now passes `-c sandbox_mode="danger-full-access" -c
  approval_policy="never"` explicitly instead of assuming the user's `config.toml`
  already has them.
- **`AethelHook-Setup.exe` rebuilt** (`dotnet publish` API + Tray, `ISCC.exe
  AethelHook.iss`) to bake in the fix; `dist\hooks\` was already in sync (no hook
  script changes this time, only `Program.cs`).
- **Live-verified on all three machines now.** Dev machine via `install.ps1` (fresh
  exe timestamp confirmed), and both originally-affected PCs via reinstalling the
  rebuilt `AethelHook-Setup.exe` - Codex mobile Session Access prompts complete
  cleanly on all three with no `helper_sandbox_lock_failed` error. This closes out
  the third and (so far) last Codex-on-other-PCs bug found via real multi-machine
  testing this week (see gotchas #14/#15/#16).

**As of 2026-07-08 (Codex Stop-hook fully fixed on a real slow PC, installer rebuilt twice):**

- **Root-caused and fixed two separate bugs blocking Codex on other PCs**, both
  found via live troubleshooting on an affected machine (not static review - see
  `feedback_live_hook_testing` in memory): the gotcha #14 BOM issue (Codex couldn't
  even parse `hooks.json`) and the gotcha #15 Stop-hook timeout/fire-and-forget issue
  (approval gate worked once BOM was fixed, but agent-done summaries still never
  arrived on a genuinely slow PC). Full detail in gotchas #14/#15 above.
- **`AethelHook-Setup.exe` rebuilt twice this session** - once after the BOM fix +
  timeout bump to 15, again after the fire-and-forget rewrite + bump to 30. Both
  `dotnet publish` (API + Tray) and `ISCC.exe AethelHook.iss` steps confirmed
  successful each time; `dist\hooks\codex\` kept in sync with the dev copies
  (including the new `notify_async.ps1`) as changes were made, not batched.
- **Live-verified end-to-end on the actual originally-affected slow PC**: uninstalled
  the old AethelHook, installed the rebuilt `AethelHook-Setup.exe`, confirmed working
  perfectly - Codex approval gating and Stop-hook agent-done summaries both reach the
  phone reliably now. This is the first fix in this project verified on a genuinely
  separate real-world machine, not just the dev box.
- Also live-verified the dev machine's own local service picks up both changes
  correctly via `.\install.ps1` (confirms `hooks.json`'s declared timeout updates
  5→15→30 in step with each `Program.cs` change).

**As of 2026-07-07 (end of session - installer packages rebuilt):**

- **Rebuilt both distribution packages to pick up everything from today's session**
  (Session tab bugfixes, project-state persistence, Codex Session Access, the Codex
  Stop-hook `cwd` fix - see entries below): `.\gradlew assembleRelease` →
  `app\build\outputs\apk\release\app-release.apk` (signed with
  `aethelhook-release.jks`, not a debug build), plus fresh `dotnet publish` output for
  both `AethelHook.API` and `AethelHook.Tray` into `dist\publish\`/`dist\publish-tray\`,
  then `ISCC.exe AethelHook.iss` → `AethelHook-Setup.exe`. `dist\hooks\` was already in
  sync with the dev copies (diffed clean) since the Codex Stop-hook fix was applied to
  all three copies (dev/dist/live) as it was made, not batched for later.
- **Not yet tested end-to-end on a genuinely separate machine** - this rebuild is
  ready for that test (per the user's stated intent to try it on other devices) but
  hasn't happened yet. First real test of the full distribution pipeline itself, not
  just a rebuild.

**As of 2026-07-07 (Codex Stop hook cwd fix):**

- **Fixed: a project open only in a Codex agent window (IDE) never appeared in the
  phone's project picker, even after triggering hooks.** Root cause: `.codex\hooks\
  on_agent_done.ps1` (Codex's Stop hook) never read or forwarded `cwd` from its stdin
  payload at all - it only ever sent `{message, detail}` to `/hook/notify`, so the
  `if (!string.IsNullOrWhiteSpace(request.Cwd))` guard there always skipped
  registering the project in `KnownProjects`. This is the exact same "Stop hook never
  reported cwd" bug already fixed for Claude Code's copy (see the 2026-07-07 entry
  below) - the fix just never got applied to the Codex-specific copy of the script.
  Codex's PreToolUse hook already proved `cwd` is present on every Codex hook event
  (confirmed live in `on_approval_request.ps1`'s own stdin log), so `on_agent_done.ps1`
  just needed to read `$data.cwd` and forward it, mirroring Claude's copy exactly.
  Applied to all three copies (dev/dist/live) - hook scripts run fresh per invocation,
  no service restart needed. Live-verified: finished a turn in a Codex agent window on
  a brand-new project, confirmed it appeared in `GET /hook/known-projects` immediately
  after.

**As of 2026-07-07 (Session Access now supports Codex too):**

- **Session Access (phone → headless prompt) now works for Codex, not just Claude
  Code.** Per-message toggle in the Session tab (`SmartToy` chip next to the project
  picker, defaults to "claude", persisted via `AppPrefs.getLastAgent`/`setLastAgent`) -
  each send uses whichever agent is currently selected; both agents keep independent
  resumable threads per project directory (`ProjectSessions` for Claude's `session_id`,
  new `CodexProjectSessions` for Codex's `thread_id` - separate namespaces, both
  persisted in `project_state.json`). Real CLI details (verified directly via `--help`
  and live runs, not just docs - the user's own pasted research on Codex's
  programmatic API mixed in inapplicable enterprise/marketing details like "codex
  generate" and a "Compliance API" that don't exist on this install):
  - Binary is `codex.exe` under `<profile>\AppData\Local\OpenAI\Codex\bin\<hash>\` (a
    desktop-app bundle, not an npm/pip install or VS Code extension bundle like
    Claude's) - new `FindCodexCliInfo()` mirrors `FindClaudeCliInfo()`'s "scan
    C:\Users\* for a real profile" pattern.
  - Headless mode is `codex exec --json --skip-git-repo-check -C <dir> "<prompt>"`;
    resume is a distinct subcommand shape, `codex exec resume --json
    --skip-git-repo-check <thread_id> "<prompt>"` (no `-C` - resume has no cd flag at
    all, inherits the original session's cwd, same constraint as Claude's own
    `--resume` gotcha #8). JSON event stream is `thread.started` (→ `thread_id`),
    `item.completed` (assistant text lives at `item.type=="agent_message"`,
    `item.text`), `turn.completed`/`turn.failed`/`error`.
  - **Approval gating survives headless exec unchanged** - live-verified critical
    finding: the user's `~/.codex/config.toml` already has `approval_policy = "never"`
    + `sandbox_mode = "danger-full-access"` (Codex's own native approval UI fully
    disabled), so `on_approval_request.ps1` (the existing `PreToolUse` hook under
    `.codex\hooks\`) is the *only* gate - confirmed it still fires and blocks on the
    phone's decision in headless `exec` mode exactly as it does interactively, with no
    extra flags needed. Deliberately did NOT pass
    `--dangerously-bypass-approvals-and-sandbox` or `--dangerously-bypass-hook-trust`
    (both exist and would either bypass the phone gate entirely or weren't needed since
    hook trust was already established from prior interactive Codex use in this
    directory) - a brand-new, never-interactively-opened project might still hit a
    trust prompt headless can't answer; not yet tested.
  - `BroadcastSessionEventAsync` gained an `agent` param (payload + FCM data), so the
    phone labels bubbles "Codex" vs "Claude Code" from the actual source instead of a
    hardcoded fallback.
  - Live-verified end-to-end: fresh run, real approval round-trip (phone tapped Allow),
    and - the one part not proven by CLI testing alone - resume/continuity: asked
    Codex "what did you just tell me?" on a second phone message and it correctly
    quoted its own prior reply verbatim, confirming the `exec resume` argument
    ordering (not documented anywhere verbatim, inferred from `--help`'s usage line)
    actually works.

**As of 2026-07-07 (Session tab bugfix pass):**

- **Fixed three real bugs in the Session tab chat**, all live-verified: (1) cross-project
  leak - `on_tool_done.ps1` fires for every tool call in every Claude Code window
  (interactive or headless), and the server's `session_update`/`prompt_result`
  broadcasts carried no project info at all, so the phone dumped every project's
  activity into whichever chat happened to be open. Fixed by threading `cwd` through
  `BroadcastSessionEventAsync` (Program.cs) into the WS/FCM payload, and routing each
  incoming chat item on the phone by that `cwd` instead of by whatever's currently
  selected. (2) Chat history was being wiped by an explicit `chat.clear()` in the
  project picker's `onSelect` - removed; `SessionChatStore` is now keyed per-project
  (`projectKey()`, normalized like the server's `OrdinalIgnoreCase` dictionaries) so
  each project's conversation persists independently and switching just changes which
  bucket is rendered. (3) `"prompt_result"` WS messages (the final answer/failure of a
  headless run) had no case in `AethelHookWebSocket.handleMessage` - silently dropped
  whenever WS was connected, only ever surfacing via the delayed FCM fallback
  notification. Added the missing case, plus a "thinking…" bubble (animated dots,
  `SessionChatStore`-backed per-project) that shows from send until the first reply for
  that project's `cwd` arrives.
- **Fixed `KnownProjects`/`ProjectSessions`/`LastKnownCwd` being wiped on every service
  restart.** These were in-memory only ("no expiry yet - entries persist until the
  service restarts" - turned out to be a real problem, not just a comment). Every
  `install.ps1` redeploy or reboot silently cleared the phone's project picker and every
  resumable conversation thread, forcing a project to be re-opened in an IDE just to
  re-register it before the phone could target it again. Now persisted to
  `C:\ProgramData\AethelHook\project_state.json`, loaded on startup and saved after
  every mutation. Live-verified: restarted the service directly (not via install.ps1)
  and confirmed `GET /hook/known-projects` still returned the known project afterward,
  with the exact `"[ProjectState] Restored N known project(s)..."` log line on startup.

**As of 2026-07-07 (later same day):**

- **Fixed AskUserQuestion/Plan-mode automation only ever working inside the AethelHook
  project itself** (see gotcha #13). Reported as "broken after a Claude Code update";
  root cause was actually a pre-existing hardcoded window-title match, not the update.
  `on_ask_question.ps1`/`on_exit_plan.ps1` now derive the real project name from `cwd`
  and pass it to `send_answer_key.ps1`/`send_plan_key.ps1` as `-WorkspaceName`. Applied
  to all three copies (dev/dist/live) and confirmed in sync. **Live-verified on a real
  non-AethelHook project (ERP, via Cursor) - works perfectly.**

**As of 2026-07-07:**

- **Session Access now supports explicit per-project targeting.** Replaced the single
  global `LastPhoneSessionId`/`LastPhoneSessionCwd` pair with `ProjectSessions` (a
  resumable `session_id` per project directory) and `KnownProjects` (every directory
  any hook has reported, with last-seen time). New `GET /hook/known-projects` lets the
  phone list them; the Session screen has a project chip that opens a picker, and
  `send-prompt` targets whichever directory is explicitly picked (falls back to
  `LastKnownCwd` if none is). Verified live across three real project directories
  (AethelHook + two Cursor-hosted work projects). Two real bugs found and fixed during
  that verification: (1) the Stop hook never reported `cwd`, so a turn that finished
  without calling any tool never registered its project (gotcha unrelated to the list
  above - `/hook/notify` now also updates `KnownProjects`); (2) `ProjectSessions`/
  `KnownProjects` used case-sensitive keys, showing the same folder twice due to
  drive-letter casing differences between tools (see gotcha #11).
- **Session tab's chat history was resetting on every tab switch** - root cause was
  plain `remember`-scoped state living inside a composable that gets fully disposed
  by the manual tab switcher (see gotcha #12). Fixed by hoisting the message list into
  a top-level `SessionChatStore` object and resetting the consumed `sessionUpdates`
  StateFlow value after use (was otherwise replaying the last event every time the
  screen re-entered composition). Superseded by the per-project rework above - same
  store, now keyed per-project instead of one shared list.

- **Core approval gateway is solid.** Claude Code, Codex, and Antigravity integrations
  all live and working - approval gating, plan review, question routing, agent-done
  summaries. Fixed several real bugs in it this session: plan-review's "couldn't load
  the plan" failure (now embeds plan text inline over WS with a Tailscale-first fetch
  fallback), mojibake/encoding corruption across all three IDEs' hooks (stdin
  StreamReader + API response charset fixes), and a cross-question answer
  contamination bug in `AskUserQuestion`/`ExitPlanMode` (was reusing the whole
  conversation's `session_id` instead of a fresh GUID per call - see gotcha #6 above).
- **Distribution pipeline fixed end-to-end.** Found and fixed pervasive
  `C:\AethelHook`-hardcoded paths that would've broken any install other than this
  dev machine. Added Codex and Antigravity to the friend-facing installer (previously
  Claude-Code-only). Found and fixed a broken packaging pipeline itself (stale
  `dist\publish\`, missing Tray publish output, `AethelHook-Setup.exe` predating the
  Tray app entirely) - rebuilt fresh, installer now current.
- **Android layout fixed.** Bottom nav pill and floating action bars were overlapping
  system navigation buttons on phones with 3-button nav (missing
  `navigationBarsPadding`/`statusBarsPadding` under edge-to-edge) - fixed across all
  4 screens.
- **Phase 2 - Session Access shipped and verified working, now with real session
  continuity.** Phone can send a prompt to the PC; it runs headlessly via `claude -p`
  (Route A - plain CLI, not the Agent SDK) in the last-known project directory, with
  chunked progress and a final result pushed back to the phone. This is the SECOND
  design - the first (OS-level keystroke injection into the live interactive session,
  via the Tray app) was replaced after a safety review found real risk of misdirected
  input with no way to verify or undo it. The Android UI is a WhatsApp-style chat
  (user prompts right-aligned, replies left-aligned, timestamps, pinned input row) -
  which meant per-prompt statelessness was a real gap: fixed by tracking the
  `session_id` from each headless run's `"result"` message and passing `--resume
  <id>` on the next phone-sent prompt (`LastPhoneSessionId` in `Program.cs`),
  confirmed live with an actual recall test (told it a word, asked for it back in a
  separate prompt, got it right). No reset mechanism yet - one continuous phone
  conversation per project directory (now persisted across service restarts too, see
  the bugfix pass above). Marked "beta" in the app UI - still new,
  single-active-directory v1 scope only.
- **This file didn't exist until today** - created to replace 3 stale, one-time
  handover docs (now in `docs/archive/`) that described a superseded early
  Antigravity-only architecture.

**Not yet done / open:**
- Installer has not been run end-to-end on a genuinely separate/fresh machine.
- No way yet to explicitly start a *fresh* conversation within an already-known
  project (picking a project you've already talked to always resumes it).
- Chat-persistence fix (this session) not yet re-verified live on the phone.

## Reference docs (still accurate, not session-state)

- `ANTIGRAVITY_HOOKS.md` - Antigravity/Gemini hook event schema, stdin payload shapes
- `codex-hooks-and-approvals.md` - Codex-specific hook/approval details

aethelst8
