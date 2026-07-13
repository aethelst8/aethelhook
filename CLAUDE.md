# AethelHook - Working Notes for Claude Code

AI agent permission gateway: routes dangerous tool calls (and now phone-initiated
prompts) between Claude Code / Codex / Antigravity / OpenCode and an Android phone. See
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
  This is the official PC-side UI: status, gateway toggle, device pairing (now
  gated by Windows Hello, see gotchas #23/#24), live feed. Anything needing to
  interact with the desktop must go through here, not the API service (see
  Session 0 isolation below).
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
22. **`Grep` and `Glob` were never wired into AethelHook at all - missing from both the
    `PreToolUse` matcher list and the `permissions.allow` bypass list in `Program.cs`**,
    so every Grep/Glob tool call hit Claude Code's own native "allow this tool" dialog
    directly, never reaching `on_approval_request.ps1` or the phone. A different failure
    mode than gotcha #13/#21 (native dialog overriding a real hook decision) - here there
    was no hook decision at all, since no matcher existed for the hook to even fire on.
    Confirmed live: `settings.json` had matchers for Write/Edit/Read/NotebookEdit/
    CronCreate/CronDelete/WebFetch/WebSearch/Bash/PowerShell but no Grep/Glob entry, so
    clicking "Always Allow" on the native dialog was a Claude-Code-native grant, not an
    AethelHook one - it didn't persist the way the user expected. Fix: added `Grep`/`Glob`
    as `PreToolUse` matchers (routed to the phone, same treatment as `Read`) and to the
    `permissions.allow` seed list (`aethelAllow`) in `RestoreClaudeCodeHooks()`, mirrored
    in `RemoveClaudeCodeHooks()`'s revocation list, and added the missing `Bash(*)`/
    `Grep(*)`/`Glob(*)` entries to `on_approval_request.ps1`'s own self-destruct fallback
    list (which was already missing `Bash(*)` before this, an unrelated pre-existing gap
    fixed for consistency while touching that array). Claude-Code-specific only - Codex
    and Antigravity route all shell/file access through their own single `run_command`/
    `apply_patch`-style matchers already, so they don't have this gap. Live-verified
    2026-07-11: triggered a real `Grep` tool call after `install.ps1` redeployed,
    confirmed via `hook_debug.log`/`api.log` that it now posts an `APPROVAL_REQUEST` and
    routes to the phone exactly like a `Bash` call, with no native dialog appearing.
23. **Trust must be granted at pairing time, not policed after the token already
    exists.** The first same-day design for "only 1 phone connected at once" asked
    the *currently-connected* phone to approve/deny a new device's WS connection
    (`WsClientStore` transfer-approval). Live testing exposed the real gap: pairing
    (QR scan) and that WS-layer approval are two separate things - a denied phone's
    token was still fully valid for every other phone-facing endpoint (e.g. the
    "Send Test Ping" button kept working via plain HTTP, confirmed live), since
    nothing about a WS-level deny touches `DeviceRegistry.IsValidToken`. Replaced
    entirely (2026-07-12) with a PC-side gate: pairing now requires **Windows Hello**
    on the Tray app's "Pair New Device" button (see gotcha #24 for the interop
    saga), and whichever device completes that ceremony becomes the sole
    `DeviceRegistry._activePhoneToken` (set once, in `PairingStore.TryClaim` -
    `SetActivePhone`). `IsValidToken` - the single choke point already used by
    every phone-facing `ValidateToken(ctx)` call and the `/ws` upgrade check -
    now rejects a `"phone"`-labeled device unless it's the current active one;
    every other paired phone is inert "history" until it re-pairs through Hello
    again. Whatever device gets displaced just gets a one-way `connection_transferred`
    notice (no buttons, no decision to wait on), sent only once the new device's
    `/ws` connection actually registers, not the instant Hello succeeds.
    **Self-gating trap hit while shipping this**: the dev machine's hook scripts
    authenticate via `api_token.txt` (read directly by every `.ps1` hook), which
    turned out to be stored in `devices.json` as a plain `"phone"`-labeled device
    (predating the `"legacy"` migration path ever running on this install) - so the
    moment the active-token restriction shipped, `IsValidToken` rejected it too,
    silently locking this very session's own tool-call approvals (every Bash/Grep
    call failed with `"AethelHook API error"` until diagnosed via `api.log`). Fix:
    `IsValidToken` now checks the token's literal *value* against `api_token.txt`'s
    contents (cached as `_legacyToken` at `Initialize()`) before falling back to the
    label-based check, correct regardless of how a given install's legacy device
    happened to get labeled historically. Live-verified end-to-end after the fix:
    Hello prompt gates a real pairing, hook-script approvals keep working
    throughout.
24. **`[ComImport]` cannot marshal an `IInspectable`-derived WinRT interface at all
    in .NET Core, not just specific parameters on it** - confirmed live building
    `AethelHook.Tray\WindowsHello.cs` (Windows Hello gate for "Pair New Device",
    see gotcha #23), six live-iteration rounds to get right, each with a different
    root cause:
    1. `[MarshalAs(UnmanagedType.HString)]` throws `MarshalDirectiveException` on
       *any* interop signature using it (P/Invoke or COM) - .NET Core's marshaler
       doesn't implement HSTRING marshaling at all. Fix: build/free HSTRINGs
       manually via raw `combase.dll` exports (`WindowsCreateString`/
       `WindowsDeleteString`), pass as plain `IntPtr`.
    2. `[MarshalAs(UnmanagedType.IInspectable)]` on an `out object` parameter throws
       `"Marshalling as IInspectable is not supported in the .NET runtime"` -
       same story, different type. Fix: get the raw `IntPtr` instead, wrap via
       `Marshal.GetObjectForIUnknown` (classic COM interop, unaffected).
    3. `IUserConsentVerifierInterop` derives from **`IInspectable`, not plain
       `IUnknown`** - not guessable from a hand-written `[ComImport]` declaration,
       only confirmed via Microsoft's own docs after two wrong guesses.
       `IInspectable` inserts 3 extra vtable slots (`GetIids`/`GetRuntimeClassName`/
       `GetTrustLevel`) between `IUnknown` and the interface's own method -
       declaring `InterfaceIsIUnknown` calls the *wrong vtable slot entirely*,
       which doesn't throw a catchable exception, it silently **crashes the whole
       process** (confirmed live, an uncatchable access-violation-class native
       crash, not a managed exception - `try`/`catch` cannot stop it).
    4. The real native signature also has a `REFIID riid` parameter (the caller-
       specified IID of the desired output interface) that a first attempt
       omitted entirely, compounding the wrong-vtable-slot crash with a
       parameter-count mismatch too.
    5. Even with the right vtable slot, declaring `InterfaceType(
       ComInterfaceType.InterfaceIsIInspectable)` on a `[ComImport]` interface and
       casting via `Marshal.GetObjectForIUnknown` still throws the same
       `"Marshalling as IInspectable is not supported"` error as #2, this time for
       the interface *itself*, not a parameter - `[ComImport]` fundamentally
       cannot wrap any IInspectable-derived interface in .NET Core, regardless of
       which specific member is the problem. Fix: skip `[ComImport]` entirely -
       read the object's vtable pointer via `Marshal.ReadIntPtr`, resolve the
       target slot's function pointer, and invoke it via
       `Marshal.GetDelegateForFunctionPointer` (the same low-level technique
       CsWinRT's own generated code uses internally, just hand-rolled here for
       this one non-projected interface).
    6. `IAsyncOperation<UserConsentVerificationResult>` (the call's return value)
       is a parameterized WinRT generic - computing its IID by hand
       (`WinRT.GuidGenerator.GetGUID(typeof(...))`, then separately
       `typeof(...).GUID` via reflection) produced two different values, both
       rejected by the OS with `E_NOINTERFACE` (0x80004002). Fix: request the
       fixed, universal `IInspectable` IID (`AF86E2E0-B12D-4c6a-9C5A-D7AA65101E90`)
       instead of guessing the parameterized-generic one, then wrap the result via
       `WinRT.MarshalInspectable<T>.FromAbi`, CsWinRT's own supported helper for
       exactly this "raw IInspectable* to specific projected type" scenario.
    Live-verified end-to-end after all six fixes: Hello prompt appears, PIN entry
    succeeds, `RequestVerificationForWindowAsync` resolves to `Verified`.
25. **The 2026-07-10 pre-release security fix that locked `api_token.txt` down to
    Administrators+SYSTEM (`CryptoUtil.RestrictToAdminSystem`, see gotcha #17's
    entry in the security-review work) broke every PowerShell hook script on any
    PC where that file gets freshly created - i.e. every real end-user install,
    just not the dev machine.** Every hook script across all three IDEs reads
    `api_token.txt` directly as the plain interactive user, not SYSTEM and not
    elevated - and a non-elevated process's UAC-filtered token marks the
    Administrators SID "deny only", so an Administrators-only ACE doesn't grant
    it access even when that account is a local admin. Confirmed live: reported
    as "Codex hooks not firing on another PC" - `PreToolUse` returned "hook
    exited with code 1" (an uncaught `Get-Content` Access Denied terminating the
    script outside any try/catch), `Stop` showed no error but never reached the
    phone (the detached `notify_async.ps1` hit the same read failure silently in
    the background). Not Codex-specific - Claude Code and Antigravity's copies
    read the same file the same way and would fail identically. Never surfaced
    on the dev machine because `LoadOrCreateApiToken`'s "file already exists"
    branch never re-applied the ACL, so a token file predating the 2026-07-10 fix
    just kept its original, more permissive ACL forever. Fix: added
    `FindRealUserSid()` (same "scan `C:\Users\*`" pattern as
    `FindClaudeCliInfo`/`FindCodexCliInfo`) and grant that one resolved account
    explicit Read on `api_token.txt` - deliberately not opened to every local
    account (`Authenticated Users`/`Everyone`), which would undo the original
    fix's actual threat model (other unrelated local Windows accounts reading a
    shared secret). `RestrictToAdminSystem` gained an optional `extraReadSid`
    parameter, only ever passed for `api_token.txt` - every other file it locks
    down (TLS cert, `devices.json`, `active_device.json`, `project_state.json`)
    stays Administrators+SYSTEM only, since only the service itself ever needs
    to read those. The ACL is now reapplied on every startup, not just on first
    token creation, so an already-broken install self-heals on a plain service
    restart - no token reset, no forced re-pair. Rebuilt and reuploaded the
    installer the same day (`AppVersion` stayed at `1.1`, existing-release
    `--clobber` reupload, same convention as the 2026-07-11 Grep/Glob fix).
    **First version of this fix didn't actually work** - reinstalled on the
    originally-affected PC, no change. Root cause of *that*: the first
    `FindRealUserSid()` resolved the SID via
    `new NTAccount(profileFolderName).Translate(typeof(SecurityIdentifier))`,
    which only works if the profile folder name happens to equal a resolvable
    local logon name. A Microsoft-account sign-in's profile folder (derived from
    the account's local-part/display name, e.g. `C:\Users\kabel`) is very often
    **not** a resolvable account name at all - `Translate` throws
    `IdentityNotMappedException`, silently caught, `FindRealUserSid()` returns
    `null`, and the ACL grant is a no-op - reproducing the exact original bug
    with zero visible difference. Exactly the kind of gap likelier to hit a
    different/friend's PC (much more likely Microsoft-account-signed-in) than
    this dev machine. Fix: resolve the SID from the registry instead of
    guessing an account name - `HKLM\SOFTWARE\Microsoft\Windows NT\
    CurrentVersion\ProfileList` is keyed by SID with a `ProfileImagePath` value,
    so matching the discovered profile directory against that gives the real
    SID regardless of account type (local, Microsoft, domain-joined). Also
    added an explicit startup log line (`[Security] Granting hook-script read
    access...` / `Could not resolve a real user SID...`) so a future diagnostic
    session can read `api.log` directly instead of re-deriving this by theory.
    **Live-verified 2026-07-12 on the originally-affected PC** after the
    registry-based fix, rebuilt installer, and reinstall - Codex `PreToolUse`/
    `Stop` both confirmed working. The separately-reported "Session Access
    doesn't work on the other PC" turned out to be fine too on the same
    reinstall - never actually explained by this bug (`FindClaudeCliInfo`/
    `FindCodexCliInfo` locate the CLI by directory existence, not account-name
    resolution, so weren't subject to this failure mode), most likely just
    another symptom of that PC's install being in a broken/incomplete state
    before this session's fixes landed. No separate root cause found or needed.
26. **Antigravity's live hook scripts had never actually been deployed to this dev
    machine at all, and its approval-gate/no-confirmation-needed setup is entirely
    different from Codex's.** Two separate, real findings from a 2026-07-13
    from-scratch pass on Antigravity (deferred since Claude Code/Codex work wrapped
    up):
    - `C:\ProgramData\AethelHook\hooks\gemini\` - where `RestoreAntigravityHooks()`'s
      `hooks.json` points - simply didn't exist on this machine, ever. `install.ps1`
      only rebuilds/restarts the service; it never copies hook script files (that's
      `AethelHook.iss`/`dist\install_hooks.ps1`'s job, for a real installer run,
      which this dev machine never went through for Antigravity specifically).
      Every Antigravity `PreToolUse` approval and every `Stop`/`AfterAgent`/
      `SessionEnd` "done" notification silently failed outright (fire-and-forget
      events ignore exit code per Q2 in `ANTIGRAVITY_HOOKS.md`, so a missing-file
      PowerShell error was invisible). Fixed by copying `.gemini\hooks\*.ps1` to
      that path directly (no elevation needed - the folder's ACL already allows the
      interactive user to write). Also fixed a real bug found at the same time:
      `on_task_complete.ps1` (Antigravity's Stop-equivalent) never read/forwarded
      `cwd`, the same class of bug already fixed for Claude/Codex's own Stop hooks -
      now mirrors their pattern.
    - **Antigravity's own native "Allow running this command?" / file-edit-accept
      dialogs are a *separate* layer from the `PreToolUse` hook, gated by IDE
      settings, not by anything in `hooks.json`.** Initially tried to work around
      the terminal dialog staying stuck (phone answers, dialog never dismisses)
      with a keystroke-injection script (`send_antigravity_key.ps1`, mirroring
      Claude Code's `send_plan_key.ps1`/`send_answer_key.ps1` pattern) - built,
      wired in, then **fully reverted same session** at explicit user request
      ("I don't like this keystroke approach, it's dangerous"). The actual fix
      needed no code at all: Settings (**Ctrl + ,**) > Permissions has **"Terminal
      Command Auto Execution"** (Terminal section) and **"Review Policy"** (Planning
      section) - as of 2026-07-13, both are set to **"Always Proceed"** (corrected
      from an earlier note that had Review Policy at "Auto Accept" - either the
      option was renamed since, or that was wrong the first time; confirmed current
      by the user directly). Both default to
      requiring manual confirmation; setting both removes Antigravity's own native
      dialog entirely for `run_command` and for file edits, leaving AethelHook's
      `PreToolUse` hook (`deny` = hard block, confirmed independent of any dialog)
      as the sole real gate - same tradeoff/precedent as Codex's
      `approval_policy="never"` fix (gotcha #16). **Live-verified working** for
      both settings after the user changed them.
    - **Still unresolved**: `Stop`/`AfterAgent`/`SessionEnd` genuinely never fire,
      even after the missing-file fix above and a full Antigravity IDE restart
      (ruled out stale-cached-config theory). Exhausted every local log location
      (`hook_debug.log`, `api.log`, the `google.antigravity` extension log, the Go
      language-server daemon log, `main.log`, `cloudcode.log`, crash logs) with zero
      trace of these three events ever being dispatched, despite `PreToolUse`
      dispatching and logging correctly every time. The one real lead: Antigravity
      IDE's own DevTools console shows `GetAgentScripts`/`GetMendelFlags` failing
      with `ERR_CERT_AUTHORITY_INVALID` against its own local backend
      (`127.0.0.1:<port>`, a self-signed cert its own embedded Chromium doesn't
      trust) - "GetAgentScripts" is a plausible name for whatever dispatches
      lifecycle hook scripts, but unconfirmed (could be an unrelated Antigravity
      feature, e.g. custom agent skills). This is a bug inside Antigravity IDE
      itself if true - nothing in AethelHook's own code can fix a cert-trust
      failure between two Google-controlled local processes. User plans to report
      it via Antigravity's own "Provide Feedback"; not yet filed.
    - **Investigated and explicitly deferred**: Session Access (phone-initiated
      headless prompt) for Antigravity, twice in the same session. First pass:
      Antigravity IDE itself is Electron-GUI-only, no CLI/exec entry point found
      anywhere in its install directory. Second pass (user surfaced this): Google
      does ship a genuinely separate headless-capable CLI, `agy` (`agy -p "prompt"`,
      `--output-format json`, matching `claude -p`/`codex exec`'s shape) - but (a)
      it is a wholly separate binary/install from the Antigravity IDE, meaning every
      end user would need a second, independent install just for this, and (b) it
      has a known, currently-open upstream bug (`google-antigravity/antigravity-cli`
      issue #76) where headless `-p`/`--print` silently drops all stdout when run
      as a subprocess/non-TTY - exactly the invocation pattern
      `RunHeadlessPromptAsync`/`RunHeadlessCodexPromptAsync` already use. Not worth
      the added install burden plus working around an open upstream bug; revisit
      only if `agy` ships bundled with the IDE or that bug gets fixed.
    - Also found and left alone: a stale, orphaned `C:\Users\<user>\.gemini\
      antigravity-cli\hooks.json` on this dev machine, hardcoded to
      `C:\AethelHook\.gemini\hooks\...` (dev path) with only a 5-second
      `PreToolUse` timeout - unrelated to anything `Program.cs` writes, looks like
      an old abandoned manual experiment (possibly testing `agy` CLI specifically,
      which explains the different config directory name). Not cleaned up yet;
      harmless since `agy` isn't installed here to ever read it.
27. **OpenCode (`opencode-ai`, v1.4.3, already installed on this dev machine) added as
    a 4th approval-gated agent (2026-07-13), approval-gate only - no Session Access.**
    Its hook mechanism is architecturally unlike the other three: a JS/TS **plugin**
    loaded once into OpenCode's own long-running process (not a PowerShell script
    spawned per event via a JSON hooks config). Everything below was confirmed by
    direct empirical testing against the real installed SDK
    (`~/.config/opencode/node_modules/@opencode-ai/plugin/dist/index.d.ts`, the actual
    authoritative type definitions) and real `opencode run` invocations - not trusted
    from blog posts/gists alone, several of which turned out subtly wrong (see below).
    - **Registration**: a plugin is a `.js` file exporting an ESM named export
      (`export const X = async (ctx) => ({ ...hooks })`, matching
      `@opencode-ai/plugin`'s own shipped example verbatim) - CommonJS
      `module.exports.X = ...` **silently fails to load** (no error anywhere, just
      never fires - confirmed live by the complete absence of even a module-top-level
      log line). Registered via a `"plugin": ["<absolute/path.js>"]` array in
      `opencode.json` - **not** auto-discovered by dropping a file into a
      `plugin/`/`plugins/` folder, despite several blog posts claiming otherwise (both
      directory-name variants were tried and neither loaded anything on its own).
      OpenCode's real global config directory is XDG-style even on Windows -
      `<profile>\.config\opencode\opencode.json`, not `<profile>\.opencode\...`.
    - **The approval gate is `tool.execute.before(input, output)`**, confirmed live:
      `input = {tool, sessionID, callID}`, `output = {args: {...}}` (e.g. for `bash`,
      `output.args.command`). **Throwing an `Error` inside it genuinely blocks the
      tool call** - the agent receives a real tool-execution error
      (`state.status:"error"`, `state.error:"<thrown message>"`), not a silent bypass.
      Returning normally lets it proceed. This is the sole gate - no native GUI dialog
      exists to fight (unlike Antigravity), since OpenCode is a pure terminal tool.
    - **Do not build against `permission.ask`, OpenCode's own documented approval
      hook** - it is defined in the plugin SDK's TypeScript types but is **never
      actually triggered** (confirmed by our own live testing - it never once fired
      across many real tool calls - and by an open upstream bug,
      `anomalyco/opencode#7006`, filed Jan 2026, still open). The exact same
      "documented but silently doesn't fire" trap as Antigravity's Stop hook earlier
      this session - caught this time *before* building against it, not after.
    - **There is no dedicated `"session.idle"` hook key.** It's an event *type*
      delivered through a separate generic `event` hook
      (`event: async (input) => {...}`, check `input.event.type === "session.idle"`),
      payload is just `{sessionID}` - no `cwd`, no message text. Confirmed genuinely
      real and working (not a hallucinated blog claim, unlike the CJS-export and
      folder-auto-discovery claims above) - it just never fired during initial testing
      with a free test model that got stuck in an unrelated auto-continuation loop
      ("Continue if you have next steps...") and never reached a true idle state
      within any reasonable timeout; switching to a model that hit a real terminal
      error confirmed the event fires exactly as documented once a session actually
      finishes. Also confirmed live via the *real* production deployment (not just a
      throwaway test plugin): `tool.execute.before` posting to `/hook/event` and long
      -polling `/hook/wait-decision` through the actual running AethelHook API, for
      both `allow_once` and `deny_with_reason` decisions, end to end.
    - **Backend mirrors the Codex pattern** (`FindCodexCliInfo`/`RestoreCodexHooks`/
      `RestoreClaudeCodeHooks`'s merge-preserving-existing-keys approach) but simpler:
      no headless-CLI spawning needed for the approval gate itself (the plugin runs
      inside whatever OpenCode process the user already started interactively), so no
      `FindOpenCodeCliInfo()` was needed - only `FindOpenCodeConfigPath()` +
      `RestoreOpenCodeHooks()`/`RemoveOpenCodeHooks()` (merging/unmerging a single
      string into `opencode.json`'s `"plugin"` array, preserving any other keys/
      plugins the user has, mirroring Claude's approach rather than Codex's
      overwrite-the-whole-file approach - `opencode.json` is far more likely to hold
      real user config worth preserving) + `IsOpenCodeGatewayActive` +
      `/opencode/gateway/activate`/`/opencode/gateway/deactivate`, same shape as
      Codex's. Deployed and live-verified on this dev machine directly (plugin file to
      `C:\ProgramData\AethelHook\hooks\opencode\`, `opencode.json` written by hand
      matching `RestoreOpenCodeHooks()`'s exact output) without needing `install.ps1`,
      since the new endpoints aren't required for the core mechanism to work - only
      for the phone-side gateway toggle.
    - **Explicitly deferred, not built**: Session Access (headless phone-initiated
      prompt). `opencode run --format json` looks genuinely promising for this later
      (confirmed live: clean newline-delimited JSON events - `step_start`/`tool_use`/
      `text`/`step_finish`/`error`, each carrying a resumable `sessionID`; real
      `--continue`/`--session`/`--fork`/`--dir`/`--model` flags) - but scoped out of
      this pass to match how Session Access was added later as its own follow-up for
      Claude/Codex too. One quirk worth knowing before attempting it: `opencode run`
      did not cleanly exit after producing a final response in several live tests -
      unclear yet whether that's model-specific (the free `opencode/big-pickle` test
      model's own auto-continuation behavior) or a `run`-mode-general quirk; needs
      more investigation before a `RunHeadlessOpenCodePromptAsync` is written against
      it.
    - **`install.ps1` has since been run and the new endpoints are live-verified**:
      `/opencode/gateway/activate`/`deactivate` confirmed working directly (activate
      restores the exact `"plugin"` entry, deactivate cleanly removes the whole
      `plugin` key, no duplicate entries on repeated activation - the merge logic is
      idempotent).
    - **Android app updated the same day**: added a third "OpenCode" gateway toggle
      to `MainActivity.kt`'s dashboard (mirroring the existing Codex toggle block
      exactly - own `Switch`, own `openCodeGatewayEnabled` state, POSTs to
      `opencode/gateway/activate`/`deactivate`) plus
      `getOpenCodeGatewayEnabled`/`setOpenCodeGatewayEnabled` in `AppPrefs.kt`. Every
      `anyEnabled`/`anyGatewayEnabled` three-gateway combine check (whether the
      WebSocket service should be running, what the status hero card shows) updated
      to a 3-way OR across all three flags. Rebuilt via `assembleDebug` (the phone
      already had a debug build installed, confirmed via `dumpsys package` showing
      the `DEBUGGABLE` flag, so `adb install -r` worked with no uninstall/signature-
      mismatch needed), installed on-device, and **user confirmed the toggle displays
      and works correctly.** No version bump for this change.
28. **OpenCode Session Access (phone-initiated headless prompt) added - `opencode run`
    has a real, reproducible "doom loop" bug that the documented config option to
    disable does NOT actually disable.** After the model gives a genuine final reply,
    OpenCode itself injects a synthetic user message ("Continue if you have next
    steps, or stop and ask for clarification if you are unsure how to proceed.") and
    keeps looping the agent indefinitely - confirmed live, reproduces with `--pure`
    (no AethelHook plugin loaded at all) and with a totally fresh, un-resumed session,
    so it's unrelated to our plugin, the model/provider configured, or any tool call -
    a general `opencode run` behavior. Each extra round re-sends the full cached
    context (~229k tokens in testing) for no benefit - real time and real money burned
    looping on nothing. OpenCode's own config schema has a `permission.doom_loop`
    option (`PermissionActionConfig`, valid at both the global top level and per-agent)
    that looks like exactly the right knob - tried both `"deny"` globally and on a
    dedicated `mode:"primary"` agent invoked via `--agent`, live, and the loop
    continued regardless. Same class of bug as gotcha #27's `permission.ask` - a
    documented option that silently does not do what it says. Fix: don't fight it -
    `step_finish` with `reason:"stop"` is OpenCode's own signal that the model gave a
    final answer with no pending tool calls (the same natural end-of-turn boundary
    Claude's `-p` and Codex's `exec` give for free), so `RunHeadlessOpenCodePromptAsync`
    captures that as the result and immediately `Kill(entireProcessTree: true)`s the
    process, before the synthetic nudge can ever be injected. Live-verified this is
    safe for resume: killing right after "stop" still leaves a fully resumable
    session - a follow-up `--session <id>` run correctly recalled a detail from the
    killed run's conversation, through the real deployed API, not just a standalone
    CLI test. One more quirk observed (not fixed, not ours to fix): a resumed
    session's very first reply sometimes comes back wrapped in a "## Goal /
    Instructions / Discoveries / Accomplished" summary template instead of directly
    answering - reproduces identically with `--pure` and no resume at all, so it's a
    model/CLI response-style quirk, not an AethelHook or resume-logic bug; the
    resumed context itself was still correct in every test (it named the exact prior
    answer). CLI discovery: OpenCode installs as a global npm package - the
    `opencode`/`opencode.cmd`/`opencode.ps1` shims on PATH just relaunch the real
    platform binary at
    `<profile>\AppData\Roaming\npm\node_modules\opencode-ai\node_modules\opencode-
    windows-x64\bin\opencode.exe` (confirmed live it runs standalone, no node.exe
    wrapper needed) - `FindOpenCodeCliInfo()` resolves straight to that, same
    "scan C:\Users\*" pattern as `FindClaudeCliInfo`/`FindCodexCliInfo`. Session
    continuity uses OpenCode's own `sessionID` (`OpenCodeProjectSessions`, a third
    per-directory dictionary alongside `ProjectSessions`/`CodexProjectSessions`,
    persisted in `project_state.json` the same way). Android's Session tab agent
    toggle now cycles Claude -> Codex -> OpenCode instead of just two (shared
    `agentLabel()` helper in `SessionActivity.kt`). Live-verified end-to-end against
    the real running service after the user ran `install.ps1`: a real
    `/hook/send-prompt` call with `agent:"opencode"` replied cleanly ("PONG", no
    doom-loop garbage), and the user independently tested the same flow from their
    own phone against a different project directory while this was being verified.
    Debug APK rebuilt and reinstalled via `adb install -r`. Session Access is now at
    feature parity across all three headless-capable agents (Claude, Codex,
    OpenCode) - Antigravity remains approval-gate-only per gotcha #26.

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
| `AethelHook.Tray\WindowsHello.cs` | Windows Hello gate for "Pair New Device" - raw WinRT vtable interop, see gotcha #24 |

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

**As of 2026-07-13 (OpenCode Session Access added - headless phone prompts now work
for all three headless-capable agents):**

- **Full detail in gotcha #28 above.** Short version: extended `/hook/send-prompt` to
  support `agent:"opencode"`, mirroring the existing Claude/Codex headless runners.
  Found a real, reproducible bug in `opencode run` itself during empirical testing
  (same "verify before building" discipline as the plugin work earlier the same day):
  after a genuine reply, OpenCode injects a synthetic "continue if you have next
  steps" nudge and loops indefinitely, burning real tokens - and the documented
  `permission.doom_loop: "deny"` config option does not actually stop it (tried both
  globally and per-agent, live). Fix: treat `step_finish` with `reason:"stop"` as the
  real end of the turn and kill the process immediately, before the loop can restart -
  confirmed this doesn't break resumability (a follow-up `--session <id>` run still
  recalls the killed run's conversation correctly).
- **`FindOpenCodeCliInfo()`** resolves straight to the real platform binary
  (`opencode-ai\node_modules\opencode-windows-x64\bin\opencode.exe`) rather than going
  through the npm shim, same pattern as `FindClaudeCliInfo`/`FindCodexCliInfo`.
  `OpenCodeProjectSessions` tracks OpenCode's own `sessionID` per directory, persisted
  in `project_state.json` alongside the other two agents' session maps.
- **Android's Session tab agent toggle now cycles three ways** (Claude -> Codex ->
  OpenCode) instead of two, via a shared `agentLabel()` helper in `SessionActivity.kt`.
- **Live-verified end-to-end against the real deployed service** after the user ran
  `install.ps1`: sent a real `/hook/send-prompt` with `agent:"opencode"` targeting
  `C:\AethelHook`, got back a clean "PONG" with no doom-loop noise, then confirmed a
  follow-up resumed message correctly recalled the prior turn. The user independently
  exercised the same flow from their own phone against a different project directory
  while this was being verified. Debug APK rebuilt (`assembleDebug`) and reinstalled
  via `adb install -r`.
- **Superseded by the full release below** - this was true at the time but the
  friend-facing installer and a signed release APK have since been built and shipped.

**As of 2026-07-13 (release finalized - v1.1.0 Android / v1.2 Windows installer,
website fully updated for all 4 agents):**

- **Full distribution pipeline run end to end**: `dotnet build`/`publish` for both
  API and Tray, signed Android release APK via `gradlew assembleRelease`, and
  `AethelHook-Setup.exe` recompiled via `ISCC.exe` - all baked in together (OpenCode
  Session Access + notification fix + the Android 3-way agent toggle + the
  Antigravity `cwd` fix + OpenCode's installer entries, several of which had been
  sitting uncommitted from earlier in the day).
- **Versions bumped for the first time this cycle**: Android `versionCode` 4 -> 5,
  `versionName` "1.0.3" -> "1.1.0"; Windows installer `AppVersion` "1.1" -> "1.2".
  Committed and pushed to `main` (`09aa474`).
- **Installed the new release APK on the dev phone** - required an uninstall first
  since the phone had a *debug* build installed (from the OpenCode toggle testing
  earlier that day) and debug/release signing keys differ, same gotcha as the
  2026-07-09 distribution entry below. This wiped the phone's local pairing token,
  requiring a fresh QR re-pair - **hit the self-gating gotcha live**: with the phone
  unpaired, this very session's own `PowerShell` tool call (compiling the installer)
  hung and was auto-denied on timeout, since AethelHook's own approval gate had no
  phone to reach. Resolved by having the user re-pair before retrying.
  See [[project_aethelhook_self_gating_gotcha]].
- **New GitHub release `v1.1.0`** created with `aethelhook_v1.1.0.apk` (Android,
  following the per-version-tag convention); the rebuilt `AethelHook-Setup.exe` was
  uploaded to the *existing* `v1.0.0` release with `--clobber`, continuing the
  established convention that the Windows installer's own `AppVersion` is
  independent of its GitHub release tag.
- **Website fully updated for 4-agent coverage** (separate repo,
  `aethelst8.github.io`): Hero/Features/Setup copy, full per-agent setup
  instructions added to the main setup guide (Codex's Settings > Hooks Trust-button
  step with a real screenshot, Antigravity's two IDE settings plus its documented
  Stop-hook gap, OpenCode's `npm install -g opencode-ai` requirement), Session
  Access explicitly scoped to Claude Code/Codex/OpenCode only (Antigravity has no
  headless mode), the phone dashboard screenshot swapped for one showing the 3-way
  agent toggle, and the Android download link bumped to `v1.1.0`. Built, linted, and
  pushed (`c10e8fb`) - GitHub Actions redeployed successfully.
- **Corrected a stale detail while writing the site copy**: gotcha #26's original
  note had Antigravity's "Review Policy" setting at "Auto Accept" - the user
  confirmed live it's actually "Always Proceed" (same value as "Terminal Command
  Auto Execution"), corrected in both places gotcha #26 mentions it.

**As of 2026-07-13 (OpenCode added as a 4th approval-gated agent, approval-gate only):**

- **Full detail in gotcha #27 above.** Short version: added OpenCode (v1.4.3, JS/TS
  plugin architecture, unlike the other three's PowerShell-hook-per-event model).
  Went through a mandatory empirical-verification phase first (given how much the
  Antigravity Stop-hook mystery cost this same session from building against
  unverified hook behavior) - caught two real inaccuracies in blog-post/gist sources
  before writing any real code: CommonJS `module.exports` silently fails to load
  (needs ESM `export const`), and plugins are NOT auto-discovered from a
  `plugin/`/`plugins/` folder (must be registered via `opencode.json`'s `"plugin"`
  array). Also confirmed OpenCode's own documented `permission.ask` hook is broken
  (open upstream issue) before building against it - used `tool.execute.before`
  instead (confirmed reliable, throwing an Error genuinely blocks the tool call).
- **Live-verified end-to-end against the real running AethelHook API**, not just a
  syntax check: real `opencode run` invocations, real `/hook/event`/
  `/hook/wait-decision` round trips, both `allow_once` and `deny_with_reason`
  decisions confirmed to actually take effect (deny surfaces as a genuine
  tool-execution error to the agent).
- **Program.cs backend mirrors the Codex pattern** (`FindOpenCodeConfigPath`,
  `RestoreOpenCodeHooks`/`RemoveOpenCodeHooks` merging `opencode.json`'s `"plugin"`
  array, `IsOpenCodeGatewayActive` + `/opencode/gateway/activate`/`deactivate`) -
  simpler than Codex's, since the approval gate needs no headless-CLI-spawning logic
  (the plugin runs inside whatever OpenCode process the user already has open).
  `dotnet build` succeeds; deployed to this dev machine by hand (plugin file +
  `opencode.json`) without needing `install.ps1`, since the new endpoints only matter
  for the phone-side gateway toggle, not the core mechanism.
- **Explicitly deferred**: Session Access (headless phone prompt) for OpenCode.
  `opencode run --format json` looks genuinely promising for a future pass (clean
  JSON event stream, real resume/session flags) - but scoped out to match how this
  was added later as its own follow-up for Claude/Codex too.
- **`install.ps1` has since been run by the user and the live service confirmed
  working**: `/opencode/gateway/activate`/`deactivate` verified directly (activate
  restores the exact `"plugin"` entry, deactivate cleanly removes it, no duplicates on
  repeated activation - the merge is idempotent).
- **Android app updated the same day**: a third "OpenCode" gateway toggle added to
  `MainActivity.kt`'s dashboard (mirrors the Codex toggle exactly) plus
  `getOpenCodeGatewayEnabled`/`setOpenCodeGatewayEnabled` in `AppPrefs.kt`; every
  `anyEnabled`/`anyGatewayEnabled` check updated to a 3-way OR. Rebuilt via
  `assembleDebug`, installed via `adb install -r` (the phone already had a debug
  build, so no uninstall/signature-mismatch issue), and **user confirmed the toggle
  works correctly on-device.** This closes out the OpenCode integration for this
  session - approval gate + phone-side toggle both fully live-verified end to end.
- Installer files (`dist\hooks\opencode\`, `AethelHook.iss`,
  `dist\install_hooks.ps1`) are updated and ready but not yet exercised via an actual
  `AethelHook-Setup.exe` rebuild/run - only the dev machine's live service and the
  debug APK have been verified so far.

**As of 2026-07-13 (Antigravity from-scratch pass - deployed missing hooks, found the
real approval-dialog fix, Stop hook still unresolved):**

- **Full detail in gotcha #26 above.** Short version: Antigravity's live hook scripts
  had never actually been deployed to `C:\ProgramData\AethelHook\hooks\gemini\` on
  this dev machine (only `dist\` and the dev `.gemini\hooks\` copies existed) -
  fixed, plus fixed `on_task_complete.ps1` not forwarding `cwd` (same class of bug
  already fixed for Claude/Codex).
- **Antigravity's native approval dialogs turned out to be an IDE settings problem,
  not a hooks.json problem.** Tried keystroke injection first
  (`send_antigravity_key.ps1`), then fully reverted it same-session at explicit user
  request. The real fix needed zero code: Settings (**Ctrl + ,**) > Permissions >
  **"Terminal Command Auto Execution" = "Always Proceed"** and **"Review Policy" =
  "Always Proceed"** - both live-verified working, with AethelHook's `PreToolUse` hook
  now the sole real gate (same precedent as Codex's `approval_policy="never"`).
- **Still open**: `Stop`/`AfterAgent`/`SessionEnd` never fire at all, even after the
  missing-file fix and a full Antigravity restart. Best lead so far is a
  `GetAgentScripts`/`GetMendelFlags` cert-trust error in Antigravity's own DevTools
  console (against its own local backend) - unconfirmed whether that's actually the
  hook-dispatch path, and not something AethelHook's own code can fix regardless.
  User is reporting it to Google via Antigravity's own feedback channel; not yet
  filed as of this session.
- **Explicitly deferred, not pursued**: Session Access (phone-initiated headless
  prompt) for Antigravity. Investigated twice - once ruling out the GUI IDE (no
  CLI/exec entry point at all), once re-opened after learning Google ships a
  separate `agy` CLI with real headless support (`agy -p`) - but that CLI needs its
  own independent install (not bundled with the IDE) and has an open upstream bug
  dropping stdout in headless/non-TTY subprocess mode. Revisit only if either of
  those change.

**As of 2026-07-12 (api_token.txt ACL fix - hooks failing on other PCs):**

- **Fixed a real bug found via a second-PC install: Codex `PreToolUse` returned
  "hook exited with code 1", `Stop` fired silently and never reached the phone.**
  Full root cause and fix are gotcha #25 above - the 2026-07-10 security fix that
  locked `api_token.txt` to Administrators+SYSTEM broke every hook script's direct
  read of that file on any freshly-created install, since hook scripts run as the
  plain (often non-elevated) interactive user, not Codex-specific despite how it
  was first reported. Fix grants the resolved real-user account explicit Read via
  a new `FindRealUserSid()` helper, reapplied every startup so an already-broken
  install self-heals via `install.ps1`/service restart, no token reset needed.
- **First fix attempt didn't work** - reinstalled on the affected PC, no change.
  Root cause: `FindRealUserSid()` resolved the SID by translating the profile
  folder name as a local logon name (`NTAccount(name).Translate(...)`), which
  silently fails for a Microsoft-account sign-in (the folder name isn't a
  resolvable account name at all) - exactly the kind of setup likelier on a
  different/friend's PC than this dev machine. Re-fixed by resolving the SID
  from the registry's `ProfileList` (keyed by SID, matched via
  `ProfileImagePath`) instead of guessing an account name - works regardless of
  account type. Also added an explicit `[Security] Granting hook-script read
  access...` / `Could not resolve a real user SID...` startup log line so this
  doesn't need re-diagnosing by theory again.
- **Rebuilt and reuploaded the installer twice this session** (`AppVersion`
  stayed `1.1`, existing `v1.0.0` GitHub release, `--clobber` reupload each time
  - confirmed via `gh release view v1.0.0` asset timestamps - same convention as
  the 2026-07-11 Grep/Glob fix). `dotnet publish` (API + Tray) and
  `ISCC.exe AethelHook.iss` run directly this time rather than by the user;
  `install.ps1` still run by the user (elevated, can't be done from this session).
- **Live-verified end-to-end on the originally-affected PC**: reinstalled the
  second (registry-based) fix, Codex `PreToolUse`/`Stop` both confirmed working.
  The separately-reported "Session Access doesn't work on that PC" turned out
  fine too on the same reinstall - not actually caused by this bug (see gotcha
  #25's closing note), most likely just another symptom of that PC's install
  being in a broken/incomplete state beforehand. This closes out both reports.

**As of 2026-07-12 (Windows Hello pairing gate shipped as v1.0.3 / installer 1.1,
privacy policy + demo videos + social links added to the website):**

- **Replaced the same-day phone-approval connection-transfer feature with a PC-side
  Windows Hello pairing gate.** Full design and the self-gating lockout hit while
  shipping it are gotcha #23; the six-round WinRT interop saga to actually get
  Windows Hello working from a plain WPF app (`AethelHook.Tray\WindowsHello.cs`) is
  gotcha #24. Net result: pairing a new device now requires Windows Hello
  (PIN/fingerprint/face) on the PC before a QR code even appears; only one phone is
  ever the active connection; every other paired phone is inert "history" until it
  re-pairs. Falls back to no gate if Windows Hello isn't configured on the PC at
  all, per explicit product decision, rather than blocking pairing outright.
- **Shipped as Android `v1.0.3`** (versionCode 4) **and Windows installer
  `AppVersion` 1.0 → 1.1** (same day, both bumped per explicit request - previous
  installer-only rebuilds had left `AppVersion` at a fixed `"1.0"`). Windows
  installer re-uploaded to the existing `v1.0.0` GitHub release (`--clobber`, same
  convention as before); Android got a genuinely new tagged release,
  `v1.0.3/aethelhook_v1.0.3.apk`.
- **aethelst8.com got four separate additions this session**, all pushed:
  1. A new homepage section (`PairingSecurity.jsx`) explaining the Windows Hello
     gate with a real screenshot, plus updated Setup/Features copy and two of the
     three Guides pages, since they still described the old QR-only pairing flow.
  2. A privacy policy page (`/privacy/`), written from scratch rather than adapting
     a generated one - the generated draft (privacypolicygenerator.info) invented a
     full Cookies/Tracking section, account management, marketing use, and
     24-month retention schedules, none of which apply (no accounts, no cookies,
     no analytics, no server AethelSt8 operates anywhere). Wired up the same way as
     the Guides pages (static `index.html` shell + `src/pages/*.jsx` + entry file +
     registered in `vite.config.js`/`scripts/prerender.mjs`/`entry-server.jsx`).
  3. Both demo videos embedded in the Demo section as plain YouTube iframes (not
     served from the repo): the tray app demo at 16:9, the phone demo (uploaded as
     a Short, recorded in portrait) at a 9:16 `aspect-ratio` variant so it renders
     tall instead of getting letterboxed into the existing fixed-`max-height` frame
     that assumed image/landscape content only.
  4. Reddit/YouTube/Product Hunt icon links in the footer, path data pulled
     directly from Simple Icons rather than reconstructed from memory. Initially
     placed in the footer-links row, then moved next to the "2026 ÆthelSt8"
     copyright line per follow-up request.
  New standing workflow rule saved to memory
  (`feedback_website_sync_after_installer_changes`): update the website's
  installer links/versions/content in the same pass as any real installer change,
  not as a separate follow-up to ask about.

**Live-verified (2026-07-13):** the connection-transfer flow this whole redesign was
built for - pairing a second phone while the first stays connected, the first
getting the plain "Connection ended" notice, a "history" phone's reconnect attempt
being silently rejected - user confirmed working perfectly. This closes out the
last open item from the Windows Hello pairing gate work (gotchas #23/#24).

**Not yet done:** a Reddit launch post is being planned, not yet drafted.

**As of 2026-07-11 (Android UI fixes shipped as v1.0.1, Grep/Glob approval-gate gap
fixed, Windows installer refreshed):**

- **Two small Android fixes**: removed the "Liquid glass theme" subtitle under
  Settings > Appearance, and the Dashboard header logo now switches to a
  white-background variant in light mode instead of always showing the black one.
  The launcher icon is a raster asset (no separate light-mode art existed anywhere
  in the repo), so rather than ship a second icon file the fix recolors the existing
  bitmap at runtime - a luminance-threshold pass (`recolorBlackBackgroundToWhite` in
  `MainActivity.kt`) that maps near-black pixels to white and leaves the blue/grey
  glyph colors untouched, computed once via `remember(ctx, isDark)`.
- **Shipped as GitHub release `v1.0.1`** (versionCode 2, versionName "1.0.1") -
  first version bump since the initial `v1.0.0` open-source release. Debug build
  had a real signature-mismatch snag: the test device had the release-signed APK
  installed, so installing the debug build required an uninstall first (wipes
  pairing/prefs, needs a fresh QR re-pair) - same signing-key gotcha noted in the
  2026-07-09 distribution entry below, now hit from the opposite direction.
  Website's Android download link (`Download.jsx`) updated to point at the new
  release asset.
- **Found and fixed a real gap: `Grep`/`Glob` tool calls were never routed through
  AethelHook at all** - see gotcha #22 above for the full root cause and fix.
  Live-verified via `hook_debug.log`/`api.log` right after the user ran
  `install.ps1`.
- **Rebuilt the Windows installer with the Grep/Glob fix and pushed it to the site
  without bumping the version**, per explicit user request. Since `AethelHook.iss`'s
  `AppVersion` is a separate, independent value from the Android app's version (it
  stayed at `"1.0"`), the rebuilt `AethelHook-Setup.exe` was uploaded to the
  *existing* `v1.0.0` GitHub release with `gh release upload v1.0.0 ... --clobber`,
  overwriting the old binary in place rather than creating a new tag. The website's
  Windows download link needed no change at all, since it already pointed at that
  same `v1.0.0/AethelHook-Setup.exe` URL - verified post-upload via a direct HEAD
  request confirming the new file size.
- **Checked an SEO question and found nothing to fix**: user saw em dashes in
  Google's cached SERP titles for aethelst8.com pages. Grepped both repos for the
  literal character and every encoded form (`&mdash;`, `&#8212;`, `—`) and
  curled the live site directly - all clean, hyphens only. The em dashes were from
  Google's stale index snapshot predating the `fe257c0` fix from the prior session;
  nothing to do here but wait for Google to recrawl (or use Search Console's
  "Request Indexing").
- **Both repos confirmed fully committed and pushed at end of session** (no
  outstanding local changes, no unpushed commits on either `main`) - AethelHook at
  `d910821`, aethelst8.github.io unchanged from its last push this session.

**As of 2026-07-11 (website: new sections, Claude-app clarification, YouTube channel):**

- **Added a Demo section and a Troubleshooting section to aethelst8.com** (separate
  repo, `C:\aethelst8.github.io`), plus two new Features cards and a requirements
  callout in Connect. Troubleshooting lists the four PowerShell service commands
  (`Get-Service`/`Start-Service`/`Stop-Service`/`Restart-Service AethelHook`) kept
  deliberately terse per explicit user feedback - just the command and a one-line
  "use this when", no log path or `install.ps1` mention. Before writing the two new
  feature claims (service auto-starts on boot and survives sleep/wake, phone
  reconnects on its own; LAN/Tailscale IP and API token masked behind a
  biometric/PIN reveal), ran a dedicated code-verification pass against
  `install.ps1`/`AethelHook.iss` service registration, `AethelHookWebSocket.kt`'s
  reconnect logic, and `BiometricAuth.kt` - all three confirmed true before the copy
  went out (see [[project_aethelhook_website]] for the "burned by inaccurate copy
  before" history this follows).
- **Demo section trimmed same day.** Shipped first with three cards (phone
  dashboard screenshot, a PC tray-app launch video, a "phone demo coming soon"
  placeholder) using files the user dropped in `C:\AethelHook\screenshots\`
  (`phoneDashboard.jpeg`, `pc_demo_vid.mp4`, copied into the site repo's
  `public/media/`). User then asked to remove the video and placeholder cards -
  they just created a YouTube channel and plan to host demo videos there instead
  of serving a 32MB `.mp4` directly from the site repo. `public/media/pc-tray-demo.mp4`
  is left in the repo unused for now, not deleted, pending that swap.
- **Added an explicit "not the Claude app" compatibility clarification**, on both
  aethelst8.com (Hero fine-print + first Features card) and `README.md`: AethelHook
  only works with the **Claude Code** CLI and its VS Code extension, and the
  **Codex** CLI and IDE, not the general-purpose Claude assistant app or claude.ai
  (no hook mechanism to route through on those). Antigravity support was left
  as-is in both places - this was purely about the Claude Code vs Claude-the-app
  confusion, not a scope change.
- **Footer copyright simplified** from "Copyright © 2026 ÆthelSt8 / All rights
  reserved" to "2026 ÆthelSt8", per user request.
- All website changes pushed to `aethelst8.github.io` main (`d734bcb`, `ca8e24e`,
  `eb2ee80`); the README clarification pushed to the AethelHook repo main
  (`95b3740`). GitHub Actions redeployed successfully after each push.

**Not yet done:** the PC tray-app demo video and a phone demo recording still need
to go up on the new YouTube channel and get linked back into the Demo section; a
drafted channel description was handed to the user but not confirmed as posted.

**As of 2026-07-10 (open sourced, public security review, aethelst8.com shipped):**

- **AethelHook went from "considering open source" to actually public the same day.**
  Created the project's first git repo (it had none before today), picked MIT,
  cleaned out personal-machine junk (debug logs, a 32MB PowerShell snapshot dump,
  scratch test scripts, a script leaking the dev's Windows username), and pushed to
  **https://github.com/aethelst8/aethelhook** under a dedicated `aethelst8` GitHub
  account (not an org - a personal account was clean enough once a separate
  `aethelst8@gmail.com` identity existed). `gh` CLI installed via winget for this;
  needed both a base `gh auth login` and a later `gh auth refresh -s workflow` (the
  latter specifically to push `.github/workflows/*.yml` files, which the base OAuth
  scope doesn't allow).
- **Ran a from-scratch security review before publishing** (5 parallel review passes:
  API/auth, transport, hook scripts, installer, Android) since going public turns any
  bug into a same-day 0-day disclosure. Found and fixed 2 Critical + 3 High + several
  Medium/Low issues - see gotcha #21 and the git history for the "Fix pre-release
  security review findings" commit for the full list. Worst two: the installer
  granting every local Windows account Full Control over the folder holding the TLS
  private key and every device's token, and the phone's "always allow" list matching
  only a command's first word (allowing "git" once silently auto-approved anything
  starting with "git "). Also found and fixed, later the same day, a real bug in the
  WebSearch/WebFetch native-dialog-dismiss fix itself - see gotcha #21.
- **Removed every em dash from the project** (code comments, docs, hook scripts,
  Android strings) per an explicit, standing style rule from the user - "never use
  em dashes, even on the website." Use commas or hyphens instead, going forward,
  including any future PC-side work and the website.
- **Signing decision: shipping unsigned for now.** Researched Trusted Signing
  (blocked - individual developers currently only eligible in US/Canada, and the
  user is in South Africa), SignPath Foundation / OSSign (both free, but require
  ~6 months of release history this brand-new repo doesn't have), and a paid OV
  cert (viable, ~$70-90/yr, but costs money for a first project). Decided to ship
  the installer and APK unsigned and revisit free signing once the repo is old
  enough to qualify. The README and the aethelst8.com download page both call out
  the resulting SmartScreen/UAC-Unknown-Publisher and Android-unknown-source
  warnings up front so people expect them instead of bailing.
- **Built and shipped aethelst8.com** as a separate repo,
  **github.com/aethelst8/aethelst8.github.io** (the special GitHub-Pages
  user-site repo name, needed for a bare custom domain rather than a subpath).
  DNS is on Namecheap: 4 A records on `@` to GitHub Pages' IPs
  (185.199.108/109/110/111.153) plus a CNAME for `www` to `aethelst8.github.io.`
  (trailing dot required - Namecheap appends the zone name otherwise). Site itself
  is Vite + React, deployed via a GitHub Actions workflow
  (`.github/workflows/deploy.yml`, `build_type: workflow` in the Pages API) rather
  than the legacy branch-based Pages build. Hit two real deployment gotchas worth
  knowing if this ever needs touching again:
  1. **Switching Pages `build_type` from `branch` to `workflow` via the API resets
     `cname` to null.** Has to be re-set explicitly afterward
     (`gh api -X PUT repos/.../pages -f cname=aethelst8.com`) - it does not restore
     itself from the repo's `CNAME` file automatically.
  2. **The legacy branch-deploy and the new Actions-deploy can race** right at the
     moment `build_type` is switched, and the legacy one (which serves the raw
     unbuilt repo root, not `dist/`) can win - this produced a live blank-white-screen
     bug (`index.html` was referencing `/src/main.jsx` directly, which no browser
     can execute). Fixed by manually re-triggering the Actions workflow
     (`gh workflow run deploy.yml`) once `build_type` had fully settled on
     `workflow` - a clean trigger after that point doesn't race with anything.
  Created a GitHub Release (`v1.0.0`) on the **aethelhook** repo with the built
  installer and APK attached, since the website needs real binaries to link to.
  Release-asset filename convention going forward: `aethelhook_v{version}.apk`,
  version number incremented per release (this means the site's Android download
  link is NOT the version-agnostic `releases/latest/download/...` pattern anymore -
  it has to be bumped by hand to the new filename each release, unlike the Windows
  `.exe` which kept the same fixed name).
- **Site copy went through a real revision pass** after the user pushed back that
  the initial version undersold the product - added a whole angle the first draft
  missed entirely: AethelHook isn't just a safety gate, it's also what lets you
  walk away from your desk (out with your phone on Tailscale/mobile data) and stay
  in the loop via notifications, prompt your agent from your phone directly
  (Session Access), and avoid constantly alt-tabbing back to the IDE just to click
  Allow. Also fixed real accuracy gaps caught by the user: the setup instructions
  originally said "open http://localhost:5266/pair in a browser," but the actual
  flow is scanning a QR code from inside the **Tray app**'s own "Pair New Device"
  window (verified against the real `PairingWindow.xaml`/`MainWindow.xaml` and the
  Android app's actual "Scan QR to Pair" button text before writing the copy) - and
  some Windows PCs show a plain UAC "Unknown Publisher" prompt instead of a
  SmartScreen screen, both now documented as expected outcomes.

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
