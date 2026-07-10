> **ARCHIVED (2026-07-06):** superseded by `CLAUDE.md` at the repo root. One-time fix
> handoff from an early stage of the project - kept for history only.

# AethelHook - Fix Handover for Antigravity

## The Problem

After the user taps Approve on the phone notification, Antigravity still shows a native
permission dialog in the IDE requiring manual input. The hook is working correctly -
the issue is how the hook response is structured and a known Antigravity regression.

---

## Root Cause 1 - Wrong JSON Structure

The current `on_approval_request.ps1` outputs:

```json
{"permissionDecision":"allow"}
```

This is incorrect. Antigravity requires the response to be wrapped inside
`hookSpecificOutput`. Without this wrapper, Antigravity cannot correctly parse the
decision and falls back to its native dialog.

The correct output structure is:

```json
{
  "hookSpecificOutput": {
    "permissionDecision": "allow"
  }
}
```

And for deny:

```json
{
  "hookSpecificOutput": {
    "permissionDecision": "deny",
    "permissionDecisionReason": "Declined via phone"
  }
}
```

---

## Root Cause 2 - Known Antigravity Bug (v2.1.119)

There is a confirmed regression in Antigravity v2.1.119 where `permissionDecision: "allow"`
in a PreToolUse hook does not suppress the native permission prompt in interactive mode.
Hook exit 2 (deny) correctly blocks the tool - only the allow suppression path is broken.

The fix is to set `defaultMode` in the Claude settings file so Antigravity defers
permission decisions to the hook system rather than showing its own dialog.

---

## Fix 1 - Update `on_approval_request.ps1`

Find every place in the script that outputs the permission decision and wrap it inside
`hookSpecificOutput`.

Replace the allow output:
```powershell
# OLD - incorrect
Write-Output '{"permissionDecision":"allow"}'

# NEW - correct
Write-Output '{"hookSpecificOutput":{"permissionDecision":"allow"}}'
```

Replace the deny output:
```powershell
# OLD - incorrect
Write-Output '{"permissionDecision":"deny","permissionDecisionReason":"Declined via phone"}'

# NEW - correct
Write-Output '{"hookSpecificOutput":{"permissionDecision":"deny","permissionDecisionReason":"Declined via phone"}}'
```

---

## Fix 2 - Update `C:\Users\Moloi\.claude\settings.json`

Create or overwrite this file with the following content:

```json
{
  "permissions": {
    "allow": [],
    "defaultMode": "default"
  },
  "hooks": {
    "PreToolUse": [
      {
        "matcher": ".*",
        "hooks": [
          {
            "type": "command",
            "command": "powershell.exe -ExecutionPolicy Bypass -File C:\\AethelHook\\.gemini\\hooks\\on_approval_request.ps1",
            "timeout": 90
          }
        ]
      }
    ]
  }
}
```

This ensures:
- All tool calls go through the hook first
- Antigravity defers to the hook's permissionDecision
- The native dialog is suppressed when the hook returns a valid decision

---

## Fix 3 - Keep `.agents/hooks.json` As-Is

The existing hook config at `C:\AethelHook\.agents\hooks.json` is correct and does
not need to change. The matcher on `ask_permission` is fine. The problem is upstream
in the response format and settings file, not in the hook config itself.

---

## Verification Steps After Applying Fixes

1. Restart the AethelHook API:
```powershell
dotnet run --project C:\AethelHook\AethelHook.API\AethelHook.API.csproj --urls "http://0.0.0.0:5264"
```

2. Start a new Antigravity session in `C:\AethelHook\`

3. Trigger any action that requires a permission - e.g. ask the agent to read a file
   or run a shell command

4. Verify the sequence:
   - Hook fires ✅
   - FCM notification arrives on phone within 3 seconds ✅
   - User taps Approve on phone ✅
   - Agent proceeds WITHOUT any IDE dialog appearing ✅

5. Check `C:\AethelHook\hook_debug.log` to confirm the hook output matches the new
   `hookSpecificOutput` wrapped format

---

## If the IDE Dialog Still Appears After These Fixes

This confirms the v2.1.119 regression is the cause. In that case apply this additional
workaround - add specific tool patterns to `antigravity.commands.alwaysAllow` in
Antigravity's user settings to pre-approve common read-only operations, while keeping
AethelHook as the approval gateway for destructive operations like `run_command`,
`write_file`, and `delete_file`.

The `antigravity.commands.alwaysAllow` setting accepts `exact`, `glob`, and `regex`
match strategies. Example:

```json
{
  "antigravity.commands.alwaysAllow": [
    { "pattern": "read_file(*)", "match": "glob" },
    { "pattern": "list_directory(*)", "match": "glob" }
  ]
}
```

This keeps destructive operations gated behind AethelHook while bypassing the broken
allow suppression path for safe read-only tools.

---

## Summary of Changes Required

| File | Change |
|------|--------|
| `C:\AethelHook\.gemini\hooks\on_approval_request.ps1` | Wrap all permission outputs inside `hookSpecificOutput` |
| `C:\Users\Moloi\.claude\settings.json` | Create/overwrite with hook config and defaultMode |
| `C:\AethelHook\.agents\hooks.json` | No change needed |
| `C:\AethelHook\AethelHook.API\Program.cs` | No change needed |
| Android app | No change needed |
