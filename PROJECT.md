# ÆthelHook

**AI agent permission gateway — approve or deny every tool call from your phone.**

Built by **ÆthelSt8**.

---

## What It Is

When an AI coding agent (Claude Code, Cursor, etc.) wants to run a command, write a file, or execute anything on your machine, it has to ask your phone first. You get a notification, tap Allow or Deny, and the agent proceeds or stops — all in real time, from anywhere.

No more clicking dialog boxes in your IDE. No more trusting the agent blindly. Full control, in your pocket.

---

## The Problem It Solves

AI coding agents are powerful but dangerous by default. They can:

- Execute arbitrary shell commands
- Overwrite or delete files
- Make network requests
- Install packages

Most IDEs show a simple "Allow / Deny" popup on the same screen you're working on — easy to click through without reading. ÆthelHook routes every one of those decisions to your phone, forcing a deliberate choice before anything runs.

---

## Components

ÆthelHook has three parts that work together:

### 1. PC API (`AethelHook.API`)

A .NET 9 Windows Service that runs in the background on your PC. It:

- Listens on port `5264` (all interfaces — LAN, Tailscale, localhost)
- Receives hook events from the IDE
- Pushes notifications to the phone over WebSocket (primary) or FCM push (fallback)
- Waits for the phone's decision and returns it to the IDE hook
- Broadcasts a UDP beacon every 3 seconds so the phone can auto-discover the PC's IP
- Serves a loopback-only QR pairing page (`/pair`) for handing a phone its API token securely

### 2. Android App (`app/`)

A Kotlin/Jetpack Compose app with a Liquid Glass UI. It:

- Maintains a persistent WebSocket connection to the PC API
- Shows full-screen approval notifications with action buttons
- Lets you approve, deny, always-allow, or deny-with-a-reason
- Tracks approval history
- Auto-discovers the PC on any network (LAN, hotspot, mobile data via Tailscale)

### 3. Claude Code Hooks (`.claude/hooks/`)

PowerShell scripts registered as Claude Code hooks. They:

- Fire before every tool call (`PreToolUse`) — Bash, PowerShell, Write, Edit, Read
- Fire before `AskUserQuestion` — routes Claude's multiple-choice questions to the phone instead of the native in-IDE dialog (see below)
- Fire when Claude finishes its turn (`Stop`)
- POST events to the PC API and wait for the phone's decision (up to 5 minutes)
- Exit 0 (allow) or exit 2 with a JSON block (block the tool)

---

## How It Works — Full Flow

```
Claude Code wants to run a Bash command
  │
  ▼
PreToolUse hook fires (on_approval_request.ps1)
  │  Reads tool name + command from stdin
  │  Checks local allow-list (phone-managed)
  │
  ▼
POST /hook/event → PC API (localhost:5264)
  │
  ├─── WebSocket connected? ──► Push JSON to phone instantly (<100ms)
  │
  └─── No WebSocket? ──────────► FCM push notification (3-5s via Firebase)
  │
  ▼
Android phone notification
  ├── Quick actions: [Allow once] [Always allow] [Deny]
  └── Tap body → full ApprovalActivity with all 5 options
  │
  ▼
User decision sent back
  ├── WebSocket (primary): app sends JSON frame directly
  └── HTTP POST /hook/respond (FCM fallback path)
  │
  ▼
API resolves /hook/wait-decision/{sessionId}
  │
  ▼
Hook receives decision
  ├── Allow → exit 0 → tool runs
  └── Deny  → exit 2 + reason → Claude sees "Denied via phone"
```

When Claude finishes its turn, a separate `Stop` hook fires and sends a quiet "Claude finished working" notification to the phone.

---

## AskUserQuestion → Phone Routing

Claude Code's built-in `AskUserQuestion` tool (multiple-choice clarifying questions) is routed to
the phone the same way as tool approvals, so answering doesn't require being at the PC:

```
Claude calls AskUserQuestion
  │
  ▼
on_ask_question.ps1 fires (PreToolUse, matcher: AskUserQuestion)
  │  POST /hook/ask-question → WS (primary) or FCM (fallback)
  │  GET  /hook/wait-answer/{sessionId} — blocks up to 5 minutes
  │
  ▼
Phone shows a tap-to-open notification → QuestionActivity
  │  Renders each question: radio buttons (single-select) or checkboxes
  │  (multi-select), plus an always-available "Other" free-text option
  │  Single "Submit" button answers the whole batch
  │
  ▼
Answer sent back (WS `question_answer` frame, or HTTP POST /hook/answer-question)
  │
  ▼
Hook emits hookSpecificOutput { permissionDecision: "allow", updatedInput: { answers } }
  │
  ▼
Claude's turn continues using the phone's answer — no native dialog ever appears
```

This hook **fails open**, unlike the approval hooks: any failure (API unreachable, timeout, no
answer) exits 0 with no output, letting the native in-IDE dialog appear as a fallback. It's a
convenience feature, not a security gate, so it never blocks or self-destructs hooks on error.

---

## Connection Strategy

The app stays connected across all network scenarios without any manual configuration:

### Device Pairing (QR)

The API token is no longer broadcast over the network. On first setup (or to pair an
additional phone), open `http://localhost:5264/pair` on the PC — loopback-only, 403 for
any remote caller — and scan the QR it shows from the app's Settings screen ("Scan QR to
Pair"). The QR encodes a one-time, 120-second, single-use secret; scanning it redeems a
real per-device API token via `POST /pair/claim`. The page swaps the QR for "✅ Paired"
the instant it's claimed. Each paired device gets its own token (`devices.json`), so one
can be revoked later without affecting the others.

### On Home Wi-Fi / Same Network

The PC broadcasts a UDP beacon (`AETHELHOOK:5264`) every 3 seconds on port 47263. The phone listens, reads the sender's IP, and connects automatically — this only carries the IP/port for convenience reconnects (e.g. after a DHCP lease change), never the token. No IP address entry needed once a device is paired.

### Phone as Hotspot

The phone's OkHttp client binds its socket to the hotspot interface IP (`BoundSocketFactory`), forcing traffic through the hotspot instead of mobile data. Works seamlessly.

### Mobile Data + Tailscale

When Tailscale is running on the PC, the UDP beacon includes the Tailscale IP: `AETHELHOOK:5264:100.x.x.x`. The phone saves this persistently. When on mobile data, after 3 failed LAN connection attempts, the app automatically switches to the Tailscale URL.

If no beacon has ever been received (first mobile-data session), the user enters the Tailscale URL manually in Settings once. After that, it's automatic.

### FCM Fallback

If the WebSocket is completely unreachable, the PC API sends a push notification via Firebase Cloud Messaging. The phone wakes up, the user taps the notification, and once Tailscale connects, the decision goes back over WebSocket.

---

## Decision Options

| Option                          | What Happens                                                                          |
| ------------------------------- | ------------------------------------------------------------------------------------- |
| **Allow once**                  | Tool runs this one time                                                               |
| **Always allow (this project)** | Added to project allow-list — never asked again for this command in this project      |
| **Always allow (global)**       | Added to global allow-list — never asked again anywhere                               |
| **Deny**                        | Tool blocked. Claude sees "Denied via phone"                                          |
| **Deny with reason**            | Tool blocked + Claude receives a custom instruction (e.g. "use a different approach") |

---

## Android App — UI & Features

- **Liquid Glass design** — transparent glass cards, animated gradient blob background, floating pill navigation bar
- **Dark / Light mode** — true black dark mode (`#000000`), toggleable from Settings
- **Dashboard** — gateway status (online/offline), quick toggle, test event button
- **History** — scrollable log of all approval decisions
- **Question answering** — full-screen `QuestionActivity` for AskUserQuestion: radio/checkbox options per question, always-available "Other" free-text field, single submit for a whole question batch
- **Settings** — LAN URL field, Tailscale URL field, timeout setting
- **Persistent foreground service** — keeps the WebSocket alive when the app is backgrounded or screen is off. Uses `remoteMessaging` service type (no Android time limits)
- **Boot receiver** — service auto-starts when the phone boots (if gateway is enabled)
- **Notification channels:**
  - `aethelhook_channel` — approval requests (`IMPORTANCE_HIGH`, full-screen on lock screen)
  - `aethelhook_done` — "Claude finished working" (`IMPORTANCE_DEFAULT`, quiet)
  - `aethelhook_ws_service` — persistent service notification (`IMPORTANCE_LOW`, silent)

---

## Firebase / Push Notifications

Firebase Cloud Messaging (FCM) is used as a fallback when the WebSocket connection is unavailable.

- The Android app uses `google-services.json` (bundled in the APK) to register with the Firebase project on first launch. Each phone gets its own unique FCM device token automatically.
- The PC API uses `aethelhook-firebase-adminsdk-*.json` (stored next to the service executable) to send notifications to that token.
- **No per-user Firebase setup is required.** All users share one Firebase project. Each user's PC sends only to their own phone's token. This is the standard model used by virtually every consumer app.

---

## Current State (July 2026)

### Working

- [x] Claude Code PreToolUse hooks — Bash, PowerShell, Write, Edit, Read
- [x] Phone approval / denial with all 5 decision options
- [x] AskUserQuestion phone routing — multi-choice + multi-select + free-text "Other" answers, no native dialog
- [x] "Claude finished working" notification after every turn
- [x] WebSocket primary path (LAN + Tailscale)
- [x] FCM fallback path
- [x] UDP beacon auto-discovery (LAN)
- [x] Tailscale auto-discovery + mobile data fallback
- [x] Atomic WS client registration — fixes duplicate notifications seen after switching to Tailscale (concurrent reconnects could briefly leave two sockets registered)
- [x] 5-minute phone response window (approval + question flows)
- [x] Persistent foreground service (no time limit crashes)
- [x] Boot auto-start
- [x] Gateway ON/OFF toggle
- [x] Phone-managed allow-list (always allow commands)
- [x] Approval history log
- [x] Dark / light mode
- [x] Windows Service (auto-starts on boot)
- [x] No hardcoded IPs or tokens — works on any network, for any user
- [x] QR-code device pairing (2026-07-02) — replaces beacon-broadcasts-the-token model; per-device tokens, single-use/short-lived pairing sessions, loopback-only pairing page

### In Progress / Planned

- [ ] Cursor adapter (hook mechanism not yet researched)
- [ ] Antigravity adapter (scaffolded, not tested)
- [ ] Windows `.exe` installer for easy PC setup — **needs rebuilding to include QR pairing** (`AethelHook-Setup.exe` still ships the pre-pairing binary; the running dev service was updated via `install.ps1` directly, not the installer). Planned for this weekend.
- [ ] Distribution to friends / small group testing
- [ ] Device management UI (list/revoke paired phones) — `GET /pair/devices` + `DELETE /pair/devices/{id}` exist server-side, no UI yet

### Known Limitations

- APK must be sideloaded (no Play Store listing yet)
- No in-app update mechanism
- Tailscale URL must be entered manually on first mobile-data session if no prior LAN session
- Pairing a phone requires physical/local access to the PC (must open `/pair` on the PC itself) — intentional, not a bug

---

## Distribution Plan

The goal is a near-zero-configuration install experience:

**Phone:** Install APK → open app → scan the pairing QR shown at `http://localhost:5264/pair` on the PC once → done. After that, UDP beacon auto-discovers the PC's IP on subsequent LAN connections and Tailscale URL is saved for mobile-data use. (The one manual step — scanning the QR — replaced the old fully-automatic-but-insecure beacon-delivers-the-token flow.)

**PC:** Run a single installer (`.exe`) → done. The installer will:

1. Publish the .NET API as a self-contained executable
2. Register it as a Windows Service (auto-starts on boot)
3. Install the Claude Code hooks into the user's Claude settings
4. Bundle the Firebase credentials file

**Firebase:** No action required from users. All installs share one Firebase project. Each user's PC sends only to their own phone.

The installer will be built as a proper Windows `.exe` — not a zip, not a script the user has to run manually.

---

## File Structure

```
C:\AethelHook\
├── AethelHook.API\
│   └── Program.cs                        # All API logic (.NET 9)
├── app\
│   └── src\main\java\com\aethelhook\app\
│       ├── MainActivity.kt               # All screens (Dashboard, History, Settings)
│       ├── AethelHookWebSocket.kt        # WebSocket client, UDP discovery, Tailscale fallback
│       ├── AethelHookWebSocketService.kt # Foreground service
│       ├── AppPrefs.kt                   # Shared preferences (urls, history, settings)
│       ├── ApprovalActivity.kt           # Full-screen approval UI
│       ├── QuestionActivity.kt           # Full-screen AskUserQuestion answer UI
│       ├── AethelHookMessagingService.kt # FCM push handler (fallback path)
│       ├── DecisionBroadcastReceiver.kt  # Notification quick-action handler
│       └── Pairing.kt                    # QR payload parsing + /pair/claim (device pairing)
├── .claude\hooks\
│   ├── on_approval_request.ps1           # PreToolUse hook (Bash/Write/Edit/Read/PowerShell)
│   ├── on_ask_question.ps1               # PreToolUse hook (AskUserQuestion)
│   └── on_agent_done.ps1                 # Stop hook
├── aethelhook-firebase-adminsdk-*.json   # Firebase Admin SDK credentials (keep private)
├── aethelhook-release.jks                # APK signing keystore (keep backed up)
├── keystore.properties                   # Keystore credentials (do not commit to git)
├── install.ps1                           # Installs Windows Service (run as Admin)
└── uninstall.ps1                         # Removes Windows Service
```

---

## Build Commands

```powershell
# Required every session
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path

# Build debug APK
cd C:\AethelHook
.\gradlew assembleDebug

# Install on phone (uninstall first if you get a signature mismatch error)
$adb = "C:\Users\Moloi\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb uninstall com.aethelhook.app
& $adb install "app\build\outputs\apk\debug\app-debug.apk"

# Build release APK (signed, distributable)
.\gradlew assembleRelease
# Output: app\build\outputs\apk\release\app-release.apk

# Run API locally (dev/debug)
dotnet run --project AethelHook.API\AethelHook.API.csproj --urls "http://0.0.0.0:5264"

# Install / reinstall Windows Service (run PowerShell as Administrator)
powershell -ExecutionPolicy Bypass -File install.ps1
```

---

## Service Logs

When running as a Windows Service, all output is written to:

```
C:\ProgramData\AethelHook\api.log
```

Hook debug log (written by the PowerShell hooks):

```
C:\AethelHook\hook_debug.log
```

Per-device pairing tokens (2026-07-02+):

```
C:\ProgramData\AethelHook\devices.json
```
