# AethelHook Approval Request Hook - GitHub Copilot CLI edition
# Fires via the permissionRequest hook before any tool call (no matcher configured -
# see RestoreCopilotHooks() in Program.cs, fires for every tool by design, same broad
# "gate everything by default" philosophy as Gemini CLI's matcher "*").
#
# Contract (live-verified 2026-07-22 against real @github/copilot 1.0.73): stdout JSON
# {"behavior":"deny","message":"..."} + exit 0 genuinely blocks the tool call -
# confirmed with --yolo active, so unlike Antigravity's native dialogs, full
# auto-approval does NOT bypass this hook.
#
# CRITICAL timing constraint: Copilot's own hook timeout is fail-OPEN (a hook that
# doesn't respond in time is treated as if it allowed the action) - the opposite of
# every other agent's safe-default in this project. RestoreCopilotHooks() configures
# this hook's own "timeoutSec" generously (100s) specifically so this script's
# wait-for-phone-decision call (below, 90s) always finishes and returns an explicit
# JSON decision well before Copilot's own timeout could ever fail open.

$debugLog = "C:\ProgramData\AethelHook\hook_debug_copilot.log"
function Log($msg) {
    $line = "$(Get-Date -Format 'HH:mm:ss') $msg"
    try { $line | Out-File -FilePath $debugLog -Append -Encoding utf8 -ErrorAction SilentlyContinue } catch {}
    [Console]::Error.WriteLine($line)
}
Log "--- Copilot CLI permissionRequest Hook Triggered ---"

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

# Real field names confirmed live: hookName, sessionId, timestamp, cwd, toolName,
# toolInput (an object whose shape varies per tool - toolInput.command for the
# powershell/shell tool; other tools' exact field names not independently confirmed,
# so this falls back to a compact JSON dump same as the other three agents' hooks.
$toolName = if ($inputData -and $inputData.toolName) { $inputData.toolName } else { "unknown_tool" }

$commandPreview = "Agent is requesting permission to run a tool"
if ($inputData -and $inputData.toolInput) {
    $toolArgs = $inputData.toolInput
    if ($toolArgs.command)        { $commandPreview = "$($toolArgs.command)" }
    elseif ($toolArgs.file_path)  { $commandPreview = "$($toolArgs.file_path)" }
    elseif ($toolArgs.path)       { $commandPreview = "$($toolArgs.path)" }
    elseif ($toolArgs.query)      { $commandPreview = "$($toolArgs.query)" }
    else                          { $commandPreview = $toolArgs | ConvertTo-Json -Compress -Depth 3 -ErrorAction SilentlyContinue }
}

$cmdName = if ($commandPreview) { ($commandPreview -split '[\s"'']+')[0].Trim() } else { $toolName }
# Allow-list keys on the FULL command, never just $cmdName's first word - same
# "always allow git" bug this project already fixed once for Claude/Codex.
$fullCommand = if ($commandPreview) { $commandPreview.Trim() } else { $toolName }
Log "Tool: $toolName | Preview: $commandPreview"

$tokenPath   = "C:\ProgramData\AethelHook\api_token.txt"
$apiToken    = if (Test-Path $tokenPath) { (Get-Content $tokenPath -Raw -Encoding ascii).Trim() } else { "" }
$authHeaders = if ($apiToken) { @{"X-AethelHook-Token" = $apiToken} } else { @{} }

# Phone-managed allow list - shared across every agent (same file/format Claude/
# Codex/Gemini already write to).
$phoneAllowPath = "C:\ProgramData\AethelHook\phone_allow.txt"
if (Test-Path $phoneAllowPath) {
    $allowedCmds = Get-Content $phoneAllowPath -ErrorAction SilentlyContinue |
                   Where-Object { $_.Trim() -ne "" }
    if ($allowedCmds -contains $fullCommand) {
        Log "'$fullCommand' is in phone allow list - auto-approving silently"
        exit 0
    }
}

$sessionId = [System.Guid]::NewGuid().ToString()

$body = @{
    event_type   = "APPROVAL_REQUEST"
    message      = "[$toolName] Approve or Decline?"
    detail       = $commandPreview
    session_id   = $sessionId
    timestamp    = (Get-Date -Format "o")
    tool_name    = $toolName
    command_name = $cmdName
    cwd          = if ($inputData.cwd) { $inputData.cwd } else { "" }
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
    # Fail-open risk: since Copilot's own hook timeout defaults to ALLOWING the
    # action, an unreachable API must still return an explicit deny here rather than
    # letting the script exit with no output.
    Log "API unreachable - denying (AethelHook service offline): $_"
    Write-Output '{"behavior":"deny","message":"AethelHook service is offline. Start the service and retry."}'
    exit 0
}

# Wait up to 90s for the phone - deliberately UNDER Copilot's own configured
# timeoutSec (100s, see RestoreCopilotHooks()) so this script always finishes and
# returns an explicit decision before Copilot's fail-open timeout could trigger.
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
        Write-Output '{"behavior":"allow"}'
        exit 0
    }
    { $_ -in "always_allow_project", "always_allow_global" } {
        "$fullCommand" | Out-File -FilePath $phoneAllowPath -Append -ErrorAction SilentlyContinue
        Log "Added '$fullCommand' to phone allow list"
        Write-Output '{"behavior":"allow"}'
        exit 0
    }
    "deny_with_reason" {
        $reason = if ($internalReason) { $internalReason } else { "User declined via phone" }
        Log "Denied with reason: $reason"
        Write-Output (@{ behavior = "deny"; message = $reason } | ConvertTo-Json -Compress)
        exit 0
    }
    "deny" {
        Log "Denied"
        Write-Output '{"behavior":"deny","message":"Denied via phone"}'
        exit 0
    }
    default {
        Log "No phone response - denying (safe default)"
        Write-Output '{"behavior":"deny","message":"No phone response (timed out)"}'
        exit 0
    }
}
