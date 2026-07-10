# Antigravity Hook System — Definitive Reference

> All answers are derived from (a) live stdin payloads captured in `hook_debug.log`, (b) the working `hooks.json` / hook scripts in this workspace, and (c) observed runtime behavior. Nothing here is speculative.

---

## Q1 — PreToolUse Hook

**Yes.** Antigravity has a `PreToolUse` hook event that runs a shell command **synchronously** before any matched tool executes.

### Coverage

The `matcher` field controls which tools trigger the hook. Supported values:

- **Exact tool name** — `"run_command"`, `"write_file"`, `"replace_file_content"`, `"multi_replace_file_content"`, `"ask_permission"`, etc.
- **Wildcard** — `"*"` matches every tool call (confirmed working; was set during early testing).
- **Any registered tool name** — includes MCP tools (see Q9).

### Enforcement

**Hard enforcement boundary**, not a soft guardrail. When the hook exits `2` (deny), the tool call is fully blocked — the agent receives an error and cannot retry the same call. There is no bypass path; every matched tool call passes through the hook before execution, including recursive tool calls made by the hook script itself (this caused double-firing during development).

> **Known caveat:** The hook fires on the hook's *own* tool calls too if the matcher is `"*"`. Scope your matcher to specific tool names to avoid loops.

---

## Q2 — Stop / PostTurn Hook

**Yes.** Three event names all fire when the agent finishes its turn / returns control to the user:

| Event name | When it fires |
|---|---|
| `Stop` | Agent explicitly stops (end of turn) |
| `AfterAgent` | After an agent sub-process completes |
| `SessionEnd` | Session teardown |

All three are registered in the working config and invoke the same `on_task_complete.ps1` script. In practice, **`Stop` is the primary "agent done" event** for a normal conversation turn.

These hooks are **fire-and-forget** — their exit code and stdout are ignored (they cannot block or redirect). A hook that hangs here will hold up the session; keep them short.

---

## Q3 — Hook Config Format & Location

### File format

**JSON** (`.json`). The schema is:

```json
{
  "hooks": {
    "<EventName>": [ ... ]
  }
}
```

### Config paths (in priority order)

| Scope | Path |
|---|---|
| Global (user-level) | `C:\Users\<user>\.gemini\config\hooks.json` |
| Project-level | `<workspace>\.agents\hooks.json` |
| Redirect pointer | `<workspace>\.gemini\settings.json` → `"hooksPath"` key |

The `hooksPath` redirect in `.gemini\settings.json` lets you point to any JSON file:

```json
{ "hooksPath": "C:\\AethelHook\\.agents\\hooks.json" }
```

Both the global and project configs are loaded and **merged** — if both define `PreToolUse` matchers, both fire.

### Minimal working PreToolUse example

```json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "run_command",
        "hooks": [
          {
            "type": "command",
            "command": "powershell.exe -ExecutionPolicy Bypass -File C:\\path\\to\\hook.ps1",
            "timeout": 90
          }
        ]
      }
    ]
  }
}
```

Required fields per hook entry:

| Field | Description |
|---|---|
| `"type"` | Always `"command"` |
| `"command"` | The shell string to execute (run through the system shell, not exec'd directly — see Q10) |
| `"timeout"` | Integer seconds; hook process is killed if it exceeds this |

---

## Q4 — Stdin JSON Format

**Exact structure from live captured payloads:**

```json
{
  "artifactDirectoryPath": "C:/Users/Moloi/.gemini/antigravity/brain/<conversationId>",
  "conversationId": "e6c681d6-cbf0-473b-909e-e6809bc1ec97",
  "stepIdx": 2085,
  "toolCall": {
    "name": "run_command",
    "args": {
      "CommandLine": "echo \"AethelHook permission test\"",
      "Cwd": "C:\\AethelHook",
      "WaitMsBeforeAsync": 90000
    }
  },
  "transcriptPath": "C:/Users/Moloi/.gemini/antigravity/brain/<conversationId>/.system_generated/logs/transcript.jsonl",
  "workspacePaths": ["c:/AethelHook"]
}
```

### Field reference

| Field | Type | Description |
|---|---|---|
| `conversationId` | string (UUID) | Unique session/conversation identifier — use this as your session key |
| `stepIdx` | integer | Turn/step index within the conversation |
| `toolCall.name` | string | The tool being called, e.g. `"run_command"`, `"write_file"` |
| `toolCall.args` | object | All arguments passed to the tool |
| `toolCall.args.CommandLine` | string | **For `run_command`: the shell command string** |
| `toolCall.args.Cwd` | string | Working directory for the command |
| `toolCall.args.WaitMsBeforeAsync` | integer | Async delay in ms (`run_command` specific) |
| `toolCall.args.TargetFile` | string | For file-write tools: absolute path to the file |
| `artifactDirectoryPath` | string | Path to the agent's artifact directory for this conversation |
| `transcriptPath` | string | Path to the live JSONL conversation transcript |
| `workspacePaths` | string[] | Active workspace root paths |

> **No separate "turn ID" field.** Use `conversationId` + `stepIdx` together as your unique request key.

### Key field locations by tool

| Tool | Command / path field |
|---|---|
| `run_command` | `toolCall.args.CommandLine` |
| `write_to_file` | `toolCall.args.TargetFile` |
| `replace_file_content` | `toolCall.args.TargetFile` |
| `multi_replace_file_content` | `toolCall.args.TargetFile` |
| `ask_permission` | `toolCall.args.Action`, `toolCall.args.Target` |

---

## Q5 — Block Mechanism (Deny)

To block/deny a tool call, your hook must do **both**:

1. Write this JSON to **stdout**:

```json
{"hookSpecificOutput":{"permissionDecision":"deny","permissionDecisionReason":"reason text"}}
```

2. Exit with code **`2`**.

Both are required. Exit code `2` signals the engine to hard-block. The `permissionDecisionReason` string is surfaced to the agent as the error message.

**PowerShell example:**

```powershell
Write-Output '{"hookSpecificOutput":{"permissionDecision":"deny","permissionDecisionReason":"Denied by phone"}}'
exit 2
```

> **Critical:** Any non-JSON text on stdout before the JSON object will corrupt parsing. Send all debug/log output to **stderr** using `[Console]::Error.WriteLine(...)`.

---

## Q6 — Allow Mechanism (Pass-through)

Two options:

**Option A — Explicit allow (recommended):**

```powershell
Write-Output '{"hookSpecificOutput":{"permissionDecision":"allow"}}'
exit 0
```

This also **suppresses the native IDE permission dialog** (see Q8).

**Option B — Silent pass-through:**

Exit `0` with no stdout. The engine treats this as "no objection" and proceeds, but the IDE dialog may still appear.

**To defer to the IDE dialog:**

```powershell
Write-Output '{"hookSpecificOutput":{"permissionDecision":"ask"}}'
exit 0
```

---

## Q7 — Timeout / Hanging Hook

- The `"timeout"` field (integer seconds) sets the hard deadline per hook entry.
- If the hook process does not exit within that time, **the process is killed**.
- **Behavior after kill: auto-deny / block.** The tool call does not proceed when the hook times out.
- There is **no "auto-allow on timeout" mode**.
- Timeout is configurable per hook entry. AethelHook uses:
  - `90` seconds for `PreToolUse` (to allow the phone long-poll)
  - `5` seconds for `Stop` / `SessionEnd` (fire-and-forget notifications)

---

## Q8 — Native Permission Dialog

**Yes**, Antigravity shows its own native IDE permission dialog for tool calls that require user approval (write, execute, etc.).

### Hook output vs. dialog behavior

| Hook stdout | Effect on dialog |
|---|---|
| `"permissionDecision":"allow"` | Dialog **suppressed** — tool executes immediately |
| `"permissionDecision":"deny"` | Dialog **suppressed** — tool is blocked |
| `"permissionDecision":"ask"` | Dialog **still appears** — hook defers to IDE |
| Exit 0, no stdout | Dialog **still appears** — hook defers to IDE |

**The hook fires concurrently with the IDE dialog appearing**, not before it. The dialog is shown while the hook is long-polling. This is why AethelHook uses `AutoDismiss` (Windows `SendKeys`) to programmatically click the dialog after the phone responds — the hook's JSON output alone does not close an already-open dialog.

Observed `AutoDismiss` keystrokes:

| Decision | Keys sent |
|---|---|
| Approve | `"1{ENTER}"` — selects "Yes, allow this time" and submits |
| Deny | `"{ESC}"` — closes / skips the dialog |

---

## Q9 — MCP Tool Interception

**Yes.** MCP tool calls appear in the hook stdin with the tool name in `toolCall.name` using the convention:

```
mcp__<serverName>__<toolName>
```

**Example:** `mcp__Neon__run_sql`

You can match them exactly in the `"matcher"` field:

```json
{ "matcher": "mcp__Neon__run_sql", ... }
```

Or use the wildcard `"*"` to catch all tools including MCP.

There is no MCP-specific exclusion path — MCP calls go through the same `PreToolUse` pipeline as native tools.

---

## Q10 — Hook Process Environment

| Property | Value |
|---|---|
| **Execution model** | Run through the **system shell** (not exec'd directly). The `command` string is passed to the shell as an argument. |
| **Working directory** | The **workspace root** (`workspacePaths[0]`), e.g. `C:\AethelHook` |
| **PATH** | Inherits the agent process's PATH — standard Windows system dirs, `dotnet`, `powershell`, etc. are all available. |
| **Environment variables** | Full inherit of the IDE/agent process environment. `USERPROFILE`, `APPDATA`, `TEMP`, etc. are present. |
| **stdin** | UTF-8 JSON payload (see Q4). The pipe may **not be closed** by the caller — use async read with a timeout (3 seconds works reliably). |
| **stdout** | Read by the engine after the hook exits. Must be **clean JSON only** — no extra text, BOM, or log lines. |
| **stderr** | Captured to agent logs. **Safe for debug output** — does not interfere with stdout JSON parsing. |

> **Windows-specific:** PowerShell's `Out-File` defaults to UTF-16LE. Use `-Encoding ascii` or write to `[Console]::Error` for log output to avoid encoding issues.

---

## Quick-Reference Card

```
BLOCK  →  stdout: {"hookSpecificOutput":{"permissionDecision":"deny","permissionDecisionReason":"..."}}
           exit 2

ALLOW  →  stdout: {"hookSpecificOutput":{"permissionDecision":"allow"}}
           exit 0  (also suppresses IDE dialog)

DEFER  →  stdout: {"hookSpecificOutput":{"permissionDecision":"ask"}}
           exit 0  (IDE dialog takes over)

SILENT →  (no stdout)
           exit 0  (IDE dialog takes over)

TIMEOUT → hook killed at "timeout" seconds → auto-block (same as exit 2)
```

```
STDIN KEY FIELDS:
  toolCall.name              → tool name ("run_command", "write_file", ...)
  toolCall.args.CommandLine  → shell command string (run_command only)
  toolCall.args.TargetFile   → file path (write/replace tools)
  toolCall.args.Cwd          → working directory
  conversationId             → session identifier (UUID)
  stepIdx                    → turn counter
```
