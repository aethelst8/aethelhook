> **ARCHIVED (2026-07-06):** superseded by `CLAUDE.md` at the repo root. Describes an
> early architecture (wrong file paths, a SQLite store no longer used) — kept for
> history only, moved from `.agents\AGENTS.md`.

# AethelHook Workspace Instructions

You are working on **AethelHook** — a system where an Android phone is the primary approval gateway for every write/execute tool call the Antigravity agent makes, replacing or supplementing the IDE's built-in permission dialogs.

---

## 1. File Map

| Path | Purpose |
|---|---|
| `C:\AethelHook\AethelHook.API\` | .NET API project |
| `C:\AethelHook\AethelHook.API\aethelhook.db` | SQLite event/decision store |
| `C:\AethelHook\AethelHookApp\` | Android app project |
| `C:\AethelHook\.gemini\hooks\on_approval_request.ps1` | PowerShell hook script (fires on PreToolUse) |
| `C:\AethelHook\.agents\hooks.json` | Hook registration configuration |
| `C:\AethelHook\.agents\settings.json` | Project-level agent settings (no execute/write wildcards) |
| `C:\AethelHook\hook_debug.log` | Hook execution log |

---

## 2. Running the API

To start the API in the background, run:
```powershell
dotnet run --project C:\AethelHook\AethelHook.API\AethelHook.API.csproj --urls "http://0.0.0.0:5264"
```
The API listens on port `5264`.

---

## 3. How the Hook System Works

1. When you run a tool that is not pre-approved, the IDE opens its own permission dialog, and concurrently executes the `on_approval_request.ps1` hook script.
2. The hook script posts the approval request to the API, which pushes an FCM notification to the user's Android phone.
3. The hook script long-polls `/hook/wait-decision/{sessionId}` for the phone's choice.
4. Once the user responds on their phone, the hook script uses Windows API (`SendKeys`) to **AutoDismiss** the IDE dialog:
   - **Approved:** Sends `"1{ENTER}"` (Yes, allow this time) and exits 0.
   - **Denied:** Sends `"{ESC}"` (Skip) and exits 2 (blocking execution).

---

## 4. How to Test the Hook

To test the system:
1. Ensure the API is running and the phone is connected via WebSockets.
2. Propose a command that is NOT on the allowed list (e.g., `hostname` or `ping 127.0.0.1`).
3. Tell the user to watch their phone and IDE window.
4. The hook should trigger, notify the phone, and the IDE dialog should auto-dismiss when they click Approve/Deny on the phone.
