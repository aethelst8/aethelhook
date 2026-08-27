# AethelHook - Working Notes for Claude Code

AI agent permission gateway: routes dangerous tool calls (and now phone-initiated
prompts) between Claude Code / Codex / OpenCode / Gemini CLI / GitHub Copilot CLI /
Devin CLI and an Android phone. Copilot CLI is approval-gate-only (no Session Access) -
see gotcha #33. Devin CLI started the same way but gained full Session Access the same
day - see gotchas #34/#35. Antigravity support exists in code (see gotcha
#29) but is no longer shipped - a fresh install does not configure it. See
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
  (Codex), `.gemini/hooks/` (Antigravity), `.geminicli/hooks/` (Gemini CLI - a
  deliberately distinct folder from Antigravity's `.gemini/hooks/`, despite the
  confusingly similar name; see gotcha #32), `.copilot/hooks/` (GitHub Copilot CLI,
  see gotcha #33), `.devincli/hooks/` (Devin CLI's standalone terminal CLI only -
  **not** the Devin IDE, which never fires hooks at all; see gotcha #34). Dev copies live in the repo; the API's
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
29. **Stopped shipping Antigravity (2026-07-13) - a real deny-bypass bug, not just the
    already-known missing Stop hook.** Reported by the user testing on a second PC and
    reproduced on this dev machine too: denying a tool call on the phone still let the
    tool run. `hook_debug.log` showed the hook itself doing everything right - a real
    "Internal decision: deny" / "Decision: DENY" for a `replace_file_content` call,
    with the exact `{"hookSpecificOutput":{"permissionDecision":"deny",...}}` + exit 2
    response `ANTIGRAVITY_HOOKS.md`'s own Q5 documents as a "hard enforcement boundary,
    no bypass path" - so the bypass is downstream of the hook script, inside
    Antigravity itself. Prime suspect: gotcha #26's fix set both **Terminal Command
    Auto Execution** and **Review Policy** to **Always Proceed** to kill Antigravity's
    own redundant confirmation dialog - "Always Proceed" may not just hide the dialog,
    it may tell the engine to proceed regardless of any hook's decision. Tried
    reverting just Review Policy back to its original value to isolate which of the
    two settings was responsible - **did not fix it**, so the bypass isn't explained by
    that one setting alone (or reverting it alone isn't sufficient), and the actual
    mechanism is still unresolved. Combined with the pre-existing gaps (gotcha #26's
    Stop hook never firing, no Session Access), decided it's not worth shipping in
    this state. Fix: removed Antigravity entirely from what a fresh install
    configures - dropped the two `dist\hooks\gemini\*` lines from `AethelHook.iss`'s
    `[Files]` and the whole Antigravity block from `dist\install_hooks.ps1` (both
    live-verified: rebuilt `AethelHook-Setup.exe`, no `gemini` folder or hooks.json
    entry created on install). **Explicitly not deleted**: `Program.cs`'s
    `RestoreAntigravityHooks()`/`RemoveAntigravityHooks()`/gateway endpoints, the
    `.gemini\hooks\*.ps1` scripts, `dist\hooks\gemini\*.ps1`, and
    `ANTIGRAVITY_HOOKS.md` - all left in place in case the bypass gets root-caused
    later, they're just no longer wired into a new install. This dev machine's own
    live service is unaffected (still runs `RestoreAntigravityHooks()` on every
    startup, since that's Program.cs's own runtime behavior, not installer-time) -
    only *new* installs from the rebuilt installer lack Antigravity config. Website
    (aethelst8.com) updated the same day to drop every Antigravity mention -
    Hero/Features/Setup back to 3-agent framing, `GuideApprove.jsx`'s whole
    Antigravity subsection removed, `README.md` also updated (was already stale,
    missing OpenCode entirely).
30. **`QuestionActivity.kt`'s single-select radio options and its "Other" option
    updated `QuestionAnswerState` independently, so they could end up mutually
    non-exclusive** - reported by the user via a real screenshot (2026-07-14): a
    single-select `AskUserQuestion` on the phone showed both a normal option AND
    "Other" as selected (both radio circles filled) at once. Root cause: tapping
    "Other" correctly cleared `state.selected = emptySet()` (`QuestionCard`'s
    second `OptionRow`'s `onClick`), but tapping a normal option only ever set
    `state.selected = setOf(opt.label)` - it never reset `state.otherSelected` back
    to `false`. So the repro was: tap "Other" (`otherSelected=true`), then tap a
    normal option (`selected` updates, `otherSelected` stays stuck `true` forever,
    since nothing else ever clears it). Not just cosmetic - `toAnswerValue()`
    appends the Other text whenever `otherSelected` is still true, so a submit in
    this state could send the wrong/extra value to `/hook/answer`. Fix: the normal
    -option `onClick` (non-multiSelect branch) now also sets
    `state.otherSelected = false`. Live-verified: rebuilt debug APK
    (`assembleDebug`), reinstalled via `adb install -r` (required an uninstall
    first - phone had the signed release APK, same debug/release signing-key
    mismatch as prior sessions - user re-paired via QR afterward), triggered a real
    `AskUserQuestion` through this same Claude Code session, tapped Other then a
    normal option - user confirmed only the normal option stayed highlighted.
31. **`MainActivity.kt`'s Settings > About card hardcoded `"ÆthelHook v1.0"` as a
    literal string, never updated across 3 real version bumps** (1.0 -> 1.0.1 ->
    1.0.3 -> 1.1.0) - `build.gradle.kts`'s `versionName` was the only place that
    ever got bumped for each release, so the on-device About screen had been
    silently wrong since the very first version bump. Found by the user noticing
    it didn't match the other version references (installer/GitHub release tags)
    in this same session (2026-07-14). Fix: read the real value from
    `BuildConfig.VERSION_NAME` instead - required adding `buildConfig = true` to
    `android.buildFeatures` in `app/build.gradle.kts` first, since AGP doesn't
    generate the `BuildConfig` class at all unless a module opts in (confirmed via
    `generateDebugBuildConfig` only appearing in the Gradle task graph after this
    was added). Live-verified: rebuilt both debug and a signed release APK,
    installed the release build on the dev phone (uninstall-then-install again,
    same signing-key mismatch as gotcha #30's fix - re-paired via QR), user
    confirmed Settings > About now reads "v1.1.0" correctly. **Version number
    deliberately not bumped** for this fix, per explicit user request - the
    rebuilt APK was instead uploaded to the *existing* `v1.1.0` GitHub release
    (`gh release upload v1.1.0 aethelhook_v1.1.0.apk --clobber`), the same
    tag-reupload convention used for prior installer-only rebuilds.
32. **Added Gemini CLI as a 4th, fully-shipped agent (2026-07-21) - approval gate,
    headless Session Access, and resume all live-verified, with several real
    surprises along the way.** Distinct from Antigravity (gotchas #26/#29) despite the
    confusingly similar name - this is Google's separate open-source terminal CLI
    (`@google/gemini-cli`, npm), not the Antigravity IDE/CLI family.
    - **Personal/free Google-account login for the standalone `gemini` CLI is dead** -
      Google discontinued "Login with Google" for individual accounts on June 18,
      2026, pushing everyone toward Antigravity CLI (`agy`) instead - the same tool
      this project already rejected twice (gotcha #26's stdout-dropping headless bug,
      #29's deny-bypass). The only path left for an individual is a `GEMINI_API_KEY`
      from Google AI Studio (its own separate, tighter free-tier quota - confirmed
      live hitting a 20-requests/day limit on `gemini-3.5-flash` specifically,
      per-model, not shared account-wide). AethelHook stores no key itself -
      `RunHeadlessGeminiPromptAsync` just sets `HOME`/`USERPROFILE` to the real user's
      profile (same gotcha #2 pattern), and `gemini-cli` auto-loads `GEMINI_API_KEY`
      from that profile's own `.gemini\.env` if the user has put one there (its own
      documented persistence mechanism) - no new secret-storage surface added.
    - **An untrusted project folder runs Gemini CLI in a restricted "safe mode" that
      breaks both tool access AND hook execution, and `--skip-trust` does NOT fix
      it.** Confirmed live: in an untrusted folder, `write_file`/`run_shell_command`
      aren't even registered as available tools (the model gets a "Tool not found"
      error, not a permission error), and `BeforeTool`/`AfterTool` hooks silently
      never fire at all - even ones registered globally in `~/.gemini/settings.json`,
      not just project-level ones. `--skip-trust` only suppresses the interactive
      prompt; it does NOT grant full tool access or unlock hook execution the way it
      looks like it should. The only thing that actually worked was adding the folder
      to `~/.gemini/trustedFolders.json` directly (key format confirmed live:
      lowercase drive letter, forward slashes, e.g. `"c:/aethelhook"`). Fix:
      `TrustGeminiFolder()` in `Program.cs` writes that entry automatically before
      every headless run, so Session Access never depends on the user having
      separately, manually trusted the folder via an interactive `gemini` session
      first.
    - **A `BeforeTool` deny genuinely blocks execution even with `--approval-mode
      yolo` active** - confirmed live via a real adversarial test: armed a test hook
      that always denies, then prompted Gemini to call `write_file`,
      `run_shell_command`, `read_file`, `list_directory`, `google_web_search`, and
      `list_background_processes` all with YOLO mode on - every call across that
      session was blocked with the hook's own reason surfaced back to the model, zero
      bypass. A meaningfully better result than Antigravity's confirmed deny-bypass
      (gotcha #29) - this is why Gemini CLI got shipped where Antigravity didn't.
    - **`-o json` headless mode prints ONE pretty-printed JSON blob at the very end of
      stdout, not a newline-delimited event stream** like Codex's `--json`/OpenCode's
      `--format json` - confirmed live even a hard failure (quota exceeded, auth
      error) still ends with a parseable `{"session_id":...,"error":{...}}` object.
      This means `RunHeadlessGeminiPromptAsync` reads all of stdout before parsing
      (scanning backward for the last line that's exactly `{`, since diagnostic
      banners like "YOLO mode is enabled..." can precede the real JSON), and there's
      no mid-turn `session_update` preview the way Codex/OpenCode's streaming formats
      give for free - only the `AfterTool` hook's own heartbeat covers that gap here.
    - **`--resume` genuinely accepts an arbitrary session_id/UUID, despite `--help`
      only documenting `"latest"` or a numeric index** - initially assumed unsafe to
      guess at (shipped without resume wired up, deferring to "test it later" per
      explicit user request) but live-verified two ways in a same-day follow-up: a
      manually-chosen UUID passed via `--session-id` on the first call, and (the
      pattern actually wired up) the auto-generated `session_id` captured from a prior
      run's own JSON response - both resumed correctly in a real word-recall test.
      Same explicit-ID pattern as Codex's `thread_id`/OpenCode's `sessionID`, tracked
      in a new `GeminiProjectSessions` dictionary, not the "latest" ambiguity
      originally feared (which would have risked grabbing an unrelated directory's or
      the user's own interactive session).
    - **`gemini-cli` ships as a plain npm package with no standalone platform binary**
      (unlike OpenCode) - package.json's `bin` entry points at `bundle/gemini.js`, a
      Node.js script; the many hash-named chunk files under `bundle/` it imports
      internally differ per build/version and can't be hardcoded, but `gemini.js`
      itself is stable. `FindGeminiCliInfo()` resolves `node.exe` from the standard
      installer location (`C:\Program Files\nodejs\node.exe`) and spawns
      `node.exe <path-to-gemini.js> ...` directly - same "scan C:\Users\*" profile
      pattern as the other three `Find*CliInfo` helpers.
    - **Real bugs found and fixed while wiring this up, most predating Gemini
      entirely:**
      - `/hook/token-usage` had a hardcoded `{"claude","codex","opencode"}` agent
        filter that silently never included `"gemini"` - the token tracking itself
        was working fine, it just wasn't exposed on that read endpoint.
      - `/hook/session-update` never accepted an `agent` field at all - every "still
        working..." heartbeat defaulted to `<claude>` regardless of source. Caught
        live when a `update_topic` heartbeat (a Gemini-only tool, never used by
        Claude) showed up mislabeled `<claude>` in `api.log`. This bug predated Gemini
        and equally affected OpenCode's own identical heartbeat hook (added back in
        the 2026-07-16 session) - fixed both call sites (`on_after_tool.ps1`,
        `aethelhook-plugin.js`) plus the endpoint itself.
      - Wiring `on_after_agent.ps1` to both `AfterAgent` and `SessionEnd` produced a
        duplicate "Gemini finished" phone notification on every single headless
        prompt - confirmed live both fire together for a headless `-p` run, since the
        process exits right after its one turn, satisfying both "turn finished" and
        "session ended" simultaneously. Fixed by dropping the `SessionEnd` wiring
        entirely - `AfterAgent` alone already covers both the headless case and an
        interactive session's per-turn case.
      - `BootReceiver.kt` only ever checked Claude's gateway flag on boot - a device
        with only Codex or OpenCode enabled (Claude's toggled off) never got the
        WebSocket service auto-started after a reboot. Predates Gemini; found and
        fixed while adding its 4th check.
      - Android's `TokenUsageRow` had no `horizontalScroll`/`maxLines` safety net - fit
        fine at 3 chips, but adding a 4th (Gemini) squeezed the last chip's `Text`
        down to near-zero width, and Compose wrapped it character-by-character down
        the right edge of the screen instead of clipping or scrolling. Confirmed via a
        real screenshot from the user. Fixed with `horizontalScroll(rememberScrollState())`
        plus `maxLines = 1, softWrap = false` on each chip.
      - `SettingsSheet.kt`'s model-list `when(agent)` had no `"gemini"` branch, so it
        silently fell into the `else -> CLAUDE_MODELS` case - selecting Gemini showed
        Claude's `opus`/`sonnet`/`fable` aliases, meaningless for Gemini. Caught live
        by the user. Fixed with a `GEMINI_MODELS` list curated from the real
        `ListModels` API response for this API key (not guessed) - trimmed the dozens
        of TTS/image/robotics/music/research models down to the general-purpose
        text/coding family; the effort picker is hidden for Gemini too, since no
        reasoning-effort equivalent exists on this CLI.
      - Replaced the Session tab's tap-to-cycle agent switcher with a dropdown
        (`DropdownMenu`) - cycling one tap at a time through 2 agents was fine, but
        became genuinely tedious at 4 (up to 3 extra taps to land on the one you
        want).
    - Live-verified end-to-end against the real deployed service across multiple
      rounds: approval gate (a real deny blocking a real tool call, correctly tagged
      `<gemini>`), headless prompt completion, token-usage tracking and exposure, a
      single non-duplicate done notification, and two-turn session resume (a word
      told in message 1 correctly recalled in message 2, purely via the persisted
      `session_id` and the real deployed `/hook/send-prompt` path).
    - **Not yet done**: `AethelHook.iss`/`dist\install_hooks.ps1` were updated with
      Gemini's hook files and settings.json bootstrap section but never exercised via
      an actual `ISCC.exe` rebuild + fresh-machine install test. Android's debug APK
      is installed and live-verified on the dev phone; no signed release build yet.
33. **Added GitHub Copilot CLI as a 5th agent (2026-07-22), then deliberately scaled
    back to approval-gate-only after weighing the cost of managing its own credential.**
    Distinct hook mechanism again: standalone `*.json` files dropped into
    `~/.copilot/hooks/`, each independent (no shared settings file to merge into,
    unlike Claude/OpenCode/Gemini) - `permissionRequest` (the gate, `{"behavior":
    "allow"|"deny","message":"..."}`) and `agentStop` (done notification) wired via
    `RestoreCopilotHooks()`/`FindCopilotHooksDir()`.
    - **Copilot CLI's hook timeout fails OPEN (allows the action) on timeout/error -
      the opposite of every other agent here**, which all fail closed/deny. Confirmed
      from Copilot's own docs, not guessed - `on_permission_request.ps1`'s 90s
      phone-wait budget is sized to always finish comfortably under the declared
      `timeoutSec: 100`, specifically to avoid ever hitting that fail-open path.
    - **Fine-grained PATs only** - classic tokens (`ghp_...`) are explicitly
      unsupported by Copilot CLI's own docs; only a fine-grained PAT with the
      "Copilot Requests" permission works. Headless auth needs
      `COPILOT_GITHUB_TOKEN`/`GH_TOKEN`/`GITHUB_TOKEN` env vars pointing at one.
    - **Full headless Session Access was built and live-verified working**
      (`RunHeadlessCopilotPromptAsync`, `CopilotProjectSessions`, token stored in
      `C:\ProgramData\AethelHook\copilot_token.txt`, ACL-restricted the same way as
      `api_token.txt`) - then **deliberately removed** once the user weighed the
      real tradeoff: Copilot CLI's own documented auth mechanism is Windows
      Credential Manager (DPAPI, per-logon-session), which the LocalSystem-run
      service can never read regardless of any env-var workaround, so a real
      long-lived Session Access setup would mean managing a PAT by hand indefinitely
      - not worth it for a feature the user wasn't sure they'd use. Scope trimmed to
      match Antigravity's own approval-gate-only precedent (gotcha #26/#29):
      `permissionRequest` + `agentStop` only, `postToolUse` (Session Access's own
      mid-turn heartbeat) dropped entirely, `/hook/send-prompt` now explicitly
      rejects `agent:"copilot"` with a clear error instead of silently 404ing.
    - **`agentStop`'s own payload has no response-text field at all** - confirmed
      live via `hook_debug_copilot.log`: `{sessionId, timestamp, cwd,
      transcriptPath, stopReason, stop_hook_active}`, unlike Claude's Stop
      (`last_assistant_message`) or Gemini's AfterAgent (`prompt_response`). The
      reply only exists in the transcript file at `transcriptPath` - a JSONL event
      log - as the most recent `{"type":"assistant.message","data":{"content":
      "..."}}` line. `on_agent_stop.ps1`'s first version guessed 4 wrong field
      names before this was confirmed and fixed - every phone notification had
      shown a blank "Finished working" body until then (the same symptom
      independently traced back to a different root cause for Gemini, see below).
    - Also hit the same debug-log ACL lockout pattern as gotcha #25 (a SYSTEM-run
      process had made `hook_debug_copilot.log` inaccessible to the plain
      interactive user) - not fixed (diagnostic-only, needs elevation), verified
      the fix correctness instead via a standalone PowerShell test extracting a
      known transcript value directly.
    - **Same day, found and fixed the identical "blank summary" bug in Gemini
      CLI's own `on_after_agent.ps1`** - the user's report that Copilot's summary
      was blank ("I think this might be the problem for gemini too") was right:
      Gemini's `AfterAgent` event's real field is `prompt_response`, confirmed via
      `hook_debug_geminicli.log` - none of the 4 originally-guessed names
      (`response`/`last_message`/`message`/`text`) had ever matched, so every
      Gemini done-notification had silently shown "Finished working" too, since
      the integration shipped (gotcha #32) until this fix.
34. **Added Devin CLI as a 6th agent (2026-07-22) - approval-gate only, after
    discovering the actual "Devin IDE" product can't be gated by hooks at all.**
    "Devin" here needs real disambiguation: the user's installed "Devin IDE" turned
    out to be a rebranded Windsurf editor (Cognition acquired Windsurf) - its
    internal ACP-server process logs itself as `chisel`/`windsurf_api_client`, its
    model picker shows Windsurf's own SWE-1.x model family, and its bundled
    extension folder is literally `resources\app\extensions\windsurf\devin\`. This
    is architecturally distinct from the standalone `devin.exe` terminal CLI
    (Cognition's separate product, documented at the bundled `docs.devin.ai` mirror
    shipped inside that same extension folder - `share\devin\docs\`, used as the
    primary source throughout this investigation instead of the live website, since
    it's guaranteed to match the exact installed version).
    - **The Devin IDE runs `devin.exe acp` as an editor subprocess (Agent Client
      Protocol), and ACP mode never fires hooks at all - confirmed live, not
      theorized.** Wired a real logging-only test hook into
      `%APPDATA%\devin\config.json`'s `"hooks"` key, confirmed via process
      start-time that a genuinely fresh ACP process (well after the config edit)
      was running, triggered a real tool call inside the IDE - zero trace of the
      hook ever loading anywhere (no log line, no script execution). Devin's own
      bundled Xcode ACP doc confirms this is by design, not a bug: "Some richer
      interactions are only available in the standalone CLI" - permission
      decisions in ACP mode are handed to the connected editor client (the native
      "Run/Skip" card seen in the IDE), the same way Xcode's own "Agents >
      Permissions" settings own it there. Same shape of dead end as Cursor's own
      hooks (rough/beta, unclear headless support) - a real hooks feature that
      doesn't reach the actual product surface most users would want gated.
    - **The standalone terminal CLI's hooks are real and adversarially unbypassable
      - confirmed live.** `.devin/hooks.v1.json` / `%APPDATA%\devin\config.json`'s
      `"hooks"` key supports `PreToolUse` (fires before every tool call,
      unconditionally, confirmed even under `--permission-mode dangerous` - Devin's
      most permissive "auto-approve everything" mode) and `PermissionRequest`
      (fires only when the built-in permission system would prompt - doesn't fire
      under `dangerous` mode, since nothing needs prompting there). `PreToolUse`
      alone is enough for the gate. Adversarial test: armed a hook that always
      returns `{"decision":"block","reason":"..."}`, ran `devin -p "run git status"
      --permission-mode dangerous` - the agent received "Error: A tool was
      rejected by the user" and the real command never ran. A stdout JSON
      `{"decision":"block"}` + exit 0 blocks; no output allows. **Devin's hook
      timeout fails OPEN on timeout/error, the same danger as Copilot's hook** -
      `on_pre_tool_use.ps1`'s 90s phone-wait budget stays under the declared
      `"timeout": 100`.
    - **Getting the standalone CLI usable at all took several real, live-only-
      discoverable steps**, none guessable from `--help` alone:
      1. `devin auth login`'s interactive TUI (and the git-provider-connect wizard
         on first run) read the Windows console directly, bypassing piped stdin
         entirely - redirecting stdin (`< /dev/null`) or piping answers had zero
         effect, confirmed live. Both had to be completed once by the user in a
         real interactive terminal (`devin auth login`, then `devin setup`).
      2. This dev machine's `AppData\Roaming` has case-sensitivity enabled on one
         specific directory (rare - likely WSL/dev-mode interop) - `devin` and
         `Devin` are two genuinely different folders here, and `devin.exe` itself
         was confirmed live to write to whichever casing already existed (its
         login flow wrote `org_id` into the capitalized `Devin\config.json`, not
         the docs' literal lowercase). `FindDevinConfigPath()` resolves by
         scanning for whichever casing already exists on disk, falling back to
         the documented lowercase only if neither does - correct on both this
         machine and a normal case-insensitive one.
      3. A PATH update from installing the standalone CLI (`irm
         https://static.devin.ai/cli/setup.ps1 | iex`, the officially documented
         Windows install method) doesn't propagate to already-open terminal
         windows/tabs, even brand-new ones spawned from an already-running host
         process - confirmed live via `[Environment]::GetEnvironmentVariable(...,
         "User")` showing the new entry while `$env:Path` in a fresh child process
         still lacked it. Fix for an open window: `$env:Path =
         [Environment]::GetEnvironmentVariable("Path","Machine") + ";" +
         [Environment]::GetEnvironmentVariable("Path","User")`.
    - **Devin CLI reads and executes Claude Code's own `.claude/settings.json`
      hooks by default** (`read_config_from.claude`, one of several tool-config
      "import" flags documented at `reference/configuration/read-config-from.mdx` -
      also imports CLAUDE.md/skills/MCP servers from Claude Code, enabled by
      default) - **found live via a real symptom**: an interactive `devin` session
      finishing a turn produced a phone notification titled "Claude Code finished"
      with a blank body, even though no Claude Code session was involved. Root
      cause: Claude's own `on_agent_done.ps1` (Stop hook) doesn't check which tool
      actually invoked it, and Devin's own `Stop` event payload
      (`{"hook_event_name":"Stop","session_id":"<word-pair>",...}`, no
      `transcript_path`) is structurally different from Claude's own Stop payload
      (which always carries `transcript_path`) - so the script ran, found nothing
      to summarize, and sent a blank notification labeled as Claude Code's own.
      Checked whether this also caused duplicate/wrong approval prompts (Claude's
      `PreToolUse` re-firing for Devin's own tool calls) - confirmed it does NOT:
      Claude's hooks match case-sensitive tool names (`Bash`/`Edit`/`Read`), Devin's
      are lowercase (`exec`/`edit`/`read`), so they never collide; only the
      matcher-less `Stop` event was affected. Fix: `on_agent_done.ps1` now skips
      sending any notification at all when `transcript_path` is absent from
      stdin - every genuine Claude Code Stop event always carries it, so its
      absence means some other Claude-hook-format-compatible tool fired it, not a
      real Claude Code turn.
    - **Devin CLI's own `Stop` event carries no response-text field and no
      transcript-file path at all** (unlike every other agent here) - it stores
      session history in a SQLite database (`sessions.db` under
      `%APPDATA%\devin\cli\`, confirmed in WAL mode via the accompanying
      `-wal`/`-shm` files) instead of a JSONL transcript. Added a real done-
      notification anyway per explicit user request: a companion
      `extract_summary.py` (Python's stdlib `sqlite3`, no extra dependency) queries
      `message_nodes.chat_message` for the most recent `role=="assistant"` row for
      the given `session_id` - confirmed live this works correctly while
      `devin.exe` still has the database open (WAL mode allows concurrent readers)
      and is fast (~0.2s, dominated by interpreter startup). `on_stop.ps1` calls it
      with a bounded 5s wait (`Process.WaitForExit`, killed on timeout) and
      degrades gracefully to a plain "Devin CLI finished" notification with no
      summary if Python isn't on PATH at all - a real, disclosed limitation for any
      install without Python, not a silent failure. **Never emits a `decision`
      field on stdout** - `Stop` is one of the events that can legitimately block
      ("prevent premature stopping" per Devin's own docs), and a stray block here
      would risk the same doom-loop class of bug already hit and fixed for OpenCode
      (gotcha #28).
    - Live-verified end-to-end against the real deployed API and phone across
      multiple rounds: `PreToolUse` approval gate (both `allow_once` and a real
      phone `deny` genuinely blocking `whoami`), and the Stop-hook notification
      with a real extracted summary ("AethelHook summary test successful",
      confirmed received on the phone).
    - **Scope at the time this shipped**: approval-gate + done-notification only,
      no headless Session Access - the standalone CLI's own credential setup
      (separate `devin auth login`, independent of the Devin IDE's own
      Windsurf-API-key auth) plus the ACP-mode gap made this the pragmatic cut.
      `IsDevinCliGatewayActive` + `/devincli/gateway/activate`/`deactivate` + an
      Android toggle (`MainActivity.kt`, `AppPrefs.kt`, `BootReceiver.kt`) shipped
      here. **Superseded the same day** - see gotcha #35: once the user asked
      directly whether Devin supports a headless feature, Session Access was
      added after all (the credential concern didn't apply the same way it did
      for Copilot - the standalone CLI's own file-based `devin auth login` is
      reachable, unlike Copilot's Credential-Manager-only auth).
35. **Added Devin CLI Session Access the same day as gotcha #34, after the user asked
    directly whether Devin supports a headless feature.** Full feature parity with
    Claude/Codex/OpenCode/Gemini's own Session Access, but two genuinely new
    techniques were needed and two real bugs were hit and fixed along the way.
    - **`-p` mode has no machine-readable output at all** - unlike the other four
      agents (JSON, streaming events, or both), Devin's headless stdout is a single
      plain-text blob with no session id anywhere in it. Reusing the same technique
      already built for the done-notification's summary (gotcha #34's
      `extract_summary.py`), a new companion `find_latest_session.py` queries
      Devin's own `sessions.db` directly (`sessions` table, `working_directory`
      column matched against the exact cwd string, newest `last_activity_at` wins)
      to capture the just-created/just-resumed session id after every headless run -
      `DevinCliProjectSessions` (a new per-directory dictionary, same shape as the
      other four) is populated from that, not from anything CLI-reported.
    - **`-p` mode never exits on its own after printing its final response** -
      confirmed live across every test, a clean exit code was never observed
      (similar in spirit to OpenCode's own non-exiting quirk, gotcha #28, but with
      no streaming event to detect "this is the real final answer" the way
      OpenCode's `step_finish` gave for free). Fix: `RunHeadlessDevinCliPromptAsync`
      reads stdout incrementally and kills the process once output has arrived and
      stayed unchanged for 8 seconds (stdout is written essentially atomically once
      generation completes - confirmed live, no partial/trickling output ever
      observed - so this adds minimal latency in the normal case), backstopped by a
      generous 900s hard ceiling for a genuine hang.
    - **Real bug #1 - resume silently never worked on the first attempt.** A live
      two-message test ("say pong" then "what was the last word I asked you to
      say?") produced two entirely separate Devin sessions instead of one
      continuing conversation, even though manually running `devin -r <id> -p
      "..."` in a terminal resumed correctly. Root cause: `find_latest_session.py`
      is spawned directly by the LocalSystem-run API service, NOT as a child of
      `devin.exe` the way `on_stop.ps1`/`extract_summary.py` are (those inherit
      devin.exe's own overridden `APPDATA` down the process tree) - so its
      `%APPDATA%`-expansion fallback silently resolved to LocalSystem's own empty
      profile and the query always found nothing (confirmed live: exit code 0,
      empty stdout, every single time). Fix: `RunHeadlessDevinCliPromptAsync` now
      passes the fully-resolved `sessions.db` path as an explicit argument
      (computed from the same `profileDir` already used for the env-var overrides
      on the main `devin.exe` process), never relying on the script's own
      environment-variable expansion when invoked this way. Live-verified after the
      fix: a real two-message conversation (session id `ribbon-jeep`) correctly
      recalled "Banana" from message 1 in message 2.
    - **Real bug #2 - the reply text never appeared in the phone's chat despite
      Session Access otherwise working perfectly.** Found via direct user report
      after the resume fix above. Root cause: `SessionActivity.kt`'s chat renderer
      only adds a bubble on a `prompt_result` WS event for the FAILURE case - on
      success, it assumes the reply text already arrived as its own bubble via a
      `session_update` event (the other four agents each have some kind of
      mid-turn/PostToolUse-equivalent heartbeat hook that streams this). Devin CLI
      has no such heartbeat hook at all (only `PreToolUse` for the gate and `Stop`
      for the done-notification), so `session_update` never fires for it and the
      assumption silently failed - the reply existed (confirmed in `api.log` and in
      the push notification) but no chat bubble was ever created. Fix: the
      success-path condition now also fires for `agent == "devincli"` specifically,
      leaving the other four agents' existing behavior (and their real
      `session_update` streaming) untouched.
    - Live-verified end-to-end through the real deployed API for both fixes
      together: resumed conversation recall correct, and the reply text now
      genuinely visible as a chat bubble in the Sessions tab, confirmed directly by
      the user on their own phone.
36. **Two small Android UI bugs found and fixed together (2026-07-26), both live-
    verified via a real triggered popup rather than waiting for a real agent event.**
    - **Status bar icon color never actually tracked AethelHook's own in-app theme
      toggle.** `enableEdgeToEdge()` is called once, with no arguments, in each of
      `MainActivity`/`ApprovalActivity`/`QuestionActivity`/`PlanReviewActivity`'s
      `onCreate` - which auto-picks status/nav-bar icon color from the **system-wide**
      dark-mode setting at that single point in time, never from the app's own
      persisted `AppPrefs.getDarkMode()` value, and never again afterward. So flipping
      AethelHook's in-app light/dark switch (which can legitimately disagree with the
      phone's system theme) left the status bar icons stuck wrong-colored - and even a
      device whose system theme matched at launch would drift out of sync the moment
      the in-app switch was flipped, since `MainActivity`'s `isDark` is separate live
      Compose state that `enableEdgeToEdge()` is never re-consulted for. Fix: explicit
      `WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars
      = !isDark` (+ nav bar) in all four activities, computed from the same
      `AppPrefs`/Compose `isDark` value already driving `AethelHookTheme`;
      `MainActivity` wraps it in `LaunchedEffect(isDark)` since its theme can change
      live post-launch, the three single-purpose popup activities set it once since
      their `isDark` never changes mid-lifecycle.
    - **TTS read-aloud voice (added 2026-07-19) still sounded robotic** despite
      already picking the highest-`quality`-tier installed voice. Root cause:
      Android's `Voice.quality` enum is self-reported by the engine and isn't a
      reliable proxy for "sounds natural" - Google's own TTS engine reports a
      HIGH/VERY_HIGH tier for its on-device compact voices too, which remain audibly
      synthetic next to that same engine's network-backed (cloud) voices.
      `Voice.isNetworkConnectionRequired()` is the real signal for "this is the good
      one." Fix: voice selection now sorts network-required voices ahead of local
      ones (falling back to local-only when `ConnectivityManager` reports no
      internet, so it never picks a voice that would then silently fail to
      synthesize offline), before breaking ties by quality tier same as before.
    - **Live-verified together**: rebuilt (`assembleDebug`), reinstalled via
      `adb install -r`, then triggered a real summary popup directly via
      `adb shell am start -n com.aethelhook.app/.MainActivity --es summary_title '...'
      --es summary_body '...'` (same technique as the original 2026-07-19 TTS
      build-and-verify pass) rather than waiting for a real agent-done event. Hit one
      small tooling snag worth remembering: passing multi-word `--es` extras as
      separate quoted bash arguments got mangled by the bash-to-Windows-adb.exe-to-
      Android-shell triple quoting hop (a stray word leaked out as a bogus package
      token - `pkg=Voice` - silently corrupting the intent extras and just bringing
      the existing task forward instead). Fixed by wrapping the *entire*
      `am start ...` command as a single string argument to `adb shell`, so only
      Android's own shell parses the embedded single-quotes, not the Windows/bash
      layers in between. User confirmed on-device: status bar now switches instantly
      with the theme toggle, and the new voice selection "is perfect."
37. **Investigated adding "OpenClaw" as a 7th gated agent (2026-08-02) - dropped after
    live testing found its approval hook doesn't see the tool calls that matter.**
    OpenClaw (`docs.openclaw.ai`, `github.com/openclaw/openclaw`) is not a coding CLI
    like the other six - it's a self-hosted gateway/daemon that connects chat platforms
    (Discord, Slack, Telegram, etc.) to underlying coding agents, with its own
    plugin/hook system. Confirmed live, installed via `npm install -g openclaw@latest`
    (required bumping this machine's Node from 24.11.1 to 24.18.1 via
    `winget install OpenJS.NodeJS.LTS` - OpenClaw hard-fails at startup on anything
    outside `>=22.22.3 <23 || >=24.15.0 <25 || >=25.9.0`, not just an npm warning):
    - The real approval hook is `before_tool_call` (`api.on("before_tool_call", handler,
      {timeoutMs})`), confirmed via the installed package's own `.d.ts` files (ground
      truth over docs, which gave inconsistent framing across two separate fetches of
      the same page). Returning `{block: true, blockReason}` after `await`ing custom
      async logic inside the handler is a real, typed, supported pattern - the design
      this integration would have used, mirroring `.opencode\hooks\aethelhook-plugin.js`.
    - Plugin registration is NOT a bare file path in `plugins.load.paths` (contrary to
      what the docs summary suggested) - it must be an npm-package-shaped directory
      containing `openclaw.plugin.json` (a manifest OpenClaw reads *before* executing
      any plugin code) plus a `package.json` with an `"openclaw": {"extensions":
      [...]}` field pointing at the real entry file. A bare file path fails startup
      with `plugin manifest not found`.
    - Conversation-content hooks (`agent_end`, `before_agent_finalize` - the ones that
      would carry final-reply text for a done-notification, `before_agent_finalize`
      conveniently exposing `lastAssistantMessage` directly) are blocked for any
      non-bundled plugin by default - confirmed via `openclaw plugins inspect <id>
      --runtime --json`'s diagnostics, requiring an explicit
      `plugins.entries.<id>.hooks.allowConversationAccess=true` opt-in most docs pages
      never mention.
    - **The actual dealbreaker**: onboarding's own recommended default (`--auth-choice
      anthropic-cli`, reusing this machine's existing Claude Code login) wires the
      agent to the `claude-cli` backend, which spawns a real external Claude Code CLI
      process over the Agent Client Protocol (ACP). Two live adversarial tests proved
      `before_tool_call` never fires for that path at all - a test plugin returning
      `{block:true}` after a 5s await had zero effect on either a real `whoami` shell
      command (genuinely executed, printed "Moloi") or a real live web search
      (genuinely executed, returned live cited weather data) - confirmed via
      `hook_debug.log` showing the plugin's own `register()` call but never a single
      `FIRED` line. Root cause, per OpenClaw's own docs: an ACP-spawned harness "owns
      its provider login, model catalog, filesystem behavior, and native tools" - so
      Claude's own Bash/WebSearch tools execute entirely inside that external process,
      invisible to OpenClaw's plugin hook layer. **The exact same dead-end shape as
      gotcha #34's Devin-IDE ACP investigation**, just for OpenClaw+Claude instead of
      Devin - discovered here specifically because this project's own "verify before
      building" discipline caught it via a real adversarial test rather than assuming
      the hook worked from docs alone.
    - `before_tool_call` would likely still gate OpenClaw's own built-in agent loop
      (its native `web_search`/`memory_search`/etc. tool set) if configured with a
      direct provider API key instead of a CLI backend - genuinely unconfirmed, no key
      was available in this session to test it, and acquiring one is a real decision
      (cost, provider choice) not made unilaterally.
    - Presented three options to the user (get an API key and verify the native-tool
      path; ship knowing the gate only covers OpenClaw's own built-in tools, not any
      CLI-backend-delegated coding work; or drop it entirely) - **user chose to drop
      it**, the same call made for Antigravity (gotcha #29) when a fundamental gap
      showed up in exactly the case people would actually use. No AethelHook repo files
      were touched - the entire investigation happened against a standalone OpenClaw
      install (global npm package) and its own `~\.openclaw\openclaw.json`/workspace,
      cleaned up (test gateway process killed) at the end of the session. OpenClaw
      itself was left installed on this dev machine (harmless, standalone) in case a
      future session revisits this with a direct API key.
38. **`origin/main`'s entire git history had been silently rewritten and force-pushed at
    some point to purge `app/` from every commit, including the very first one, and this
    local clone was never resynced - discovered only when a routine `git push` failed
    (2026-08-03), while pushing weeks of already-completed but never-committed work
    (Gemini CLI/Copilot CLI/Devin CLI integration, CLAUDE.md updates).** `git push origin
    main` was rejected as non-fast-forward. `git merge-base main origin/main` returned
    completely empty - no common ancestor at all - despite `git log --oneline` on both
    branches showing an *identical sequence of commit messages* up to a point, just under
    different hashes. Confirmed via `git ls-tree -r origin/main-initial-commit-hash` that
    origin's very first commit already had zero files under `app/` - proof the entire
    history was rewritten (most likely a `git filter-repo --path app/ --invert-paths` or
    BFG pass run on a separate clone and force-pushed), not just a recent amend. This
    matches a *different*, incomplete attempt at the same underlying goal found staged-
    but-uncommitted in this local clone at the start of the same session (a plain
    `.gitignore` entry + `git rm --cached app/`, a going-forward-only untrack) - two
    unrelated attempts to solve "keep the Android source out of the public repo," done by
    different sessions/machines with no shared memory of each other, leaving this clone on
    stale pre-purge history. Confirmed the rewritten remote had also never gotten the
    `.gitignore` line added (the purge tool stripped history but didn't touch tracked
    files going forward). **Do not force-push the stale local branch over this** - that
    would silently resurrect `app/`'s full history on the public repo, undoing the
    original purge. Fix instead: create a fresh branch from `origin/main`, cherry-pick
    only the local-only new commits onto it (a commit that deletes already-absent files
    just no-ops cleanly on the delete, any real content changes like the missing
    `.gitignore` line still apply), diff the rebuilt tip against the original local tip to
    confirm they're content-identical, then fast-forward `main` onto it and push normally
    - a real Google Play developer account was pending final review the same week, so
    getting this wrong publicly was not an acceptable risk to gamble on. Lesson for future
    sessions: `git merge-base` returning nothing combined with matching commit-message
    sequences at different hashes is the signature of a full history rewrite happening
    upstream, not genuine divergence - treat it as "replay my commits on their history,"
    never as "force my history onto theirs."
39. **A real tester (mid closed-testing) reported AethelHook crashing via a WhatsApp
    message and a Samsung "Device care" battery-management screenshot, not through
    any of the app's own reporting channels - root-caused via Play Console's own
    Android Vitals, not guesswork.** The real stack trace showed
    `android.app.ForegroundServiceStartNotAllowedException` thrown from inside
    `AethelHookWebSocketService.onCreate()`'s unconditional `startForeground()` call
    (line 40), wrapped in a `RuntimeException` from `ActivityThread.
    handleCreateService` - i.e. the OS itself creating the service, not a bug in any
    call site AethelHook controls. Confirmed every real `.start()` caller
    (`BootReceiver`'s `BOOT_COMPLETED` receiver, `MainActivity`'s gateway toggles) is
    already in one of Android's documented background-foreground-service-start
    exemptions - the actual trigger is `onStartCommand`'s `START_STICKY` return value.
    When the OS kills the service's process (confirmed on two independent real
    devices: a Redmi A3 per Vitals' own crash sample, and a Samsung A24 per this
    tester's own report - "Affected users: 2" in Vitals matches exactly), the sticky
    contract auto-recreates it later, calling `onCreate()` -> `startForeground()`
    again - and that automatic system-restart is explicitly NOT an exempted reason
    under Android 12+'s foreground-service-launch restrictions, so it throws instead
    of quietly resuming. **Not manufacturer-specific** despite hitting two different
    OEMs' aggressive battery-management features (Samsung's "Device Care", presumably
    Xiaomi's own on the Redmi) - it's a general platform restriction any sufficiently
    aggressive background-kill policy on any OEM will eventually trigger. Fix: wrapped
    the `startForeground()` call in `try`/`catch (e: Exception)` - `Log.w`s the
    rejection and calls `stopSelf()` instead of crashing, matching the existing
    catch-and-warn convention already used for `AethelHookWebSocket.kt`'s own
    `NetworkCallback` registration. Deliberately left `onStartCommand`'s
    `START_STICKY` return value unchanged - a killed service that can't legally
    auto-restart now just quietly stays down instead of crash-looping, and restarts
    cleanly next time an exempt trigger fires (app opened, reboot, a gateway toggle)
    rather than needing a separate resilience mechanism (e.g. WorkManager-scheduled
    restarts) built out for this. Verified via a clean `compileDebugKotlin` before
    shipping. Shipped as Android `versionCode` 7 -> 8, `versionName` "1.3.0" ->
    "1.3.1" - signed release AAB (`app\build\outputs\bundle\release\app-release.aab`,
    confirmed signed via `META-INF/AETHELHO.RSA`/`.SF` presence via `bundleRelease`),
    uploaded to the Closed testing - Alpha track by the user directly through the
    Play Console web UI - confirmed via grep that no Play Publishing API,
    service-account credentials, or `fastlane` config exist anywhere in this repo, so
    every Play Console interaction in this project (this session included) happens by
    hand, not through any automation this session's tools can drive directly.
    **Also surfaced two small process gaps while triaging this, not code bugs, not
    yet acted on**: (1) the in-app "Report an Issue" button (`MainActivity.kt`,
    shipped 2026-07-31) points at GitHub Issues, real friction for a non-technical
    tester - this tester's actual first instinct was WhatsApp instead. Play Console's
    own per-track "Feedback URL or email address" field was already set to
    `aethelst8@gmail.com` (confirmed via a live screenshot of the Closed testing -
    Alpha track settings), a lower-friction channel Google surfaces directly to
    enrolled testers that the in-app button doesn't currently point at - worth
    reconciling the two if this keeps happening. (2) the
    `aethelhook-testing@googlegroups.com` group is shared across both AethelHook and
    sastownhub testers, so a fix-announcement post to it needs to explicitly scope
    which app it's about - done here by leading the post with "(this one doesn't
    affect sastownhub)".
40. **A `Column` inside a `Row(horizontalArrangement = Arrangement.SpaceBetween)` with
    no `Modifier.weight(1f)` doesn't wrap its `Text` - it just overflows past whatever
    sits next to it (here, a `Switch`).** Without a weight, the `Column` measures at
    its own unconstrained intrinsic width, so `Text` never hits a width to wrap
    against - it's not a "wraps but looks bad" bug, the text genuinely renders past/
    under the sibling. Every one of `MainActivity.kt`'s six gateway-toggle rows
    (Claude/Codex/OpenCode/Gemini/Copilot/Devin CLI) has this same missing modifier -
    it only ever surfaced for Devin CLI's row because "Tool calls route to phone
    (terminal CLI only)" is the one subtitle long enough to actually overflow; the
    other five's shorter strings happened to fit. Reported by a real closed-testing
    tester (Nobelstate, 2026-08-07) via email with screenshots, not found via code
    review. Fixed by adding `Modifier.weight(1f).padding(end = 12.dp)` to that one
    `Column` (Devin CLI's row only, matching the reported bug's scope - the other five
    rows are left as-is unless a future subtitle string grows long enough to hit the
    same trap). Verified via `compileDebugKotlin`, then a real debug-APK
    uninstall/reinstall on the dev phone (required a fresh QR re-pair, same signing-key
    gotcha as every prior debug/release swap) - user confirmed "its perfect."
41. **Every gateway toggle row's "Active" label was driven purely by the local
    on/off preference, not by whether the phone had actually connected to the PC** -
    reported by a real closed-testing tester recruited via the r/LookWhatTheyBuilt
    Reddit post (2026-08-10), on a completely unpaired first run: the hero card banner
    correctly read "Gateway Offline" in red, but every row below it (Claude Code,
    Codex, OpenCode, Gemini) still read "- Active / Tool calls route to phone" with
    the toggle on, since each row's text only ever checked `xGatewayEnabled` (the
    saved preference), never `apiStatus` (the real WebSocket connection state) -
    exactly the kind of state-mismatch this project's own security/accuracy
    discipline exists to catch, just missed here because the two signals were never
    cross-checked when the rows were first written across gotchas #27/#28/#32/#33/#34.
    A false "Active" reading on first run is genuinely confusing (the tester spent a
    minute wondering if pairing had silently already happened). Same report also
    flagged the hero card's status subtitle (e.g. "Open Settings - connect to the
    same Wi-Fi as your PC to auto-discover") truncating mid-sentence at `maxLines = 1`
    - exactly the sentence telling a new tester what to do next. Fixes, applied to all
    six toggle rows identically (not abstracted into a shared composable, matching
    this project's existing preference for direct repetition over premature
    abstraction across these rows - see gotcha #40): each row's title now reads
    "Active" only when `enabled && apiStatus == true`, "Waiting" when enabled but not
    yet connected (dimmed to `c.textSecondary`), "Inactive" when off; subtitle mirrors
    the same three states ("Tool calls route to phone" / "Waiting for gateway to
    connect" / "Approve dialogs in ..."). Status subtitle `maxLines` bumped 1 -> 2 so
    the truncated sentence now wraps and reads in full. Verified via a clean
    `compileDebugKotlin` only - no on-device install this session (would have
    required uninstalling the tester's current build and a fresh QR/Windows-Hello
    re-pair on the dev phone mid-testing-period), so this shipped straight to a
    signed release build instead. Shipped as Android `versionCode` 10 -> 11,
    `versionName` "1.3.3" -> "1.3.4" - signed release AAB
    (`app\build\outputs\bundle\release\app-release.aab`, confirmed signed via
    `META-INF/AETHELHO.RSA`/`.SF` presence via `bundleRelease`), uploaded to Play
    Console by the user directly (same manual-only workflow as every prior release).
    Tester reply sent by the user directly via Reddit DM (the tester's original
    report arrived that way too, not through the in-app "Report an Issue" button or
    the Play Console feedback address) - a third distinct report channel alongside
    WhatsApp (gotcha #39) and email (gotcha #40), reinforcing that real testers reach
    for whatever channel is already open, not necessarily the ones this project
    designed for. A broader fix-announcement was also drafted for
    `aethelhook-testing@googlegroups.com`, scoped to AethelHook only per the gotcha
    #39 convention (the group is shared with sastownhub testers), asking testers to
    update to v1.3.4 - not yet confirmed posted.
42. **`sendTestEvent` ("Send Test Ping" on the Dashboard) threw a raw OkHttp exception
    straight into the UI instead of a friendly message, when tapped before pairing.**
    Reported by a real closed-testing tester (2026-08-12) via a screenshot:
    "Something went wrong sending that: Expected URL scheme 'http' or 'https' but no
    scheme was found for /hook/...". Root cause: `AppPrefs.getApiUrl(ctx)` defaults to
    `""` until pairing completes (`AppPrefs.kt:152-154`), and `sendTestEvent` built the
    request URL as `"$baseUrl/hook/event"` with no upfront blank check - OkHttp's
    `Request.Builder().url()` throws `IllegalArgumentException` on the resulting
    schemeless string (`/hook/event`), which `friendlyNetworkError`'s catch-all `else`
    branch dumped verbatim (`e.message`) instead of translating it. Fix: `sendTestEvent`
    (`MainActivity.kt`) now checks `baseUrl.isBlank()` before attempting the request and
    returns "Not paired yet - go to Settings and tap 'Scan QR to Pair' first." directly;
    `friendlyNetworkError` (`NetworkErrors.kt`) also gained an `IllegalArgumentException`
    case with the same message as a defensive fallback, since every other
    `AppPrefs.getApiUrl(ctx)` call site (`fetchKnownProjects`/`fetchGitStatus`/
    `fetchTokenUsage`/`sendPromptToApi` in `SessionActivity.kt`) has the identical
    blank-URL exposure and none of them check first either.
    - **Same report also asked for a welcome page, then a user guide right after it** -
      added as a first-launch onboarding flow (`OnboardingScreen`/`WelcomePage`/
      `GuidePage` in `MainActivity.kt`), gated by a new
      `AppPrefs.getHasSeenWelcome`/`setHasSeenWelcome` flag (there was no "show once"
      mechanism in `AppPrefs` before this - every other stored flag is a persistent
      setting, not a one-time marker). Welcome page explains what AethelHook does in
      plain language; the guide page that follows is 4 numbered steps (install on PC,
      pair via QR/Windows Hello, approve from notifications, try Send Test Ping -
      explicitly noting it only works after pairing, closing the loop on the bug
      above). Rendered in `AethelHookApp` in place of the normal tab content (a new
      `showOnboarding` state gate around the existing `AnimatedContent`/`FloatingPillNav`
      block), not a `Dialog` overlay like `SummaryPopup` - this is a full first-run
      flow, not a dismissable card. Both pages have a "Skip" link. Also added a "View
      Welcome Guide" `GlassButton` to Settings (`onShowGuide` callback threaded from
      `AethelHookApp`) so anyone who already dismissed it, or wants to re-read the
      pairing steps, can reopen it anytime - a one-time-only view with no way back would
      have left this same tester's own request half-answered.
    - Verified via `compileDebugKotlin` + `lintDebug` only, no on-device install - per
      explicit user choice, shipped straight to a signed release to avoid disrupting
      the ongoing closed-testing period's pairing on the dev phone (same precedent as
      the 2026-08-10 fix). Shipped as Android `versionCode` 11 -> 12, `versionName`
      "1.3.4" -> "1.3.5" - signed release AAB
      (`app\build\outputs\bundle\release\app-release.aab`, confirmed signed via
      `META-INF/AETHELHO.RSA`/`.SF` presence via `bundleRelease`), en-GB release notes
      drafted. Upload to Play Console left to the user (same manual-only workflow as
      every prior release) - not yet confirmed uploaded, and no tester reply sent yet.
43. **`AethelHook.Tray.csproj` references both WPF and WinForms, so plain `Button`/
    `Clipboard` are ambiguous** between `System.Windows.Controls.Button`/
    `System.Windows.Clipboard` and their `System.Windows.Forms` namesakes - compiler
    error CS0104, hit while adding the "PowerShell Commands" dialog (a new
    `PowerShellCommandsWindow` with per-row Copy buttons, see the 2026-08-23 status
    entry below). Same class of ambiguity `MainWindow.xaml.cs` already works around
    for `MessageBox`/`Brush`/`Brushes` via explicit `using X = System.Windows.X;`
    aliases - any new Tray window that touches `Button`, `Clipboard`, or similar
    WPF/WinForms-overlapping types needs the same alias treatment.
    **Separately, verifying a new Tray window visually hit the app's own
    single-instance `Mutex`** (`"Global\\AethelHook.Tray.SingleInstance"`,
    `App.xaml.cs`) - a freshly-built debug exe launched while the live production
    Tray is running just silently no-ops (`Shutdown()` immediately, no new window,
    no error). The app also only ever shows `MainWindow` via a real tray-icon click
    or its context menu, never automatically on startup, which makes a debug build
    hard to inspect headlessly - automating a genuine click on a Windows
    notification-area icon via UI Automation proved too fragile to get working
    (tried searching `ToolbarWindow32` directly and via the "Show hidden icons"
    overflow chevron; neither located the icon). Working pattern for next time:
    stop the live production Tray process, launch the debug build (now the sole
    Mutex holder), optionally add a **temporary** `ShowMainWindow();` call at the
    end of `OnStartup` to force the window open without needing a real click,
    verify, then explicitly revert that temporary line (confirm via `git diff
    --stat App.xaml.cs` showing no changes) before rebuilding for real and
    restarting the production Tray from `C:\Program Files\AethelHook\Tray\
    AethelHook.Tray.exe` - safe to stop/restart since Tray is only the interactive
    status/control UI, not the approval-routing engine (that's the separate
    `AethelHook.API` Windows Service, unaffected either way).
44. **AGP 9.2.1's `optimization { enable = true }` DSL (`app/build.gradle.kts`) does
    not work standalone - it throws `Cannot use optimization.enable=true without
    setting android.r8.gradual.support flag` at configure time.** Needed to
    enable R8 shrinking/optimization/obfuscation for real (see the 2026-08-26
    Google Play "app quality requirements" status entry below - a new Play policy
    requires >=25% DEX-optimization coverage, enforced Feb 2027). Fix: added
    `android.r8.gradual.support=true` to `gradle.properties` (an experimental flag
    per Gradle's own warning on every build, expected to become the unconditional
    default in a future AGP release). Also hit one real, expected-shape issue once
    R8 actually ran: `androidx.security:security-crypto` pulls in Google Tink,
    which references `com.google.errorprone.annotations.*` (compile-time-only
    annotations, never loaded at runtime) - R8 flags these as "missing classes"
    and refuses to proceed until suppressed. Fix was exactly what R8 itself
    generates at `app/build/outputs/mapping/release/missing_rules.txt` - 4
    `-dontwarn com.google.errorprone.annotations.*` lines added to
    `app/src/main/keepRules/rules.keep` (AGP 9's replacement for a
    `proguard-rules.pro` file referenced via `proguardFiles` - keep rules now live
    in a `src/<variant>/keepRules/*.keep` source set instead). Verified via a full
    `assembleRelease` + `lintRelease` pass (lint clean, no new issues) and
    `apksigner verify --print-certs` confirming the output is still correctly
    V2-signed with the real release cert (`CN=AethelHook, OU=AethelSt8`) - R8
    doesn't touch signing, but worth confirming after a build-config change like
    this. **Live-verified the next day (2026-08-27)**: versionCode bumped to 14
    (`versionName` "1.3.7"), a signed release AAB built via `bundleRelease`
    (`jarsigner -verify` -> "jar verified.", every entry signed by the real
    release cert, `BUNDLE-METADATA/com.android.tools.build.obfuscation/
    proguard.map` present confirming R8's mapping file actually shipped this
    time), then a plain `adb install` of the signed release APK onto the dev
    phone (it happened to have no prior install at all, so no debug/release
    signing-key-mismatch uninstall was needed this time) - user re-paired via QR
    and confirmed the app "works as per usual," no visible regression anywhere
    from shrinking/obfuscation being on for the first time ever on this codebase.
    kotlinx.serialization's compile-time-generated serializers (not reflection)
    were the reasoning for why this was expected to be safe, and that held.
45. **A design letting phone-initiated Session Access prompts "join" the SAME
    conversation as a live interactive Claude Code session (instead of always
    using a separate phone-only thread) was live-tested 2026-08-27 and found to
    still lose data via a silent fork, even when correctly detected as idle -
    replaced with a context-injection design instead of trying to fix the
    underlying mechanism.** Motivation: the user wanted a phone message to have
    the same context as whatever had been discussed in the IDE, without needing
    true two-way parallelism with it. First design (picked up from a prior
    session's handoff): track a project's real interactive `session_id`
    (captured from the Stop hook's stdin) plus a busy/idle flag (true during
    PreToolUse/AskUserQuestion/ExitPlanMode, false on Stop), and have
    `/hook/send-prompt` `--resume` that real session_id instead of the separate
    phone-only `ProjectSessions` thread whenever it was known and idle. Built,
    deployed, and live-tested against this repo's own interactive session: the
    idle-detection gate worked exactly as designed (correct project, correct
    session_id, correctly detected idle) - but a real fork still happened.
    Traced through the actual transcript file's `parentUuid` chain (not
    guessed): the headless `--resume` anchored on a node from partway through
    the interactive session's immediately preceding turn (right after one of
    that turn's own PreToolUse hook_success attachments), not the true final
    leaf written minutes later once that turn actually finished. Root cause:
    the transcript file's own `{"type":"last-prompt","leafUuid":...}`
    bookkeeping (which `--resume` appears to anchor on, not literally the last
    line in the file) stopped advancing partway through that turn and never got
    a fresh entry until the NEXT top-level user prompt arrived - so even a turn
    that had been idle for 4+ minutes (Stop already fired, busy flag correctly
    false) could still have this bookkeeping lagging behind by a whole turn's
    worth of work. Net effect: the phone's real reply became an orphaned
    branch, invisible to any future resume, with no error or warning anywhere -
    the same class of silent data loss already proven possible by an earlier
    concurrent-race test this design was meant to guard against, just triggered
    by staleness during a genuinely idle window instead of a literal race. A
    dedicated research pass (via the `claude-code-guide` agent) confirmed
    there's no supported, documented way to close this gap:
    `CLAUDE_CODE_MESSAGING_SOCKET` (the `messagingSocketPath`/`peerFeatures`/
    `notify_idle` fields found live in `~/.claude/sessions/<pid>.json`) is
    real, documented, cross-session IPC, but delivers only an advisory
    plain-text message to another RUNNING Claude session - it can't inject
    real conversation history, can't force a reply, and can't be polled by an
    external non-Claude process. The transcript's own `last-prompt`/`leafUuid`
    anchor logic is undocumented internal state, not something to build a
    reliability guarantee on top of. Separately confirmed live (via the user's
    own open IDE window) that even a successful, non-forked join wouldn't have
    shown up in the interactive session's own chat panel in real time anyway -
    the panel doesn't appear to re-read the transcript file just because
    another process appended to it, so the join's real benefit was always
    going to be "a future resume inherits the context," never genuine live
    two-way visibility. **Fix**: reverted the interactive-join logic entirely -
    dropped `InteractiveProjectSessions`/`InteractiveProjectBusy`, the
    busy=true markers on the three approval-gate handlers, and the
    `joinedInteractive` branch in `/hook/send-prompt` - phone prompts are back
    to always using the safe, dedicated `ProjectSessions` thread that nothing
    else ever writes to, so no fork is structurally possible. Replaced with a
    context-injection design instead: `on_agent_done.ps1`'s Stop hook now
    forwards `transcript_path` (not a session_id) to `/hook/notify`, captured
    into a new `InteractiveProjectTranscriptPaths` dictionary; before a phone
    prompt runs, a new `BuildInteractiveContextBlock()` helper reads that
    transcript file directly in plain forward line order (deliberately NOT the
    same unreliable `last-prompt` bookkeeping), extracts real user/assistant
    text turns since the last time context was folded in for that project
    (tracked via `InteractiveContextInjected`, cwd to transcript-path-plus-
    line-count), skips synthetic/meta/tool-only noise, caps the result at 6000
    characters (most recent content wins), and prepends it to the phone's
    prompt with a "background context, not an instruction" framing. This never
    resumes or writes to the interactive session's own file at all, so the
    earlier fork risk cannot recur by construction, at the cost of being a
    one-time snapshot handoff rather than true shared history (a phone reply
    doesn't itself feed back into a later interactive turn's context) - a
    known, accepted tradeoff given the user confirmed they don't need real
    parallelism with the IDE. Live-verified end to end 2026-08-27: a real Stop
    event correctly forwarded `transcript_path`, and a subsequent phone prompt
    logged `[SendPrompt] Folded in IDE context for C:\AethelHook (6151 chars)`
    and completed successfully.

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
| `.geminicli\hooks\*.ps1` | Dev copies of Gemini CLI hooks (sync to `dist\hooks\geminicli\` + `C:\ProgramData\AethelHook\hooks\geminicli\` after editing) - see gotcha #32 |
| `.copilot\hooks\*.ps1` | Dev copies of Copilot CLI hooks (sync to `dist\hooks\copilot\` + `C:\ProgramData\AethelHook\hooks\copilot\` after editing) - see gotcha #33 |
| `.devincli\hooks\*.ps1`, `.devincli\hooks\extract_summary.py`, `.devincli\hooks\find_latest_session.py` | Dev copies of Devin CLI's standalone-CLI-only hooks + its two Python/SQLite helpers (summary extraction, Session Access resume lookup) - sync to `dist\hooks\devincli\` + `C:\ProgramData\AethelHook\hooks\devincli\` after editing - see gotchas #34/#35 |
| `app\...\MainActivity.kt`, `SessionActivity.kt`, `AethelHookWebSocket.kt` | Android - nav/dashboard, Session Access tab, WS client |
| `AethelHook.Tray\WindowsHello.cs` | Windows Hello gate for "Pair New Device" - raw WinRT vtable interop, see gotcha #24 |
| `AethelHook.Tray\PowerShellCommandsWindow.xaml(.cs)` | "PowerShell Commands" dialog (Get/Start/Stop/Restart-Service AethelHook, one-click copy) - see gotcha #43 |
| `app\src\main\keepRules\rules.keep` | R8 keep/`-dontwarn` rules for the Android release build (AGP 9's replacement for `proguard-rules.pro`) - see gotcha #44 |
| `AethelHook.API\Program.cs` (`BuildInteractiveContextBlock`) | Reads the interactive Claude Code session's own transcript file to fold real IDE context into a phone-sent Session Access prompt - see gotcha #45 |

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

**As of 2026-08-27, later the same day (Session Access "join the live IDE session"
design replaced with a safer context-injection approach after live testing found a
real data-loss bug - deployed and verified on the dev machine, installer version
bumped, not yet rebuilt into a signed `.exe`):**

- **Full detail in gotcha #45 above.** Short version: picked up a handoff from an
  earlier session that had built (but not yet deployed) an idle-detection gate
  letting phone-initiated Session Access prompts `--resume` the SAME session_id as
  a live interactive Claude Code conversation, instead of a separate phone-only
  thread. The edit to `.claude\hooks\on_agent_done.ps1` needed for this had been
  blocked outright by the sensitive-file classifier in that prior session; this
  session's edit went through cleanly, the code was deployed via `install.ps1`, and
  then live-tested end to end against this repo's own interactive conversation.
- **The idle gate worked exactly as designed** (correct project, correct
  session_id, correctly detected idle via `[SendPrompt] Joining live interactive
  session for C:\AethelHook` in `api.log`) - but a real fork still happened.
  Traced through the actual transcript file's `parentUuid` chain (not guessed):
  the phone's headless `--resume` anchored on a node from partway through the
  PRECEDING interactive turn, not that turn's true final leaf, because Claude
  Code's own `last-prompt`/`leafUuid` resume-anchor bookkeeping in the transcript
  file doesn't advance continuously through a turn and had lagged behind by
  several minutes' worth of real, already-finished work.
- **A dedicated research pass (via the `claude-code-guide` agent) confirmed there's
  no supported, documented way to close this gap** - the only real cross-session
  IPC (`CLAUDE_CODE_MESSAGING_SOCKET`, found live in
  `~/.claude/sessions/<pid>.json`) is advisory-only and can't inject real
  conversation history or be safely polled by an external process. Also confirmed
  live (checking the user's own open IDE window) that even a non-forked join
  wouldn't have appeared there in real time anyway - the interactive panel doesn't
  seem to re-read the transcript file just because another process appended to it.
- **Replaced with a context-injection design**: phone prompts stay on their own
  dedicated, fork-proof `ProjectSessions` thread (never touched by anything else);
  before each phone-sent prompt, the API now reads the interactive session's own
  transcript file directly (plain forward line order, not the unreliable
  leaf-tracking) via a new `BuildInteractiveContextBlock()` helper, and prepends
  real recent IDE conversation content as background context, capped at 6000
  characters. This can't fork by construction, since it only ever reads that file,
  never writes to or resumes it - the tradeoff (explicitly accepted by the user) is
  that it's a one-time snapshot handoff, not true two-way shared history.
- **Live-verified end to end**: a real Stop event correctly forwarded the new
  `transcript_path` field (replacing the reverted `claude_session_id` field), and a
  subsequent phone prompt logged
  `[SendPrompt] Folded in IDE context for C:\AethelHook (6151 chars)` and completed
  successfully.
- **Windows installer `AppVersion` bumped 1.5 -> 1.6** for this change. Not yet
  rebuilt into a signed `AethelHook-Setup.exe` or re-uploaded to the GitHub
  release - only this dev machine's live service (via `install.ps1`) has the new
  code deployed so far.

**As of 2026-08-27 (R8-enabled release build finished, signed, sideload-tested,
and confirmed working - not yet uploaded to Play Console; one unrelated Play
Console advisory found and deliberately deferred):**

- **Continues directly from the 2026-08-26 entry below.** Bumped Android
  `versionCode` 13 -> 14, `versionName` "1.3.6" -> "1.3.7" (see gotcha #44's
  now-updated closing note for the full build/signature/install verification
  chain). Signed AAB is at `app\build\outputs\bundle\release\app-release.aab`,
  its R8 mapping file at `app\build\outputs\mapping\release\mapping.txt` (worth
  keeping alongside the AAB, or uploading to Play Console's own deobfuscation
  slot, so a future Android Vitals crash can be traced back to real class/method
  names). Draft release notes given to the user framed around "internal
  performance and build optimizations... no visible changes" - deliberately not
  the actual Play Store description update since this release has no
  user-facing feature.
- **Live-verified on the dev phone via a plain `adb install`, not driven by this
  session beyond that single install command** - per the project's own
  `feedback_no_autonomous_device_testing` rule, the actual manual test pass
  (re-pairing, exercising the app) was done by the user, not by this session
  tapping through screens itself.
- **Found a second, unrelated Play Console advisory while looking at the
  Production release dashboard**: "Remove resizability and orientation
  restrictions... to support large screen devices," flagged against the
  currently-live 1.3.6 release, pointing at
  `com.journeyapps.barcodescanner.CaptureActivity` (the zxing QR-scanner screen
  used for "Scan QR to Pair"). Traced to `AndroidManifest.xml`'s own
  `android:screenOrientation="portrait"` + `tools:replace="android:
  screenOrientation"` override on that activity - a deliberate existing choice
  (not an accidental library default, not documented anywhere in this file's
  history) locking the scanner to portrait regardless of device. This is
  advisory-only (no enforcement date shown, doesn't block publishing) and tied
  to Android 16 ignoring orientation/resizability restrictions on large-screen
  devices (foldables/tablets) going forward. **Explicitly deferred at the
  user's choice** rather than changed blind - removing the lock would need real
  on-device verification of how the camera-preview scanning UI actually behaves
  in landscape/on a tablet, which hasn't been done.
- **Not yet done**: the signed AAB hasn't been uploaded to Play Console (left to
  the user, same manual-only workflow as every prior release - no Publishing
  API/service-account/fastlane in this repo). No follow-up Android Vitals check
  yet to confirm the new DEX-optimization coverage actually registers. SasTownHub
  (the second AethelSt8 app) still hasn't been checked for the same R8 gap.
  The large-screen orientation advisory remains unaddressed by design, not by
  oversight.

**As of 2026-08-26 (Google Play's new app-quality-requirements email triaged, R8
enabled for the release build - not yet rebuilt to a signed AAB or uploaded):**

- **Google sent a real Play Console notice to the aethelst8 account announcing two
  new quality requirements**, confirmed via the actual announcement post (not
  guessed from the email alone): (1) memory/code optimization - Android Vitals
  thresholds on dynamic memory (RSS+swap) and bitmap memory, plus a requirement
  that apps hit >=25% DEX-optimization coverage (shrink/optimize/obfuscate via
  R8) - **enforced February 2027**, non-compliant apps get reduced Play
  visibility/publishing ability; (2) zero-tap sign-in via Android's Restore
  Credentials API for device migration - **enforced April 2027, games exempt**.
- **AethelHook was failing requirement (1) outright** - `app/build.gradle.kts`'s
  release build had R8 explicitly disabled (`optimization { enable = false }`,
  the AGP 9 DSL). Fixed - see gotcha #44 above for the two real build errors hit
  turning it on (a missing experimental Gradle flag, then Tink's error-prone
  annotation classes needing `-dontwarn` suppression). Verified via a clean
  `assembleRelease` + `lintRelease` (no new lint issues) and confirmed the output
  APK is still correctly V2-signed with the real release cert.
- **Requirement (2) almost certainly doesn't apply to AethelHook** - it has no
  account/sign-in concept at all (pairing is QR scan + Windows Hello, not
  identity-based auth), so there's no "sign-in state" for Restore Credentials to
  restore. Not building anything for this unless Google's exact qualification
  criteria (not yet published in detail) says otherwise.
- **Not yet done**: no functional regression testing of the now-shrunk/obfuscated
  release build on a real device - the user plans to sideload-test it manually
  before this goes anywhere near Play Console (per the project's own
  `feedback_no_autonomous_device_testing` rule, this session didn't drive the
  device itself). No version bump, no signed AAB rebuilt, nothing uploaded.
  SasTownHub (the second AethelSt8 app, separate repo) got the same Google email
  and hasn't been checked for the same R8/DEX-optimization gap yet.

**As of 2026-08-23 (AethelHook went LIVE on Google Play Production; Play Store
title optimized; website got Play Store cross-linking + platform icons; Tray app
gained a "PowerShell Commands" dialog, shipped as installer v1.5):**

- **Google Play production access was granted the same day it was applied for
  (2026-08-19 -> 2026-08-23), 3 days ahead of the up-to-7-day estimate.** Being
  granted access is a separate thing from actually being live, though - had to
  build/promote an actual Production release afterward. Found and removed a real
  leftover first: the 2026-08-09 daily 9am tester-reminder notification
  (`DailyReminderReceiver.kt`) was explicitly testing-only and never removed -
  would have shipped a permanent daily nudge to every real production user.
  Removed the file and all 3 wiring sites (`MainActivity.onCreate`,
  `BootReceiver`, the manifest `<receiver>` entry), shipped as Android
  `versionCode` 12 -> 13, `versionName` "1.3.5" -> "1.3.6".
- **A real submission-status trap worth remembering for next time**: Play
  Console's Submission activity log showed "Published" for the first production
  submission attempt, but the real Play Store listing 404'd and the Dashboard's
  own "Create and publish a release" checklist still showed incomplete steps -
  the release actually needed an explicit "Preview and confirm the release"
  click that hadn't happened yet. A second submission genuinely cleared review
  and published (confirmed two independent ways: the Submission activity status
  flipping to "Published" for real, and a Google IARC "Live Rating Notice" email
  arriving the same day, which only fires once a content rating questionnaire
  actually goes live on a storefront). Lesson: don't trust the Submission
  activity log's status label alone - check Publishing overview's "Changes in
  review" section (empty means genuinely done) and, ideally, a side-channel
  signal like that email.
- **Play Store title optimized via the `app-store-optimization` skill's actual
  scripts, not just guessed**: the title had been just "AethelHook" (only 20% of
  the character field used, matching brand-name searches only). Real correction
  during this work: the skill's own docs claim a 50-character Google Play title
  limit, but the live Console UI enforced **30 characters** when a 39-char title
  was rejected outright - don't trust that skill's reference doc for current
  platform limits without checking the live UI first. Also caught a real mistake
  before shipping: `AethelHook: Approve ClaudeCode` (fit exactly at 30 chars)
  squashes "Claude Code" into one word, which breaks keyword matching entirely
  since search tokenizes on whitespace. Shipped title:
  `AethelHook: Claude & Codex` (26/30 chars). A real A/B test of the title was
  considered and rejected as infeasible: the account's actual traffic (~1.5
  visitors/day) would need ~144 months to reach statistical significance for a
  5% effect size - not worth revisiting until traffic is meaningfully higher.
- **aethelst8.com and this repo's README both still described the pre-Play-Store
  sideload flow** ("Android APK from GitHub Releases, expect an unknown-source
  warning, not on the Play Store yet") even after Play Store went live - fixed
  across `Download.jsx`, `Setup.jsx`, `GuideApprove.jsx`, and `README.md` to lead
  with the real Play Store listing, GitHub Releases APK kept only as a sideload
  fallback. Added a real "Get it on Google Play" badge to the README (Google's
  own official badge image) and a matching Play Store SVG icon (fetched from
  Simple Icons) next to the site's own download button, plus a Windows icon
  (Bootstrap Icons' MIT-licensed four-pane flag - Simple Icons has no plain
  Windows OS logo at all) on both the "Download for Windows" button and the
  hero's main CTA, the latter wrapping both platform icons in a row beneath the
  button label via a new scoped `.btn-stacked`/`.btn-icon-row` CSS pair. Also
  de-pilled the hero's "Free and open source" tag to plain white text per
  explicit design feedback.
- **Tray app gained a "PowerShell Commands" dialog** (see gotcha #43 for the two
  real technical gotchas hit building/verifying it) - one-click copy for the same
  4 service commands (`Get/Start/Stop/Restart-Service AethelHook`) the website's
  Troubleshooting section already documents, placed below the Gateway toggle.
  Shipped as installer `AppVersion` 1.4 -> 1.5, rebuilt via the documented
  `dotnet publish` (API + Tray) + `ISCC.exe` pipeline, uploaded to the existing
  `v1.0.0` GitHub release with `--clobber` (same fixed-filename/tag convention as
  every prior installer-only rebuild). Website's Troubleshooting section got one
  added sentence mentioning the new in-app copy button.
- **SasTownHub (the second AethelSt8 app) hit all 3 closed-testing gates the
  same day** but the user deliberately chose to wait a day before applying for
  its own production access, purely to avoid the appearance of spamming Google
  with back-to-back applications - not because of any known actual policy risk
  (what actually triggers spam-pattern rejections is clone/template app content,
  not submission cadence). Plan: apply 2026-08-24.
- **Found a substantial pre-existing gap while doing this work**: `CLAUDE.md`
  itself had 355 lines of real, accurate documentation (gotchas #39-42 and the
  2026-08-06 through 2026-08-19 status entries) sitting uncommitted in the
  working tree from an earlier session - committed separately this session
  (`7d0f549`) once confirmed legitimate. Worth checking `git status` on this
  file specifically at the start of a session if something documented here
  doesn't match `git log`'s own history.

**As of 2026-08-19 (applied for Google Play production access - all three closed-
test gates green, application submitted, awaiting Google's review):**

- **All three "Apply for production" gates on the Play Console dashboard turned
  green** (publish a closed testing release / 12 testers opted-in / run for at
  least 14 days) - the 14-day continuous-opt-in clock that started around
  2026-08-05 completed on schedule. Completed the 3-step "Apply for access to
  production" questionnaire (About your closed test / About your app / Your
  production readiness) and submitted it - **Google confirmed receipt** with an
  expected review time of "seven days or less, but may occasionally take
  longer," applied at 08:34 on 2026-08-19.
- **Answers were written to be specific and honest rather than generic
  boilerplate**, grounded in this file's own gotcha history rather than
  marketing language: recruitment via personal contacts + a public Reddit post +
  an open Google Group (explicitly no paid testing provider, after evaluating
  and declining one earlier in the project), and the real bugs found and fixed
  during closed testing (gotchas #39-42: a foreground-service crash, a UI
  text-overflow bug, a false "Active" status shown before pairing, a raw error
  message on first use). Per explicit user request, also noted that real users
  (not just closed-test testers) use AethelHook daily with their AI assistants
  and cite its convenience, reliability, and being free/open source.
- **Confirmed same day**: v1.3.5 (the Send Test Ping fix + onboarding flow, see
  the 2026-08-12 entry below) was in fact uploaded to the closed track before
  this application went in - the upload-pending flag from the 2026-08-12 entry
  below is resolved. Next real milestone is Google's review decision, expected
  around 2026-08-26.

**As of 2026-08-12 (Send Test Ping error message fixed, welcome page + user guide
added, shipped as Android v1.3.5/versionCode 12 - upload and tester reply still
pending):**

- **Full detail in gotcha #42 above.** Short version: a tester's screenshot showed a
  raw OkHttp exception ("Expected URL scheme 'http' or 'https'...") from tapping
  "Send Test Ping" before pairing - `apiUrl` defaults to `""` until paired, so the
  request URL had no scheme and the generic exception fell through unstranslated.
  The same report also asked for a welcome page, then a user guide right after it.
- **Fix**: `sendTestEvent` now checks for a blank URL up front and returns "Not
  paired yet - go to Settings and tap 'Scan QR to Pair' first." instead of hitting
  OkHttp at all; `friendlyNetworkError` also gained an `IllegalArgumentException`
  case with the same message as a defensive fallback for other call sites with the
  same exposure (`fetchKnownProjects`/`fetchGitStatus`/`fetchTokenUsage`/
  `sendPromptToApi`, none of which check first either - not fixed at the call-site
  level this session, only covered by the shared fallback).
- **Added a first-launch welcome page + 4-step user guide** (`OnboardingScreen` in
  `MainActivity.kt`), gated by a new one-time `AppPrefs` flag
  (`getHasSeenWelcome`/`setHasSeenWelcome` - no such "show once" mechanism existed
  before this). Replayable anytime via a new "View Welcome Guide" button in
  Settings, so dismissing it once doesn't lose it permanently.
- **Verified via `compileDebugKotlin` + `lintDebug` only** - no on-device install,
  shipped straight to a signed release per explicit user choice (same
  don't-disrupt-the-testing-phone precedent as 2026-08-10). Shipped as Android
  `versionCode` 11 -> 12, `versionName` "1.3.4" -> "1.3.5" - signed release AAB
  confirmed signed via `META-INF/AETHELHO.RSA`/`.SF` presence via `bundleRelease`.
  en-GB Play Console release notes drafted (under the 500-char limit).
- **Not yet done**: the AAB hasn't been uploaded to Play Console yet (left to the
  user, same manual-only workflow as every prior release); no tester reply sent;
  no real-device confirmation of either the error-message fix or the onboarding
  flow (compile/lint-verified only).

**As of 2026-08-10 (Dashboard "false Active" + truncated status-line bug fixed and
shipped, Android v1.3.4/versionCode 11 - tester reply and Google Group post drafted):**

- **Full detail in gotcha #41 above.** Short version: a tester recruited via the
  r/LookWhatTheyBuilt Reddit post reported that on an unpaired first run, every
  gateway toggle row (Claude Code, Codex, OpenCode, Gemini) read "- Active / Tool
  calls route to phone" the moment its switch was flipped on, even though the hero
  card above correctly showed "Gateway Offline" - a real false-positive since each
  row's label only ever checked the saved on/off preference, never the actual
  WebSocket connection state. Same report flagged the hero card's status subtitle
  truncating mid-sentence at `maxLines = 1`, cutting off the exact instruction
  telling a new tester what to do next.
- **Fix applied identically to all six toggle rows** (`MainActivity.kt`): title now
  reads "Active" only when the toggle is on AND `apiStatus == true`, "Waiting" when
  on but not yet connected (dimmed), "Inactive" when off - subtitle mirrors the same
  three states. Status subtitle `maxLines` bumped 1 -> 2 so it wraps instead of
  truncating. Verified via `compileDebugKotlin` only - no on-device install this
  session (would have forced a re-pair on the dev phone mid-testing-period), so this
  went straight to a signed release build.
- **Shipped as Android `versionCode` 10 -> 11, `versionName` "1.3.3" -> "1.3.4"** -
  signed release AAB (`app\build\outputs\bundle\release\app-release.aab`, confirmed
  signed via `META-INF/AETHELHO.RSA`/`.SF` presence via `bundleRelease`), uploaded to
  Play Console by the user directly.
- **Tester reply sent via Reddit DM** (matching the channel the original report came
  in on, not email or the in-app report button). A broader fix-announcement for
  `aethelhook-testing@googlegroups.com` was drafted, scoped to AethelHook only (the
  group is shared with sastownhub testers, same convention as gotcha #39/#40), asking
  testers to update to v1.3.4 - **not yet confirmed posted**.
- **Not yet done**: no real-device confirmation of the fix (compile-verified only);
  the Google Group post is drafted but unposted; the daily tester-reminder
  notification from 2026-08-09 (below) still hasn't been confirmed firing on a real
  device either.

**As of 2026-08-09 (daily tester-reminder notification added, testing-only, shipped as
Android v1.3.3/versionCode 10 - signed AAB built, not yet uploaded to Play Console):**

- **Added a hardcoded-on daily local notification** ("Got a minute for AethelHook?" /
  9:00 AM local time) to nudge closed-testing testers to open the app briefly - the
  12-tester/14-day Play Console requirement needs real opens, not just an install. New
  `DailyReminderReceiver.kt` (`DailyReminderScheduler` object + a non-exported
  `BroadcastReceiver`) uses an inexact `AlarmManager.setAndAllowWhileIdle()` alarm (no
  `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM` needed - already-granted `POST_NOTIFICATIONS`
  from `MainActivity`'s existing request covers it) that self-reschedules for the next
  day each time it fires. Armed from `MainActivity.onCreate` (idempotent - just
  recomputes "next 9 AM" each call) and re-armed in `BootReceiver.onReceive`
  unconditionally (alarms don't survive reboot, and this shouldn't depend on any
  gateway-enabled flag). Tapping the notification just opens `MainActivity` normally
  (no `summary_title`/`summary_body` extras, so it doesn't trigger the glass
  summary-popup path). New 4th notification channel (`"aethelhook_reminder"`,
  `IMPORTANCE_DEFAULT`), following the exact create-channel-inline-before-notify
  pattern already used by the other 3 channels in `AethelHookWebSocketService.kt`/
  `AethelHookWebSocket.kt`.
- **Deliberately scoped as testing-only, not a permanent feature** (explicit user
  choice) - no Settings toggle, hardcoded on. `DailyReminderReceiver.kt`'s header
  comment spells out the 3 removal sites (the file itself, its manifest `<receiver>`
  entry, and the two `scheduleNext()` call sites) for whoever revisits this before a
  Production release.
- **Verified via compile + lint + a real signed release build, not a device install** -
  the user explicitly declined an on-device install this session (updating via Play
  Store instead), so this was checked via `compileDebugKotlin` (clean), `lintDebug`
  (clean, zero warnings on the new files), and `bundleRelease` (signed - confirmed
  `META-INF/AETHELHO.RSA`/`.SF` present in the AAB, same as every prior release).
  Version bumped `versionCode` 9 -> 10, `versionName` "1.3.2" -> "1.3.3".
- **Not yet done**: the signed AAB
  (`app\build\outputs\bundle\release\app-release.aab`) hasn't been uploaded to Play
  Console yet - that's on the user, same manual-only Play Console workflow as every
  prior release (no Publishing API/service-account/fastlane in this repo). Also not
  done: no real-device confirmation that the notification actually fires at 9 AM
  (compile/lint/signature-verified only, per explicit user request to skip a device
  install this session).

**As of 2026-08-07 (Devin CLI dashboard text-wrap bug fixed and shipped, Android
v1.3.2/versionCode 9, tester and Google Group communications sent):**

- **Full detail in gotcha #40 above.** Short version: the same tester from gotcha #39
  (Nobelstate) emailed a UI change request with screenshots - the "Devin CLI - Active"
  subtitle text was getting cut off/overflowing behind the toggle switch instead of
  wrapping. Root cause: `MainActivity.kt`'s Devin CLI toggle row `Column` had no
  `Modifier.weight(1f)`, so it measured at its unconstrained width and the `Text`
  never got a width to wrap against - a real, general Compose trap present in all six
  gateway-toggle rows, just only long enough to trigger visibly for this one row's
  subtitle.
- **Fix scoped to the reported row only** (Devin CLI's `Column` gained
  `Modifier.weight(1f).padding(end = 12.dp)`) - the other five toggle rows have the
  identical missing modifier but no currently-long-enough subtitle to expose it, left
  alone per this project's "don't refactor beyond what's asked" convention.
- **Verified via a real device round-trip, not just a compile check**: built
  `assembleDebug`, uninstalled the existing app via `adb uninstall` (per explicit user
  request) and reinstalled the new debug APK via `adb install -r` (required a fresh QR
  re-pair, same signing-key-mismatch gotcha noted throughout this file), user confirmed
  the subtitle now wraps correctly on-device.
- **Shipped as Android `versionCode` 8 -> 9, `versionName` "1.3.1" -> "1.3.2"** - signed
  release AAB (`app\build\outputs\bundle\release\app-release.aab`, confirmed signed via
  `META-INF/AETHELHO.RSA`/`.SF` presence via `bundleRelease`), uploaded to Play Console
  by the user directly (same manual-only Play Console workflow noted in gotcha #39 -
  no Publishing API/service-account/fastlane in this repo).
- **Tester + Google Group communication sent this session**: a follow-up email to the
  reporting tester (nobelstate302@gmail.com) confirming the fix is live in v1.3.2, plus
  a new fix-announcement thread on `aethelhook-testing@googlegroups.com` - again scoped
  explicitly to AethelHook only in the subject/body, same convention as gotcha #39,
  since that group is shared with sastownhub testers.

**As of 2026-08-06 (tester-reported crash root-caused via Play Vitals and fixed, shipped
as Android v1.3.1/versionCode 8, tester and Google Group communications sent):**

- **Full detail in gotcha #39 above.** Short version: a closed-testing tester reported
  AethelHook crashing via WhatsApp and a Samsung "Device care" screenshot, not through
  any in-app or Play Console channel. Root-caused via Play Console's own Android
  Vitals (real stack trace, not guesswork):
  `android.app.ForegroundServiceStartNotAllowedException` thrown from
  `AethelHookWebSocketService.onCreate()`'s `startForeground()` call, triggered by
  Android 12+ blocking the OS's own automatic `START_STICKY` restart of a killed
  service while backgrounded - confirmed on two independent real devices (a Redmi A3
  per Vitals' sample, a Samsung A24 per this tester), not manufacturer-specific.
- **Fix**: wrapped `startForeground()` in `try`/`catch`, `Log.w`s the rejection and
  calls `stopSelf()` instead of crashing. `START_STICKY` left unchanged - a killed
  service now just stays down until the next exempt trigger (app open, reboot, gateway
  toggle) instead of crash-looping. Verified via a clean `compileDebugKotlin` before
  shipping.
- **Shipped as Android `versionCode` 8 / `versionName` "1.3.1"** - signed release AAB
  built via `bundleRelease`, signature confirmed via `META-INF/AETHELHO.RSA`/`.SF`
  presence, uploaded to the Closed testing - Alpha track by the user directly through
  the Play Console web UI (confirmed via grep: no Play Publishing API, service-account
  credentials, or `fastlane` config exist anywhere in this repo - every Play Console
  interaction is manual).
- **Tester communication handled end to end this session**: drafted and sent (via the
  user) an email reply to the reporting tester through the Closed testing track's own
  configured feedback address (`aethelst8@gmail.com`), plus a broader fix-announcement
  post to the shared `aethelhook-testing@googlegroups.com` group (22 members, shared
  with sastownhub - explicitly scoped the post to AethelHook only to avoid confusing
  the shared audience). Play Console `en-GB` release notes drafted and confirmed
  pasted in.
- **Real gap surfaced, not yet acted on**: the in-app "Report an Issue" button
  (Settings screen) opens GitHub Issues, real friction for a non-technical tester (this
  one reached for WhatsApp instead) - Play Console's own per-track feedback field is
  already set to a plain email address that would've been easier for this tester to
  reach. Worth reconciling if this keeps happening.
- **Not yet done**: no follow-up Android Vitals check yet to confirm the crash rate
  actually drops post-update - needs a few days of real usage across both affected
  testers' devices. Also unconfirmed: whether uploading a new release mid-testing-period
  affects the ongoing 14-day/12-tester closed-testing clock (see the 2026-08-04 entry
  below) - not verified either way this session.

**As of 2026-08-04 (weeks of uncommitted work finally pushed to GitHub after a history-
rewrite discovery, website updated for the 6-agent lineup, and the first Google Play
Store submission - closed testing published and live-verified):**

- **GitHub was badly out of sync going into this session** - everything from gotcha #32
  onward (Gemini CLI, Copilot CLI, Devin CLI, the two Android UI fixes, the OpenClaw
  investigation) had only ever existed in this working tree, never committed. Also found
  a staged-but-uncommitted `.gitignore`/`git rm --cached app/` from an earlier attempt to
  stop tracking the Android app source. Sorted into four clean commits (untrack `app/`,
  the three-agent backend/hooks work, the CLAUDE.md catch-up, README/CONTRIBUTING/
  SECURITY updated for 6 agents) - then hit the real surprise on push: **see gotcha #38**
  for the full story of `origin/main`'s entire history having been separately rewritten
  and force-pushed to purge `app/` from every commit, with this clone never resynced.
  Resolved by replaying the new local commits onto GitHub's actual current history
  instead of force-pushing the stale copy back. All pushed and confirmed clean
  (`git status` matches `origin/main` exactly) as of this writing.
- **aethelst8.com updated for the same 6-agent lineup**: Hero/Features/Setup copy, the
  SEO title/meta/OG tags, a regenerated OG image, the Guides index, and the main setup
  guide got real per-agent subsections for Gemini CLI (npm install, folder-trust caveat)
  and Devin CLI (standalone-CLI-only install, explicitly distinct from the non-hook-
  capable Devin IDE) - mirroring the accuracy discipline this repo already applies to its
  own docs. Also replaced the Demo section's phone dashboard screenshot, which still
  showed the original 3-agent, pre-redesign UI, with a current one showing all six
  toggles, once the user provided a fresh capture.
- **First Google Play Store submission, done live in this session.** Full build/test/
  submission flow, not just prep:
  - Built the signed release AAB (`gradlew bundleRelease`, versionCode 7/1.3.0, signed
    with the existing `aethelhook-release.jks` via `keystore.properties`) - confirmed via
    the bundle's own `META-INF/AETHELHO.RSA`/`.SF` signature files, not just a successful
    Gradle exit code.
  - **Play Store screenshots and the feature graphic were generated with a small
    Python/Pillow script** (`gen_playstore_screenshots.py`, kept in this session's
    scratchpad, not the repo - reusable, no external tool/account/watermark), compositing
    the user's real phone/PC screenshots into a flat device-frame mockup with a headline
    and subtitle, white background with an indigo accent to match the site's own brand
    colors. Iterated twice on user feedback (bigger fonts, then a switch from dark to
    white background) and added two more slides (notification popup, full decision
    screen) after the first pass. Same script/technique was then reused the same day for
    a second AethelSt8 app, sastownhub ("SasTown Hub" - a Sasolburg/Zamdela local
    business and services directory), just swapping the accent color to the app's own
    gold/yellow brand and writing new headlines against its actual screens.
  - **Store listing copy was rewritten, not copy-pasted from a ClaudeAI-drafted marketing
    doc the user had prepared** - that draft undersold Session Access entirely (framed
    AethelHook as pure "block dangerous actions," missing the phone-to-PC prompting
    angle), used em dashes (standing project rule against them), overclaimed IDE support
    for GitHub Copilot and OpenCode (both are CLI-only here), and used the same "Devin/
    Windsurf" conflation flagged earlier the same day (see below) as if it were settled
    fact. Rewrote both the short and full description around "remote control" as the
    lead positioning instead, per explicit user correction that the safety-gate framing
    alone undersells the product.
  - **A live naming question got resolved as "don't do it," not "do it differently."**
    User asked to append "(Windsurf)" to every mention of "Devin," which would have
    mislabeled the actually-supported standalone CLI as the Windsurf-rebranded product
    (that's the *unsupported* Devin IDE, per gotcha #34) - flagged the contradiction via
    `AskUserQuestion` before touching any file, user answered "nevermind" to both
    clarifying questions, so no rename happened anywhere. Not to be re-attempted without
    the user explicitly re-raising it.
  - **Declined a paid closed-testing fulfillment service (primetestlab.com) after
    fetching and evaluating it directly** rather than guessing - it sells "12 testers"
    for ~$20 who allegedly install and use the app daily for 14 days, which doesn't match
    real compensated-labor economics at that price and is exactly the pattern Google's
    new-account closed-testing requirement exists to filter out. Given the user already
    had real testers lined up, recommended against it outright - the downside (developer
    account termination, not just one app's rejection) has no plausible upside here.
  - **App content declarations completed live**: privacy policy URL, target age (18+
    only, to avoid Families Policy scope for a developer tool with no child audience),
    Advertising ID (No - no ad/analytics SDK anywhere in the dependency list), content
    rating questionnaire, Data Safety form, and a `FOREGROUND_SERVICE_REMOTE_MESSAGING`
    justification (accurately describing `AethelHookWebSocketService`'s real purpose -
    real-time approval delivery, not SMS/messaging - plus a required demo video, reusing
    the user's existing YouTube phone-demo clip).
  - **Internal testing published instantly** (no Google review needed for that track),
    live-verified by the user installing and using the real Play-distributed build on
    their own phone before proceeding.
  - **Closed testing tester management uses a Google Group
    (`aethelhook-testing@googlegroups.com`) set to "anyone on the web can ask" to join**,
    specifically so the user doesn't need to pre-collect testers' email addresses -
    people request to join via one shared link, get approved with one click, and their
    email is captured automatically. Same group is reusable for sastownhub's own closed
    test (or any future AethelSt8 app) rather than needing a separate one per app, since
    Play Console lets multiple apps' tracks point at the same group.
  - **Closed testing release submitted and approved in about 5 minutes** - far faster
    than the "hours to days" expectation set going in. As of this writing: 6 of the
    needed 12 testers are lined up (the user + 5 named people); the 14-day countdown
    hasn't started yet since it only begins once testers actually opt in via the track's
    real opt-in URL (distinct from the Google Group join link) - that's the immediate
    next action, not yet done as of this writing.
  - **Decided to run a second app's Play Store submission (sastownhub) in parallel
    rather than sequentially after AethelHook reaches Production** - the 14-day closed-
    testing clock is calendar time, not effort time, so there's no benefit to serializing
    two apps' submissions on the same account. Flagged (with appropriate hedging, since
    exact policy wording on this point isn't fully certain) that the 12-tester/14-day
    gate is best assumed to apply per-app rather than per-account, verifiable by checking
    that app's own Play Console dashboard once created, and that the *same* 12 real
    testers can satisfy both apps' requirements simultaneously if they opt into both,
    which is the user's actual plan.
- **Not yet done**: sastownhub's own Play Console listing/AAB/submission (only its
  screenshot set exists so far); getting AethelHook from 6 to 12 opted-in testers and
  actually starting the 14-day clock; the dormant Reddit/Show HN/Product Hunt posts
  are back on the table as a tester-recruitment channel but still unposted as of this
  writing.

**As of 2026-08-02 (OpenClaw investigated as a 7th agent, dropped after live testing -
no repo changes made):**

- **Full detail in gotcha #37 above.** Short version: OpenClaw turned out to be a
  chat-platform gateway/daemon wrapping other coding CLIs, not a coding CLI itself -
  clarified with the user up front that the actual ask was "gate OpenClaw's own tool
  calls via its own plugin hook system" (the OpenCode-shaped path), not "let OpenClaw
  drive our existing hooks" (the Devin-IDE-ACP-shaped path).
- Installed OpenClaw locally, upgraded this machine's Node to 24.18.1 (was 24.11.1 -
  OpenClaw hard-requires it), and did a real live-fire investigation before writing any
  integration code, per this project's standing "verify before building" discipline.
  Found the real hook (`before_tool_call`, confirmed via the installed package's own
  `.d.ts` files, not docs alone) and a real plugin-manifest requirement
  (`openclaw.plugin.json` + `package.json`'s `openclaw.extensions`, not a bare file
  path) neither of which matched the initial docs-based research.
- **Adversarially tested the approval gate with a throwaway plugin before building the
  real one** - a "5s await then always deny" handler had zero effect on a real shell
  command or a real live web search, because onboarding's own recommended default
  (reusing an existing Claude Code login) routes through an externally-spawned Claude
  Code CLI process (ACP) whose native tools are invisible to OpenClaw's plugin layer
  entirely. Same shape of finding as gotcha #34's dead-end Devin-IDE investigation.
- Presented the finding and three options to the user (verify the one path that might
  still work, given a direct API key; ship with a disclosed partial gate; or drop it) -
  **user chose to drop it**, same call as Antigravity (gotcha #29). No `AethelHook.API`,
  Android, or installer files were touched this session - OpenClaw itself stays
  installed standalone on this dev machine (global npm package, harmless) in case a
  future session revisits this with a direct provider API key to test the one path
  that wasn't ruled out.

**As of 2026-07-31 (Settings > About links, light-mode default, and a Report an
Issue button shipped; signed release APK rebuilt and installed):**

- **About card** (`MainActivity.kt`'s `SettingsScreen`) now has three tappable
  `LinkRow`s (label + external-link icon, `Intent.ACTION_VIEW`) above the
  copyright line: Website (`https://aethelst8.com`), GitHub
  (`https://github.com/aethelst8/aethelhook`), and Privacy Policy
  (`https://aethelst8.com/privacy/`).
- **Added a "Report an Issue" button** (`GlassButton`, amber, bug icon) directly
  above the About card, opening
  `https://github.com/aethelst8/aethelhook/issues/new` in the browser.
- **Light mode is now the default theme for a fresh install** -
  `AppPrefs.getDarkMode()`'s fallback flipped from `true` to `false`
  (`AppPrefs.kt:270`). Existing installs with an explicit saved preference are
  unaffected; this only changes the value returned when no preference has ever
  been written.
- **Rebuilt and installed both a debug and a signed release build this
  session**, each requiring an uninstall first due to the standing
  debug/release signing-key mismatch (see the 2026-07-09/07-11 distribution
  entries below) - phone needed a fresh QR re-pair after the final release
  install. **Android version not bumped** for this change (no explicit request
  to do so this session, unlike prior UI-fix passes).
- **Not yet done**: `aethelst8.github.io`'s own "Report an Issue"-equivalent
  link (if any) and the app's own GitHub release notes weren't touched - this
  was scoped to the on-device Settings page only.

**As of 2026-07-26 (status bar theme sync + TTS voice quality fixes shipped; Heard
competitor researched, iOS web client design explored then explicitly deferred):**

- **Full detail in gotcha #36 above.** Short version: fixed two small but real
  Android bugs - the status bar's icon color only ever tracked the system-wide dark
  mode setting once at launch, never the app's own in-app theme toggle; and the TTS
  read-aloud voice (from the 2026-07-19 session) still sounded robotic because
  Android's self-reported quality tier isn't a reliable signal on its own - voice
  selection now explicitly prefers network-backed (cloud) voices over local ones.
  Both live-verified via a real triggered summary popup (`adb shell am start` with
  test extras); user confirmed "its perfect."
- **Researched a Product Hunt competitor, "Heard"** (heard.dev,
  github.com/heardlabs/heard) at the user's request. Turned out to be a materially
  different product than initially assumed - it's a macOS-only voice-narration
  companion (TTS for agent output), not an approval gateway; confirmed via the OSS
  repo's full source (no QR/phone/mobile code anywhere in the public repo) plus the
  live site. The phone/QR feature the user had actually seen is real but paywalled
  behind their "Power" tier (private beta, invite-only) - "Heard Mobile" is a
  **mobile web page** reached via a session-scoped, auto-expiring QR/pairing link,
  not a native app, which is why no app-store listing exists for it. Voice/approval
  interaction there is push-to-talk (spoken commands), not discrete Allow/Deny
  buttons, and it's almost certainly cloud-relayed rather than direct-to-device.
- **Explored building an equivalent iOS web client for AethelHook** (no native iOS
  app exists yet), walked through the real architecture given AethelHook's existing
  pairing/TLS model: browsers can't do certificate pinning (unlike Android's
  `PinnedTls.kt`), so a browser client hitting the self-signed HTTPS cert needs
  either a manual "trust this cert"/`.mobileconfig` step or a Tailscale-provisioned
  real cert (works only when phone+PC share a tailnet); iOS's stock Camera app also
  can't scan the existing raw-JSON QR payload at all (only auto-opens URL-shaped QR
  codes), so a second QR format would be needed; and a plain web page can't hold a
  background connection or wake a locked phone without real Web Push (iOS 16.4+,
  requires "Add to Home Screen"). **User decided to defer this entirely for now**
  rather than commit to a specific combination of those tradeoffs - no code written,
  nothing changed in the app/API for this.

**As of 2026-07-22, later the same day (Devin CLI gained full Session Access,
superseding its own approval-gate-only scope decision from earlier that day):**

- **Full detail in gotcha #35 above.** Short version: the user asked directly
  whether Devin supports a headless feature, confirmed it does (already
  live-verified via `-p` mode throughout gotcha #34's own build-and-verify pass),
  and asked for Session Access to be added - the credential concern that kept
  Copilot approval-gate-only didn't apply the same way (Devin's standalone CLI
  auth is file-based, reachable from a LocalSystem-spawned process).
- **Two genuinely new techniques needed**, since Devin's `-p` mode has no
  machine-readable output at all (plain text only) and never exits cleanly on its
  own: a new `find_latest_session.py` queries `sessions.db` directly to capture a
  resumable session id (mirroring the done-notification's own SQLite-summary
  technique), and `RunHeadlessDevinCliPromptAsync` uses an idle-timeout-then-kill
  strategy instead of awaiting a clean process exit.
- **Two real bugs found via live testing, both fixed same-session**: (1) resume
  silently never worked on the first attempt - `find_latest_session.py` is spawned
  directly by the API service rather than as a child of `devin.exe`, so it never
  inherited the real user's overridden `APPDATA` and always queried LocalSystem's
  own empty profile; fixed by passing the resolved db path explicitly instead of
  relying on environment-variable expansion. (2) the reply text never showed up as
  a chat bubble on the phone despite everything else working - the chat UI assumes
  a `session_update` heartbeat already added a bubble on success, which is true
  for the other four agents but never true for Devin CLI (no such heartbeat hook
  exists for it); fixed by special-casing `agent == "devincli"` in
  `SessionActivity.kt`'s existing success/failure branch.
- Live-verified end-to-end, twice - once for the resume fix (a real two-message
  conversation correctly recalling "Banana"), once for the chat-bubble fix
  (confirmed directly by the user on their own phone) - through the real deployed
  API both times, not a standalone CLI test.

**As of 2026-07-22 (Copilot CLI and Devin CLI added as 5th/6th agents, both
approval-gate-only; Gemini/Copilot summary-notification bugs fixed; Claude Code
cross-contamination bug found and fixed):**

- **Full detail in gotchas #33 (Copilot) and #34 (Devin CLI) above.** Short version:
  both new agents got a full build-and-verify pass, then a deliberate scope cut to
  approval-gate-only (+ a plain done-notification) once the real cost of Session
  Access became clear for each - Copilot CLI's headless auth needs Windows
  Credential Manager (unreachable to the LocalSystem service), and Devin's actual
  "Devin IDE" product turned out to run in ACP mode, which never fires hooks at all
  (only the separately-installed standalone terminal CLI can be gated).
- **Real, hard-won findings**: Copilot's hook timeout fails OPEN (not closed) on
  error, same as later confirmed for Devin's own hook; the user's own installed
  "Devin IDE" is a rebranded Windsurf editor (Cognition acquired Windsurf), a
  genuinely different product from the standalone `devin.exe` CLI despite the
  shared name; and Devin CLI reads Claude Code's own `.claude/settings.json` hooks
  by default (`read_config_from.claude`), which was silently cross-firing Claude's
  Stop hook for every Devin session and sending a mislabeled, blank "Claude Code
  finished" notification - found via a real user report and fixed by having
  `on_agent_done.ps1` skip entirely when `transcript_path` is absent (every real
  Claude Stop event always carries it).
- **Two done-notification "blank summary" bugs found and fixed the same day**, both
  from field names that were guessed rather than confirmed when each integration
  first shipped: Gemini's `AfterAgent` event's real field is `prompt_response` (not
  any of the 4 originally-guessed names), and Copilot's `agentStop` event has no
  text field at all - the real reply only exists in a separate transcript file at
  `transcriptPath`. Both traced from a single user report ("I think this might be
  the problem for gemini too") and confirmed via each agent's own debug log before
  writing a fix, not guessed again.
- **Devin CLI's own done-notification needed a genuinely new technique**: Devin
  stores session history in SQLite (`sessions.db`), not a JSONL transcript like the
  other five agents, so there was no existing pattern to reuse. Added a companion
  `extract_summary.py` (Python's stdlib `sqlite3`, confirmed already installed on
  this machine, no new dependency shipped) that reads the last assistant message
  directly from the database - works safely alongside the still-running `devin.exe`
  process (WAL mode) and degrades gracefully (plain notification, no summary) if
  Python isn't present at all.
- **Also researched this session, not built**: Cursor CLI's hooks (put on hold -
  rough/beta, unclear headless-mode support, later confirmed as the same class of
  gap Devin's IDE turned out to have) and Amazon Q Developer CLI (flagged as the
  natural next candidate, not yet investigated live).
- Live-verified end-to-end against the real deployed service and phone across many
  rounds: Copilot's approval gate + done-notification with a real transcript-file
  summary; Devin CLI's `PreToolUse` gate adversarially confirmed unbypassable even
  under its most permissive `dangerous` mode, plus its own done-notification with a
  real SQLite-extracted summary; and the Claude Code Stop-hook fix confirmed via
  two subsequent real Devin sessions correctly producing no notification at all
  (silent, as intended) while a genuine Claude Code turn still notified normally.
- **Not yet done**: no installer rebuild (`ISCC.exe`) + fresh-machine test covering
  either new agent; no signed Android release build (still debug APK only); Amazon
  Q Developer CLI not yet researched live.

**As of 2026-07-21 (Gemini CLI added as a 4th agent - full feature parity with
Codex/OpenCode, all live-verified against the real deployed service):**

- **Full detail in gotcha #32 above.** Short version: added Gemini CLI (Google's
  `@google/gemini-cli`, npm - distinct from Antigravity despite the similar name)
  with complete feature parity to Codex/OpenCode: approval gate (`BeforeTool` hook,
  `.geminicli\hooks\`), headless Session Access (`RunHeadlessGeminiPromptAsync`),
  token-usage tracking, done notifications, an Android gateway toggle, and (added in
  a same-day follow-up once the user asked for it) session resume.
- **Real, hard-won findings**: personal Google-account login for the standalone CLI
  is now dead (Google pushes individuals toward Antigravity CLI instead, June 2026 -
  the same tool this project already rejected twice), an untrusted project folder
  silently breaks both tool access and hook execution regardless of `--skip-trust`
  (fixed via automatic `trustedFolders.json` writes in `TrustGeminiFolder()`), and a
  `BeforeTool` deny genuinely can't be bypassed even with YOLO mode on - confirmed via
  a real adversarial test blocking every tool call in one turn, a materially better
  result than Antigravity's confirmed deny-bypass (gotcha #29).
- **Six real bugs found and fixed along the way, most predating this integration
  entirely** - `/hook/token-usage`'s hardcoded agent list, `/hook/session-update`
  missing an `agent` field (mislabeled OpenCode's own heartbeats too), a duplicate
  done-notification from wiring one hook to two lifecycle events
  (`AfterAgent`+`SessionEnd`), `BootReceiver.kt` only ever checking Claude's gateway
  flag, and two Android UI bugs caught live by the user - a token-chip row layout
  squeeze (`TokenUsageRow` had no scroll/`maxLines` safety net, fine at 3 chips, broke
  at 4) and `SettingsSheet.kt` silently falling back to Claude's model list for any
  agent it didn't recognize by name.
- Android's Session tab agent switcher changed from tap-to-cycle to a `DropdownMenu` -
  cycling one tap at a time through 4 agents (was fine at 2) got genuinely tedious.
- **Not yet done**: an actual installer rebuild (`ISCC.exe`) + fresh-machine install
  test - `AethelHook.iss`/`dist\install_hooks.ps1` are updated but unexercised beyond
  this dev machine's own live service and hook file sync. No signed Android release
  build either, only the debug APK, installed and live-verified on the dev phone.

**As of 2026-07-19 (context-window usage gauge + voice-to-prompt shipped and
live-verified; a real formula bug found and fixed the same day; notification-dismiss
bug fixed; Sessions chat + whole app UI redesigned to a floating glass-pill look -
shipped as Android v1.3.0 / installer 1.4):**

- **Token usage is a context-window gauge, not a plan/billing quota** - deliberately
  scoped this way after asking the user, since none of the three CLIs expose an actual
  subscription usage quota headlessly at all. Before writing any code, ran a real trivial
  prompt through all three CLIs directly (`codex exec --json`, `opencode run --format
  json`, `claude -p --output-format stream-json --verbose`) to confirm the real field
  names rather than trusting memory of the schema - same live-verify-first discipline as
  gotcha #27's plugin work. Confirmed:
  - Claude's `"result"` message's `usage.{input_tokens,output_tokens,
    cache_creation_input_tokens,cache_read_input_tokens}` sum to the turn's total context
    size, and (the useful surprise) `modelUsage.<resolved model>.contextWindow` reports
    the *actual* max context window for whatever model really ran - no guessing needed,
    unlike the other two agents.
  - Codex's `turn.completed` has `usage.{input_tokens,output_tokens}` (cached tokens are
    already a subset of `input_tokens`, not additive) but never reports the model's max
    window at all.
  - OpenCode's `step_finish` event has a pre-summed `part.tokens.total` directly, also
    with no max-window figure reported.
  - Since Codex/OpenCode never report a real max window, added `EstimateContextWindow()`
    with hardcoded fallbacks (272K/200K) - explicitly labeled in code and in the UI as an
    approximation, not a live figure, to avoid presenting false precision.
- **Server-side**: new `TokenUsageByProjectAgent` dictionary (keyed like
  `ProjectAgentSettings`, `AgentSettingsKey(cwd, agent)`), populated inside all three
  `RunHeadless*PromptAsync` runners after every turn, persisted in `project_state.json`
  alongside the other per-project dictionaries, broadcast live over WS as a new
  `usage_update` event type (`BroadcastUsageUpdateAsync`, kept separate from the existing
  `BroadcastSessionEventAsync` rather than overloading its fixed message/detail shape),
  and readable on-demand via a new `GET /hook/token-usage?dir=...` endpoint (mirrors
  `/hook/git-status`'s pattern).
- **Android**: `SessionActivity.kt`'s per-project chat screen now shows a compact 3-agent
  stat row (Claude/Codex/OpenCode, each "12.4K/200K (6%)" or a dash if that agent's never
  run in this project yet) under the header - shows all three at once regardless of which
  agent is currently selected to send to, per explicit user answer, since each agent keeps
  its own independent thread per project already. Fetched fresh on opening a project chat
  (same per-visit-fetch pattern as the git-status row), then kept live via the new
  `usage_update` WS event for as long as the screen stays open.
  `AethelHookWebSocket.kt` gained a matching `usageUpdates` StateFlow alongside the
  existing `sessionUpdates`/`actionableEvents`.
- **Voice-to-prompt**: a mic `IconButton` in the input row launches Android's stock
  `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` (delegates to whatever speech-recognition
  activity is installed, typically the Google app) rather than the raw on-device
  `SpeechRecognizer` API - deliberately the simpler of the two: no `RECORD_AUDIO`
  permission needed in AethelHook's own manifest since the resolved recognizer activity
  holds that permission itself, and no partial-results/continuous-listening state to
  manage. Recognized text appends to (rather than replaces) whatever's already typed.
  Falls back to a toast if no recognizer is available on the device
  (`intent.resolveActivity()` check) instead of silently doing nothing.
- **Live-verified same day** after the user ran `install.ps1` and connected the phone via
  USB: token stat row and mic button both confirmed working end-to-end on a real device.
- **Found and fixed a real formula bug the same day, from a live user report**: after a
  multi-tool-call "push to github" prompt, the phone showed `Claude 2.2M/1.0M (100%)`,
  then a follow-up prompt showed it reset to near-0 before climbing back to `1.2M/1.0M`.
  Root cause, confirmed via a second live test (a deliberate 4-tool-call prompt): Claude's
  `usage` TOP-LEVEL fields are a **cumulative sum across every internal tool-call round
  trip in that turn**, not a snapshot of current context size - confirmed live, top-level
  `cache_read_input_tokens` came back ~2.5x any single round trip's own figure. That's a
  real "tokens billed this turn" number, but it can legitimately exceed the actual context
  window for a turn with many tool calls (a git push being exactly that) even though no
  single API call in it ever did. The reset-to-0 was a secondary effect: the push likely
  errored partway, which clears the pinned resumable session (existing behavior), so the
  next prompt's cumulative count started over from near-zero and re-inflated the same way.
  Fix: use the LAST entry in `usage.iterations` (the most recent individual round trip)
  instead of the top-level cumulative total - that's the real current context fill.
  Falls back to the top-level fields only if `iterations` is ever absent (identical result
  in the single-turn case anyway). Codex/OpenCode's own usage fields were left alone -
  neither exposes a comparable per-iteration breakdown to apply the same correction to,
  and this bug was only ever confirmed for Claude.
- **Fixed a real bug found by the user via live testing**: answering an approval/question/
  plan-review from the Sessions chat's inline chips (or from a chip-opened full-screen
  Activity) left the original system notification sitting in the shade - only tapping the
  notification itself or its own quick-action buttons had ever cleared it. Root cause: no
  code path existed to reach back and dismiss a notification from a *different* surface
  answering the same decision. Fix: new `NotificationRegistry` (in `AethelHookWebSocket.kt`)
  maps `session_id -> notificationId` at the moment each notification is shown;
  `DecisionActions`' three shared submit functions (used by all four answer surfaces - the
  three full-screen Activities and the Sessions inline chips) now cancel via that registry
  as their first step, so a decision answered from anywhere dismisses the notification
  everywhere. `DecisionBroadcastReceiver` (the notification's own quick-action buttons)
  already canceled itself directly via its own `notification_id` extra - left as-is,
  just also purges the registry entry for tidiness. Also added the "Always allow" quick
  action to the Sessions inline approval chips (previously only Allow/Deny), matching the
  system notification's own 3 buttons exactly (Allow once/Always allow project/Deny) -
  the full `ApprovalActivity`'s 2 extra options (always-allow-globally, deny-with-reason)
  live behind its own "More options" section, not on the notification, so deliberately
  left out of the chips too for parity with what the notification itself offers.
- **Whole app UI redesigned to a floating "liquid glass" look**, prompted by the user
  sharing reference screenshots (Apple Music's floating tab bar + mini-player, Samsung
  Settings' floating back-button chip over content that scrolls behind it) and pointing
  out that AethelHook's own headers/nav, while already glass-styled, were still full
  layout blocks that pushed content down and wasted vertical space on small screens
  rather than floating over it:
  - Removed the root `Scaffold` from `AethelHookApp` entirely - its `bottomBar` slot
    reserved its own dedicated layout region and padded every screen's content away from
    behind it, which is the opposite of "floating over content." The nav pill is now a
    plain overlay `Box` aligned `BottomCenter` inside the same root `Box` as the screen
    content, which now renders full-bleed.
  - New shared `FloatingHeaderBar` composable (same glass-pill visual recipe as the
    existing nav pill: translucent background, gradient border, rounded corners) used by
    every screen (Dashboard, Sessions list, Sessions chat, History, Settings) - pinned to
    the very top via `statusBarsPadding()` instead of each screen guessing a fixed
    `padding(top = 56.dp)` inside its own scrollable content (which meant the header
    itself scrolled away with the content beneath it, the exact complaint). Each screen
    measures the bar's own rendered height via `onGloballyPositioned` and feeds it back
    as that screen's content top-padding, so content starts exactly where the header ends
    regardless of device/status-bar height - no more magic-number guessing.
  - **Real bug hit and fixed while building this**: `onGloballyPositioned` was placed
    *after* `statusBarsPadding()`/`padding()` in the modifier chain - a modifier only
    measures what's nested inside it, so putting it last reported just the inner glass
    card's own height and silently dropped the status-bar inset + outer padding from the
    total, undershooting every screen's measured header height by exactly that amount.
    Confirmed live via a real screenshot the user sent: every screen's second card was
    rendering partly behind/under the header. Fixed by moving `onGloballyPositioned` to
    wrap `statusBarsPadding()`/`padding()` instead (first in the chain), confirmed fixed
    via a follow-up screenshot.
  - Sessions chat screen: the floating nav pill is now hidden entirely while a project
    chat is open (`SessionScreen` gained an `onChatOpenChanged` callback, hoisted into
    `AethelHookApp`'s own state) - the user relies on the phone's own back gesture there
    instead, per explicit request, so only the input row occupies the bottom of that
    screen. That input row (mic button, text field, send button) was also converted to
    the same floating glass-pill card as the header/nav, rather than sitting bare on the
    background - the text field's own border/container were made transparent so the
    surrounding card reads as the single input surface, not two stacked borders.
  - The chat screen's token-usage stat row now lives stacked inside the SAME floating
    header card as the back-arrow/project-name/agent-toggle row (both are children of one
    `FloatingHeaderBar`'s `Column` content), not a separate floating element - this
    required generalizing `FloatingHeaderBar`'s content slot from `RowScope` to
    `ColumnScope` (each caller wraps its own row(s) explicitly now).
  - Live-verified via screenshots at each step: header/content overlap confirmed fixed,
    floating input bar confirmed matching the rest of the app's visual language.

**As of 2026-07-18 (Session Access settings/worktrees/inline-actions shipped as
v1.2.0/installer 1.3; OpenCode notification-spam and dashboard-stats bugs fixed):**

- **Shipped the 5-phase Session Access feature-parity work** prompted by trying
  competing tools (Happy Coder, OpenChamber) and finding real, validated gaps to
  fill rather than assuming parity was needed - each phase was built, deployed via
  `install.ps1`, and live-verified against the real running service before moving
  to the next:
  1. Git branch + diff-stat per project in the Sessions list (new
     `GET /hook/git-status`, TTL-cached; found and fixed a real bug along the way -
     `git` shelled out by the LocalSystem-run service couldn't see the real user's
     `.gitconfig` `safe.directory` entries and silently refused to touch the repo,
     same class of issue as gotchas #1/#2, fixed via `FindRealUserProfileDir()`).
  2. Inline actionable chat chips for approvals/questions/plan-reviews in the
     Sessions tab, alongside (not replacing) push notifications - required
     threading `cwd` through `on_approval_request.ps1`/`on_ask_question.ps1`/
     `on_exit_plan.ps1` and the corresponding WS payloads (none of the three
     carried it before). Extracted `DecisionActions.kt` so all four submission
     surfaces (3 full-screen Activities + the new inline chips) share one
     WS-then-HTTP-fallback implementation instead of four copies.
  3. Per-session Model/Effort/Permission-mode settings sheet
     (`SettingsSheet.kt`, Material3 `ModalBottomSheet` - the app's first) for
     Claude Code - live-verified all 6 of Claude's real `--permission-mode`
     values (`acceptEdits`/`auto`/`bypassPermissions`/`manual`/`dontAsk`/`plan`)
     do NOT bypass AethelHook's own PreToolUse hook, including
     `bypassPermissions` despite its name - the hook is independent of Claude's
     own permission-mode setting entirely.
  4. Extended model/effort to Codex and OpenCode with their own real CLI
     vocabularies, verified live against the actual CLIs rather than docs -
     Codex's real reasoning-effort ceiling is `xhigh` not `max` (confirmed via
     the model's own rejection error), and `o3` is flatly unsupported on a
     ChatGPT-plan Codex account (a real account-tier constraint, not a bug) so
     it was left out of the model list entirely; OpenCode's `--variant` accepts
     the full range with no rejection. Permission-mode is deliberately hidden
     entirely for Codex/OpenCode - neither has a native concept, AethelHook's
     own hook is already the sole gate for both regardless of any flag.
  5. Git worktree isolation per (project, agent) - server-manages the worktree
     identically for all three agents (`git worktree add` under
     `C:\ProgramData\AethelHook\worktrees\`) rather than using Claude's native
     `-w` flag only for Claude, since Codex/OpenCode have no equivalent and two
     different code paths would have meant two different reliability
     guarantees. Confirmed live: worktree creation is idempotent, the
     resumable-session dictionaries correctly key off the worktree path (not
     the project dir) once enabled, and toggling it off correctly reverts to
     the original directory's own resumable thread. **Known follow-up, not
     fixed**: a worktree the service creates is owned by SYSTEM, so the
     interactive user gets Access Denied trying to delete one manually from a
     non-elevated shell - no cleanup UI exists yet, deletion needs an elevated
     `Remove-Item -Recurse -Force`.
- **Found and fixed two real bugs unrelated to the above, both reported live by
  the user mid-session:**
  - **OpenCode's own "doom loop" bug (see gotcha #28) was spamming approval
    notifications for over 2 hours from one stuck interactive `opencode`
    terminal** the user had left open, re-running `git status`/`log`/`diff` and
    its own `todowrite` calls every ~80s indefinitely. Root-caused via the
    `[OpenCode]`-prefixed lines in `hook_debug.log` and a live process check
    (`Get-Process -Name opencode`) that found the actual stuck PID. Fixed with
    two changes to `.opencode\hooks\aethelhook-plugin.js`: (1) a repeat-approval
    guard - once the phone has explicitly approved the exact same read-only git
    subcommand twice within 15 minutes, further repeats auto-approve without a
    new notification (fails closed on anything chained, e.g. `git status &&
    rm -rf .` never qualifies); (2) `todowrite` is now fully exempt from the
    approval gate, matching Claude Code's own settings.json precedent (TodoWrite
    was never gated there either, since it's pure bookkeeping with no
    filesystem/execution side effects - confirmed live that the stuck session's
    `todowrite` calls never once accompanied a real Write/Edit approval request).
    Both fixes live-verified via a direct Node script calling the plugin's
    exported hook functions directly, sidestepping the free test model's own
    flaky tool-calling behavior.
  - **Dashboard stat counters (Total/Approved/Denied) were tracked as separate
    ever-incrementing persistent counters, completely independent of the
    50-entry-capped history list** - once more than 50 decisions had
    accumulated, an old denial could age out of the visible/tappable history
    while the counter kept counting it forever, so the dashboard could show
    "Denied: 2" while the detail popup (which filters that same capped list)
    showed zero matching records. First fix attempt (derive the 3 counts live
    from the existing 50-cap history list) closed the drift but introduced a
    new symptom the user caught immediately: Total got stuck at exactly 50 and
    Approved visibly fell as Denied rose, since a fixed-size list's 3 counts
    always sum to its cap. Root-caused that `maybeClearOldHistory` already
    wipes the whole history list every 48 hours regardless, so removing the
    50-entry cap in `addRecord` is safe (bounded to ~2 days of real activity,
    not truly unbounded) - all 3 stats are now simple derived counts over an
    uncapped-but-periodically-cleared list, so they can never drift from what
    tapping into them shows, and Total genuinely keeps growing with real usage.
  - Both fixes required no `install.ps1` redeploy (the OpenCode plugin is a
    plain JS file OpenCode reloads on its own next launch) or only an APK
    rebuild (the dashboard fix is Android-only), not backend changes.
- **Added a read-aloud (TTS) button to the agent-summary popup** (`SummaryPopup`
  in `MainActivity.kt`), via Android's built-in `TextToSpeech` - no new
  dependency. Two follow-up refinements from live user feedback in the same
  session: (1) real pause/resume instead of stop/restart-from-scratch - Android's
  TTS has no native pause, so the summary text is split into sentence-sized
  chunks spoken as separate utterances, and the utterance-progress listener's
  `onStart` tracks which chunk was actually playing when paused, so resuming
  re-queues from that chunk instead of chunk 0 (chunk-granularity pause, the
  standard workaround given the platform API); (2) automatic highest-quality
  installed-voice selection - the engine's default voice is often the older,
  more robotic one, so once the engine reports ready, the code queries
  `engine.voices` for the best `QUALITY_*` voice actually installed (not just
  listed) for the current locale and switches to it. Both live-verified working
  by the user via a real popup triggered directly through `adb shell am start`
  with test extras (bypassing the need for a real agent-done event).
- **Shipped as Android `v1.2.0`** (versionCode 5→6, versionName 1.1.0→1.2.0) and
  Windows installer `AppVersion` 1.2→1.3 - both rebuilt
  (`dotnet publish` ×2, signed `assembleRelease`, `ISCC.exe`), the installer
  re-uploaded to the existing `v1.0.0` GitHub release (`--clobber`, same
  convention as before), the Android APK as a new tagged `v1.2.0` release.
  Website (`aethelst8.github.io`) Android download link bumped to match, lint
  clean, pushed the same pass per the standing
  `feedback_website_sync_after_installer_changes` rule rather than as a
  separate follow-up.
- **Also hit and resolved, unrelated to the feature work**: a `169.254.x.x`
  (APIPA/link-local) bad LAN-IP detection recurred mid-session (same class of
  race as gotcha #18's original fix, which retries for up to 60s but can still
  lose the race) - the phone couldn't connect or re-pair at all until the user
  ran an elevated `Restart-Service AethelHook`, which re-triggered detection
  and picked up the real LAN IP. Left as a known possible recurrence, not
  re-hardened further this session (user explicitly deferred deeper work here).

**As of 2026-07-16 (r/LookWhatTheyBuilt launch post drafted, first Reddit testimonial
added to the site):**

- **Drafted a Reddit launch post for r/LookWhatTheyBuilt** (this had been sitting as
  "not yet drafted" since the 2026-07-12 entry below) - subreddit-appropriate format,
  title + flair + short body with emoji-linked profiles, matching how other posts in
  that sub are actually written rather than a generic press-release style. Points to
  `https://aethelst8.com` and `https://github.com/aethelst8/aethelhook`, credited as
  `aethelst8`, screenshot is `https://aethelst8.com/media/mobile-app-dashboard.jpeg`
  (verified live with a direct `curl -I` before including it - HTTP 200, image/jpeg,
  57KB). Not yet actually submitted to Reddit by the user.
- **Added the site's second testimonial** (`aethelst8.github.io`'s `Reviews.jsx`) from
  a real comment on that draft post praising the Windows Hello/WinRT interop work
  (gotcha #24) as the unglamorous part that ends up taking most of the real
  development time - cleaned up a couple of typos in the quote for readability
  (kept the meaning intact) before adding it, using the existing `RedditIcon`
  component (already used for the footer social link). Lint passed clean, committed
  and pushed to `aethelst8.github.io` main (`412ea2f`).

- **Full detail in gotcha #29 above.** Short version: the user found a real
  deny-bypass bug in Antigravity (denying a tool call didn't actually block it),
  reproduced on this dev machine too, and confirmed via `hook_debug.log` that
  AethelHook's own hook was doing everything right - the bypass is inside
  Antigravity itself, downstream of the hook. Tried reverting the Review Policy
  setting from gotcha #26 to isolate it - didn't fix it, root cause still unknown.
  Combined with the pre-existing gaps (no Stop-hook notification, no Session
  Access), decided to stop shipping it: removed it from `AethelHook.iss`'s
  `[Files]` and from `dist\install_hooks.ps1`, rebuilt and re-uploaded
  `AethelHook-Setup.exe` to the `v1.0.0` release (`AppVersion` stays 1.2 - a scope
  correction, not a new version). Implementation intentionally left in place
  (`Program.cs`, `.gemini\hooks\*.ps1`, `ANTIGRAVITY_HOOKS.md`) in case it's fixed
  later. Website (aethelst8.com) and `README.md` both updated to drop every
  Antigravity mention, back to 3-agent framing (Claude Code, Codex, OpenCode).
- **Also fixed the same day**: OpenCode's Sessions chat stayed empty during real
  interactive use (notifications and Session Access replies both worked, but no
  per-tool activity ever showed) - the plugin had no equivalent to the other
  agents' `on_tool_done.ps1`. Added a `"tool.execute.after"` handler posting to
  `/hook/session-update`, deployed live (dev/dist/live copies in sync), confirmed
  the approval flow still fires correctly; the user separately confirmed the fix
  itself works.

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
