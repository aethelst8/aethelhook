> **ARCHIVED (2026-07-06):** superseded by `CLAUDE.md` at the repo root. This describes
> an early Antigravity-only architecture (pre-Claude Code integration, pre-QR pairing,
> pre-Phase 2) and is kept for history only - do not treat as current.

# AethelHook - Complete Handover Document for Claude Code

> **IMPORTANT:** You are picking up a project that has been in active development. Read this entire document before touching anything. The project is functional in concept, the infrastructure is built, but there is one remaining issue blocking the live test. The fix is known and documented below.

---

## 1. Project Goal

**AethelHook** is a system where an **Android phone is the primary approval gateway** for every tool call the Antigravity AI agent makes (`run_command`, `write_file`, `replace_file_content`, `multi_replace_file_content`).

Instead of clicking permission dialogs in the IDE, the user approves or denies every AI action directly from their Android phone. When the user taps **Deny** on their phone, the command is blocked entirely. When they tap **Approve**, the IDE dialog auto-dismisses and the command runs.

---

## 2. Architecture Overview

```
Antigravity IDE
    │
    │ (PreToolUse hook fires before every run_command / write_file / etc.)
    ▼
on_approval_request.ps1   ←── PowerShell hook script
    │
    │ POST /hook/event  (sends tool name + command preview)
    ▼
AethelHook.API  (http://0.0.0.0:5264)
    │
    ├── WebSocket push → Android App (phone)  [instant if connected]
    └── FCM push       → Android App (phone)  [fallback if WS disconnected]
    │
    │ User taps Approve / Deny on phone
    │ App POSTs to POST /hook/decision
    ▼
AethelHook.API  returns decision to hook script
    │
    ▼
on_approval_request.ps1
    ├── Approved → SendKeys "1{ENTER}" to IDE window (auto-dismiss dialog, allow)
    └── Denied   → SendKeys "{ESC}" to IDE window (auto-dismiss dialog, skip/block)
    │
    ▼
Antigravity IDE: command runs or is blocked
```

---

## 3. File Map

| Path | Purpose |
|---|---|
| `C:\AethelHook\AethelHook.API\` | .NET 9 ASP.NET Core Web API |
| `C:\AethelHook\AethelHook.API\Program.cs` | All API logic in one file |
| `C:\AethelHook\AethelHook.API\aethelhook.db` | SQLite database (events + decisions) |
| `C:\AethelHook\AethelHook.API\firebase-service-account.json` | FCM service account credentials |
| `C:\AethelHook\AethelHook.API\Properties\launchSettings.json` | Launch config (port 5264) |
| `C:\AethelHook\AethelHookApp\` | Android app (Kotlin / Jetpack Compose) |
| `C:\AethelHook\.gemini\hooks\on_approval_request.ps1` | **The hook script** - fires on every PreToolUse event |
| `C:\AethelHook\.gemini\hooks\on_task_complete.ps1` | Session end hook (notifies phone when agent finishes) |
| `C:\AethelHook\.gemini\hooks.json` | **Hook registration** - Antigravity reads THIS file |
| `C:\AethelHook\.agents\hooks.json` | Old/duplicate hooks config (NOT read by Antigravity - ignore) |
| `C:\AethelHook\.agents\settings.json` | Project permission settings (wildcards removed) |
| `C:\AethelHook\.agents\AGENTS.md` | Workspace rules (auto-loaded by any new agent) |
| `C:\AethelHook\hook_debug.log` | Hook execution log - your primary debug tool |
| `C:\Users\Moloi\.gemini\settings.json` | Global Antigravity settings (wildcards removed) |
| `C:\Users\Moloi\.gemini\config\projects\877e3233-7f8c-4d86-9c2f-590ded0b4ae5.json` | Project-specific allow list (command entries removed) |

---

## 4. The API (`AethelHook.API`)

### Start Command
```powershell
dotnet run --project C:\AethelHook\AethelHook.API\AethelHook.API.csproj --urls "http://0.0.0.0:5264"
```

### Endpoints

| Method | Path | Purpose |
|---|---|---|
| POST | `/hook/event` | Receives event from hook script. Stores in SQLite. Pushes to phone via WebSocket (instant) or FCM (fallback). |
| GET | `/hook/wait-decision/{sessionId}` | Long-polls up to 80 seconds until phone submits a decision. Returns `{"decision":"allow_once"}` etc. |
| POST | `/hook/decision` | Called by Android app. Body: `{"session_id":"...","decision":"allow_once"}` |
| GET | `/ws` | WebSocket endpoint. Android app connects here for real-time push. |

### Decision Values (from phone)
- `allow_once` - run the command this time
- `always_allow_project` - run + add to phone allow list (no more notifications for this command)
- `deny` - block the command, auto-click Skip in IDE dialog
- `deny_with_reason` - block + send a custom reason string to the agent

### FCM
- Firebase Cloud Messaging is used as fallback when the phone's WebSocket is disconnected.
- Service account: `C:\AethelHook\AethelHook.API\firebase-service-account.json`
- The FCM device token is **hardcoded** in `Program.cs` (search for `fcmToken`).

---

## 5. The Hook Script (`on_approval_request.ps1`)

Located at `C:\AethelHook\.gemini\hooks\on_approval_request.ps1`.

### Flow
1. Logs `--- Approval Request Hook Triggered ---` to `hook_debug.log`
2. Reads tool call JSON from stdin (3s timeout - `run_command` may not send EOF)
3. Extracts `toolName` and `commandPreview` (the `CommandLine` arg)
4. Checks `C:\AethelHook\.agents\phone_allow.txt` - if command is listed, auto-approves silently (no notification)
5. POSTs to `http://localhost:5264/hook/event` with a unique `sessionId`
6. Long-polls `http://localhost:5264/hook/wait-decision/{sessionId}` for up to 80s
7. When decision arrives, uses Windows SendKeys to auto-dismiss the IDE dialog:
   - `allow` / `allow_once` → `SendKeys "1{ENTER}"` (selects option 1 = Yes allow this time)
   - `always_allow_project` → sends allow, also appends command to `phone_allow.txt`
   - `deny` → `SendKeys "{ESC}"` (Skip = block command), exits with code 2
   - timeout → `SendKeys "{ESC}"`, exits with code 2 (safe default: block)
8. Outputs JSON `{"hookSpecificOutput":{"permissionDecision":"allow"}}` or `"deny"` to stdout

### Phone Allow List
`C:\AethelHook\.agents\phone_allow.txt` - one command name per line (just the first word, e.g. `dotnet`, `git`).
Commands in this list are auto-approved silently without sending a phone notification.

---

## 6. Hook Registration (`.gemini\hooks.json`)

**CRITICAL:** Antigravity loads hooks ONLY from `C:\AethelHook\.gemini\hooks.json`. It does NOT load from `.agents\hooks.json`.

Current content of `C:\AethelHook\.gemini\hooks.json`:
```json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "run_command",
        "hooks": [{"type": "command", "command": "powershell.exe -ExecutionPolicy Bypass -File C:\\AethelHook\\.gemini\\hooks\\on_approval_request.ps1", "timeout": 90}]
      },
      {
        "matcher": "write_file",
        "hooks": [{"type": "command", "command": "powershell.exe -ExecutionPolicy Bypass -File C:\\AethelHook\\.gemini\\hooks\\on_approval_request.ps1", "timeout": 90}]
      },
      {
        "matcher": "replace_file_content",
        "hooks": [{"type": "command", "command": "powershell.exe -ExecutionPolicy Bypass -File C:\\AethelHook\\.gemini\\hooks\\on_approval_request.ps1", "timeout": 90}]
      },
      {
        "matcher": "multi_replace_file_content",
        "hooks": [{"type": "command", "command": "powershell.exe -ExecutionPolicy Bypass -File C:\\AethelHook\\.gemini\\hooks\\on_approval_request.ps1", "timeout": 90}]
      }
    ],
    "SessionEnd": [
      {"hooks": [{"type": "command", "command": "powershell.exe -ExecutionPolicy Bypass -File C:\\AethelHook\\.gemini\\hooks\\on_task_complete.ps1", "timeout": 5}]}
    ],
    "AfterAgent": [
      {"hooks": [{"type": "command", "command": "powershell.exe -ExecutionPolicy Bypass -File C:\\AethelHook\\.gemini\\hooks\\on_task_complete.ps1", "timeout": 5}]}
    ],
    "Stop": [
      {"hooks": [{"type": "command", "command": "powershell.exe -ExecutionPolicy Bypass -File C:\\AethelHook\\.gemini\\hooks\\on_task_complete.ps1", "timeout": 5}]}
    ]
  }
}
```

---

## 7. Permission System - What We Learned (Critical)

Antigravity has a **3-layer permission system** that bypasses hooks if any layer pre-approves the tool:

### Layer 1: Global Settings
`C:\Users\Moloi\.gemini\settings.json`
- **What it does:** Pre-approves tool calls globally for all projects.
- **The problem:** Originally had `"run_command(*)"`, `"write_file(*)"` etc. which bypassed hooks entirely.
- **Current state (fixed):** Only `read_file(*)`, `view_file(*)`, `list_dir(*)`, `grep_search(*)`, `search_web(*)` remain.

### Layer 2: Project Settings
`C:\AethelHook\.agents\settings.json`
- **What it does:** Pre-approves tool calls for this workspace.
- **The problem:** Originally had same wildcards as global settings.
- **Current state (fixed):** Only read-only tools remain.

### Layer 3: Project Config Database
`C:\Users\Moloi\.gemini\config\projects\877e3233-7f8c-4d86-9c2f-590ded0b4ae5.json`
- **What it does:** Stores per-project allow list entries. Every time the user clicks **"Yes, and always allow in this project"** in the IDE dialog, an entry is added here. For example: `"command(powershell.exe)"`.
- **The problem:** Because every `run_command` in Windows goes through PowerShell, `"command(powershell.exe)"` allowed ALL commands, bypassing the hook.
- **Current state (fixed):** All `command(...)` entries were removed. Only `write_file(...)`, `read_file(...)`, and `read_url(...)` entries remain.

### ⚠️ The Critical Rule
**NEVER click "Yes, and always allow in this project" (option 2) in the IDE dialog during testing.** This writes a new `command(...)` entry to the project config, which will bypass the hook for that command prefix forever. If you accidentally do this, run:
```powershell
$path = "C:\Users\Moloi\.gemini\config\projects\877e3233-7f8c-4d86-9c2f-590ded0b4ae5.json"
$config = Get-Content $path | ConvertFrom-Json
$config.permissionGrants.permissionGrants.allow = @($config.permissionGrants.permissionGrants.allow | Where-Object { $_ -notlike "command(*" })
$config | ConvertTo-Json -Depth 10 | Set-Content $path
```

---

## 8. The Android App

- Package: `C:\AethelHook\AethelHookApp\`
- Language: Kotlin / Jetpack Compose
- Connects to API via WebSocket at `ws://<PC_IP>:5264/ws`
- Receives push notifications showing: tool name + command preview
- User taps **Approve** or **Deny**
- App POSTs to `http://<PC_IP>:5264/hook/decision`
- The app is installed and working on the user's phone (IP: `192.168.18.2`)

---

## 9. Current State & The Remaining Problem

### What works:
- ✅ API runs and listens on port 5264
- ✅ Android app connects via WebSocket
- ✅ FCM push notifications fire when WebSocket is not connected
- ✅ Phone can send Approve/Deny decisions to the API
- ✅ API returns decision to the hook script
- ✅ Hook script uses SendKeys to auto-dismiss the IDE dialog
- ✅ All three permission bypass layers have been cleaned
- ✅ `.gemini/hooks.json` now has `run_command`, `write_file`, `replace_file_content`, `multi_replace_file_content` matchers

### What is NOT yet confirmed working:
- ❌ The hook has **not been observed firing** for `run_command` in recent tests

### Root Cause Analysis of Current Failure

When we manually ran the hook script like this:
```
echo '...' | powershell.exe -ExecutionPolicy Bypass -File on_approval_request.ps1
```
We got **syntax errors** related to curly braces in `Write-Output` strings. However, viewing the file directly showed the syntax looked correct.

The leading theory is that **the IDE had not yet reloaded `hooks.json`** when we ran our tests - hooks are loaded at session startup and changes to `hooks.json` don't take effect until the IDE restarts.

The user **has just restarted the IDE** before handing over to you, so the new `hooks.json` with `run_command` matchers should now be loaded.

### Your First Task: Verify the hook fires

1. Start the API:
```powershell
dotnet run --project C:\AethelHook\AethelHook.API\AethelHook.API.csproj --urls "http://0.0.0.0:5264"
```

2. Run a test command:
```powershell
hostname
```

3. Check the hook log immediately after:
```powershell
Get-Content C:\AethelHook\hook_debug.log -Tail 20
```

4. If you see `--- Approval Request Hook Triggered ---` with a fresh timestamp → the hook fired! Proceed to test the full phone flow.

5. If the log still shows nothing new → the hook is still not firing. Debug by running the hook manually:
```powershell
'{"toolCall":{"name":"run_command","args":{"CommandLine":"hostname"}}}' | powershell.exe -ExecutionPolicy Bypass -File C:\AethelHook\.gemini\hooks\on_approval_request.ps1
```
Check what error it returns and fix it.

---

## 10. If the Hook Still Doesn't Fire After Restart

Work through this checklist:

### Check 1: Verify hooks.json content
```powershell
Get-Content C:\AethelHook\.gemini\hooks.json
```
Must have `"matcher": "run_command"` in PreToolUse.

### Check 2: Verify no wildcards in settings
```powershell
Get-Content C:\Users\Moloi\.gemini\settings.json
Get-Content C:\AethelHook\.agents\settings.json
```
Must NOT contain `run_command(*)`, `write_file(*)`, etc.

### Check 3: Verify project config has no command wildcards
```powershell
(Get-Content C:\Users\Moloi\.gemini\config\projects\877e3233-7f8c-4d86-9c2f-590ded0b4ae5.json | ConvertFrom-Json).permissionGrants.permissionGrants.allow
```
Must NOT contain any entries starting with `command(`.

### Check 4: Run hook script manually
```powershell
'{"toolCall":{"name":"run_command","args":{"CommandLine":"hostname"}}}' | powershell.exe -ExecutionPolicy Bypass -File C:\AethelHook\.gemini\hooks\on_approval_request.ps1
```
This will show any syntax errors in the script. Fix them if found.

### Check 5: Test the API directly
```powershell
Invoke-RestMethod -Uri "http://localhost:5264/hook/event" -Method POST -ContentType "application/json" -Body '{"event_type":"APPROVAL_REQUEST","message":"[run_command] Approve?","detail":"hostname","session_id":"test-manual-001","timestamp":"2026-06-21T10:00:00Z"}'
```
Then on the phone, approve it. Then:
```powershell
Invoke-RestMethod -Uri "http://localhost:5264/hook/wait-decision/test-manual-001" -Method GET
```

---

## 11. Known Limitations

1. **"Always allow" breaks the hook** - If the user clicks option 2 ("Yes, and always allow in this project") in any IDE dialog during a session, that command prefix gets added to the project config and will bypass the hook from then on. Must be manually cleaned.

2. **Session-cached permissions** - Changes to `settings.json` or `hooks.json` only take effect after an IDE restart. Changes to the project config JSON file take effect immediately (it is read fresh each time).

3. **Auto-dismiss reliability** - The `SendKeys` approach requires the Antigravity IDE window to be focused. If another window is in front, the keys may land on the wrong window. The hook uses `SetForegroundWindow` + `ShowWindow(SW_RESTORE)` before sending keys.

4. **Hook only covers 4 tools** - `run_command`, `write_file`, `replace_file_content`, `multi_replace_file_content`. Other tools like `search_web`, `read_url_content`, etc. are not hooked.

5. **API must be running** - If the API is not running when the hook fires, the hook falls through to `ask` mode (IDE dialog handles it normally). This is intentional graceful degradation.

---

## 12. History of How We Got Here

### Phase 1 (June 15)
- Built the .NET API with SQLite, FCM, and WebSocket support
- Built the Android app
- Created the PowerShell hook script
- Confirmed FCM notifications work end-to-end

### Phase 2 (June 20)
- Confirmed WebSocket push works (faster than FCM)
- Got the full live flow working: hook fires → phone notified → user taps → decision returned → IDE dialog auto-dismissed
- Documented the permission architecture

### Phase 3 (June 21 - today)
- **Problem:** Hook stopped firing for `run_command`
- **Investigation:** Found that the hook WAS firing for `multi_replace_file_content` but not for `run_command`
- **Root cause 1:** `.agents/settings.json` had `"run_command(*)"` wildcard - removed ✅
- **Root cause 2:** `C:\Users\Moloi\.gemini\settings.json` (global) also had `"run_command(*)"` - removed ✅
- **Root cause 3:** Project config JSON had `"command(powershell.exe)"` in the allow list (added when user clicked "always allow" during an earlier test) - all `command(...)` entries removed ✅
- **Root cause 4 (discovered last):** `C:\AethelHook\.gemini\hooks.json` only had `matcher: "ask_permission"` - NOT `run_command`. The `.agents/hooks.json` file with the correct matchers was never being loaded by Antigravity. Fixed by adding all 4 matchers to `.gemini/hooks.json` ✅
- User restarted IDE - **handover happens here**

---

## 13. Quick Reference Commands

```powershell
# Start the API
dotnet run --project C:\AethelHook\AethelHook.API\AethelHook.API.csproj --urls "http://0.0.0.0:5264"

# Watch hook log live
Get-Content C:\AethelHook\hook_debug.log -Wait -Tail 20

# Check API received events (tail the running API output)
# (The API prints to stdout: "Received event: APPROVAL_REQUEST for Session: ...")

# Run hook script manually to test
'{"toolCall":{"name":"run_command","args":{"CommandLine":"hostname"}}}' | powershell.exe -ExecutionPolicy Bypass -File C:\AethelHook\.gemini\hooks\on_approval_request.ps1

# Remove command wildcards from project config (emergency cleanup)
$path = "C:\Users\Moloi\.gemini\config\projects\877e3233-7f8c-4d86-9c2f-590ded0b4ae5.json"
$config = Get-Content $path | ConvertFrom-Json
$config.permissionGrants.permissionGrants.allow = @($config.permissionGrants.permissionGrants.allow | Where-Object { $_ -notlike "command(*" })
$config | ConvertTo-Json -Depth 10 | Set-Content $path

# Send a test event to the API manually
Invoke-RestMethod -Uri "http://localhost:5264/hook/event" -Method POST -ContentType "application/json" -Body '{"event_type":"APPROVAL_REQUEST","message":"[run_command] Approve?","detail":"hostname","session_id":"test-001","timestamp":"2026-06-21T10:00:00Z"}'

# Check current allow list in project config
(Get-Content C:\Users\Moloi\.gemini\config\projects\877e3233-7f8c-4d86-9c2f-590ded0b4ae5.json | ConvertFrom-Json).permissionGrants.permissionGrants.allow
```

---

## 14. What Success Looks Like

When fully working:
1. You (Claude Code) propose `run_command("hostname")`
2. Antigravity fires `on_approval_request.ps1`
3. `hook_debug.log` gets a new entry: `--- Approval Request Hook Triggered ---`
4. The user's Android phone vibrates with a notification: `[run_command] Approve or Decline? - hostname`
5. The user taps **Approve** on the phone
6. `hook_debug.log` gets: `Internal decision: allow_once`
7. The IDE dialog auto-dismisses (SendKeys `1{ENTER}`)
8. `hostname` runs and returns `kmoloi8`

When the user taps **Deny**:
- `hook_debug.log` gets: `Internal decision: deny`
- IDE dialog auto-dismisses (SendKeys `{ESC}`)
- The agent gets blocked: `user denied permission for command(hostname)`
