# AethelHook Approval Request Hook - Devin CLI edition
#
# Scope: the STANDALONE terminal Devin CLI only (interactive `devin` or headless
# `devin -p`), NOT the Devin IDE/Desktop app. Confirmed live 2026-07-22: Devin Desktop
# runs devin.exe in ACP (Agent Client Protocol) mode as a subprocess of the editor, and
# ACP mode never fires hooks at all - permission decisions are handed to the connected
# editor client instead (confirmed via Devin's own bundled Xcode ACP doc: "Some richer
# interactions are only available in the standalone CLI"). A fresh ACP process (verified
# via PID/start-time, well after config.json was edited) produced zero trace of hooks
# ever loading. This script only helps if Devin is actually run from a terminal.
#
# Fires via PreToolUse (matcher "" - matches every tool: exec/edit/read/grep/glob), see
# RestoreDevinHooks() in Program.cs. Confirmed live: PreToolUse fires unconditionally
# regardless of --permission-mode, including "dangerous" (Devin's most permissive
# auto-approve-everything mode) - adversarially verified a block decision genuinely
# prevents the real command from running even under dangerous mode (agent received
# "Error: A tool was rejected by the user", no actual git status output).
#
# Contract: stdout JSON {"decision":"block","reason":"..."} + exit 0 blocks the call.
# No output (exit 0) allows it. Devin's own docs note a hook that errors or exceeds its
# declared timeout is logged but does NOT block (fails OPEN, same danger as Copilot's
# hook) - the declared "timeout" in RestoreDevinHooks() and this script's own
# wait-decision poll below are sized with headroom specifically to avoid ever hitting
# that fail-open path in practice.

$debugLog = "C:\ProgramData\AethelHook\hook_debug_devincli.log"
function Log($msg) {
    $line = "$(Get-Date -Format 'HH:mm:ss') $msg"
    try { $line | Out-File -FilePath $debugLog -Append -Encoding utf8 -ErrorAction SilentlyContinue } catch {}
    [Console]::Error.WriteLine($line)
}
Log "--- Devin CLI PreToolUse Hook Triggered ---"

$inputData = $null
try {
    $stdinReader = [System.IO.StreamReader]::new([Console]::OpenStandardInput(), [System.Text.Encoding]::UTF8)
    $stdinTask = $stdinReader.ReadToEndAsync()
    if ($stdinTask.Wait(5000)) {
        $rawInput = $stdinTask.Result
        if ($rawInput) {
            Log "stdin: $rawInput"
            $inputData = $rawInput | ConvertFrom-Json
        }
    } else {
        Log "stdin read timed out - continuing without input"
    }
} catch {
    Log "Failed to parse stdin: $_"
}

$toolName = if ($inputData -and $inputData.tool_name) { $inputData.tool_name } else { "unknown_tool" }

# Preview extraction - tool_input.command confirmed live for exec. The rest
# (edit/read/grep/glob) follow Devin's documented field names but are unconfirmed live;
# falls back to a compact JSON dump either way so nothing is ever silently blank.
$commandPreview = "Agent is requesting permission to run a tool"
if ($inputData -and $inputData.tool_input) {
    $toolArgs = $inputData.tool_input
    if ($toolArgs.command)      { $commandPreview = "$($toolArgs.command)" }
    elseif ($toolArgs.path)     { $commandPreview = "$($toolArgs.path)" }
    elseif ($toolArgs.pattern)  { $commandPreview = "$($toolArgs.pattern)" }
    elseif ($toolArgs.query)    { $commandPreview = "$($toolArgs.query)" }
    else                        { $commandPreview = $toolArgs | ConvertTo-Json -Compress -Depth 3 -ErrorAction SilentlyContinue }
}

$fullCommand = if ($commandPreview) { $commandPreview.Trim() } else { $toolName }
# Allow-list keys on the FULL command, never just its first word - see CLAUDE.md's
# security-review gotcha on why a first-word-only match is unsafe.
$cmdName = ($fullCommand -split '[\s"'']+')[0].Trim()
Log "Tool: $toolName | Preview: $commandPreview"

$tokenPath   = "C:\ProgramData\AethelHook\api_token.txt"
$apiToken    = if (Test-Path $tokenPath) { (Get-Content $tokenPath -Raw -Encoding ascii).Trim() } else { "" }
$authHeaders = if ($apiToken) { @{"X-AethelHook-Token" = $apiToken} } else { @{} }

# Phone-managed allow list - shared across every agent (same file/format Claude, Codex,
# Gemini already write to).
$phoneAllowPath = "C:\ProgramData\AethelHook\phone_allow.txt"
if (Test-Path $phoneAllowPath) {
    $allowedCmds = Get-Content $phoneAllowPath -ErrorAction SilentlyContinue |
                   Where-Object { $_.Trim() -ne "" }
    if ($allowedCmds -contains $fullCommand) {
        Log "'$fullCommand' is in phone allow list - auto-approving silently"
        exit 0
    }
}

# Fresh GUID per call, never Devin's own memorable word-pair session_id (e.g.
# "fortune-bass") - see CLAUDE.md gotcha #6 on why reusing a shared session id
# contaminates unrelated calls.
$sessionId = [System.Guid]::NewGuid().ToString()

# DEVIN_PROJECT_DIR is set automatically by Devin CLI per its own hooks docs - there is
# no cwd field on the PreToolUse payload itself.
$cwd = if ($env:DEVIN_PROJECT_DIR) { $env:DEVIN_PROJECT_DIR } else { "" }

$body = @{
    event_type   = "APPROVAL_REQUEST"
    message      = "[$toolName] Approve or Decline?"
    detail       = $commandPreview
    session_id   = $sessionId
    timestamp    = (Get-Date -Format "o")
    tool_name    = $toolName
    command_name = $cmdName
    cwd          = $cwd
} | ConvertTo-Json -Compress

Log "Posting event to API with session $sessionId ..."

try {
    $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($body)
    Invoke-WebRequest `
        -Uri "http://localhost:5266/hook/event" `
        -Method POST `
        -ContentType "application/json; charset=utf-8" `
        -Headers $authHeaders `
        -Body $bodyBytes `
        -TimeoutSec 5 `
        -UseBasicParsing `
        -ErrorAction Stop | Out-Null
    Log "Event posted OK"
} catch {
    Log "API unreachable - blocking (AethelHook service offline): $_"
    Write-Output '{"decision":"block","reason":"AethelHook service is offline. Start the service and retry."}'
    exit 0
}

# Block until user taps on phone. Kept comfortably under the 100s "timeout" declared in
# RestoreDevinHooks() - Devin fails OPEN (does not block) if this hook exceeds its
# declared timeout, so this budget must always finish first.
$internalDecision = "deny"
$internalReason   = ""
try {
    $response = Invoke-WebRequest `
        -Uri "http://localhost:5266/hook/wait-decision/$sessionId" `
        -Method GET `
        -Headers $authHeaders `
        -TimeoutSec 90 `
        -UseBasicParsing `
        -ErrorAction Stop

    if ($response.Content) {
        $resObj = $response.Content | ConvertFrom-Json
        if ($resObj.decision) {
            $internalDecision = $resObj.decision
            $internalReason   = if ($resObj.reason) { $resObj.reason } else { "" }
        }
    }
} catch {
    Log "Wait-decision failed (timeout or error): $_"
    $internalDecision = "deny"
}

Log "Internal decision: $internalDecision"

switch ($internalDecision) {
    { $_ -in "allow", "allow_once" } {
        Log "Allowed"
        exit 0
    }
    { $_ -in "always_allow_project", "always_allow_global" } {
        "$fullCommand" | Out-File -FilePath $phoneAllowPath -Append -ErrorAction SilentlyContinue
        Log "Added '$fullCommand' to phone allow list"
        exit 0
    }
    "deny_with_reason" {
        $reason = if ($internalReason) { $internalReason } else { "User declined via phone" }
        Log "Denied with reason: $reason"
        Write-Output (@{ decision = "block"; reason = $reason } | ConvertTo-Json -Compress)
        exit 0
    }
    "deny" {
        Log "Denied"
        Write-Output '{"decision":"block","reason":"Denied via phone"}'
        exit 0
    }
    default {
        Log "No phone response - denying (safe default)"
        Write-Output '{"decision":"block","reason":"No phone response (timed out)"}'
        exit 0
    }
}
