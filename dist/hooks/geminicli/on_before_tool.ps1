# AethelHook Approval Request Hook - Gemini CLI edition
# Fires via the BeforeTool hook before every tool call (matcher "*" - see
# RestoreGeminiHooks() in Program.cs). Gemini's own tool registry has no equivalent to
# Claude's PreToolUse matcher-per-tool config, so this script itself filters out pure
# bookkeeping tools (update_topic, list_background_processes) rather than maintaining a
# hooks.json matcher list - same "exempt no-side-effect tools" precedent as TodoWrite
# (Claude) / todowrite (OpenCode, see gotcha #28).
#
# Contract (live-verified 2026-07-21 against real gemini-cli 0.51.0): stdout JSON
# {"decision":"deny","reason":"..."} + exit 0 genuinely blocks the tool call - confirmed
# with --approval-mode yolo active, so unlike Antigravity's native dialogs, YOLO mode
# does NOT bypass this hook. Returning nothing (exit 0, no stdout) allows the call.

$debugLog = "C:\ProgramData\AethelHook\hook_debug_geminicli.log"
function Log($msg) {
    $line = "$(Get-Date -Format 'HH:mm:ss') $msg"
    try { $line | Out-File -FilePath $debugLog -Append -Encoding utf8 -ErrorAction SilentlyContinue } catch {}
    [Console]::Error.WriteLine($line)
}
Log "--- Gemini CLI BeforeTool Hook Triggered ---"

# Read tool input from stdin
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

# Pure bookkeeping tools - no filesystem/execution/network side effects, so gating them
# would just be phone-approval spam with zero safety benefit (confirmed live: update_topic
# only ever carries a summary/title string, list_background_processes only ever lists
# AethelHook's own headless runner's child processes).
if ($toolName -in @("update_topic", "list_background_processes")) {
    Log "Exempt bookkeeping tool '$toolName' - auto-allowing"
    exit 0
}

# Extract a human-readable preview from tool_input - field names confirmed live per tool
# (run_shell_command.command, write_file/read_file.file_path, list_directory.dir_path,
# google_web_search/grep_search.query, invoke_agent.prompt). Falls back to a compact JSON
# dump for any tool whose shape isn't one of these, same as the Codex/Claude editions.
$commandPreview = "Agent is requesting permission to run a tool"
if ($inputData -and $inputData.tool_input) {
    $toolArgs = $inputData.tool_input
    if ($toolArgs.command)        { $commandPreview = "$($toolArgs.command)" }
    elseif ($toolArgs.file_path)  { $commandPreview = "$($toolArgs.file_path)" }
    elseif ($toolArgs.dir_path)   { $commandPreview = "$($toolArgs.dir_path)" }
    elseif ($toolArgs.query)      { $commandPreview = "$($toolArgs.query)" }
    elseif ($toolArgs.prompt)     { $commandPreview = "$($toolArgs.prompt)" }
    elseif ($toolArgs.path)       { $commandPreview = "$($toolArgs.path)" }
    else                          { $commandPreview = $toolArgs | ConvertTo-Json -Compress -Depth 3 -ErrorAction SilentlyContinue }
}

$cmdName = if ($commandPreview) { ($commandPreview -split '[\s"'']+')[0].Trim() } else { $toolName }
# Allow-list keys on the FULL command, never just $cmdName's first word - matching only
# the first token would let "always allow" on e.g. "git" silently auto-approve any later
# command merely starting with "git " (the same bug this project already fixed once for
# Claude/Codex - see the security-review gotcha in CLAUDE.md).
$fullCommand = if ($commandPreview) { $commandPreview.Trim() } else { $toolName }
Log "Tool: $toolName | Preview: $commandPreview"

# Load API token for request authentication
$tokenPath   = "C:\ProgramData\AethelHook\api_token.txt"
$apiToken    = if (Test-Path $tokenPath) { (Get-Content $tokenPath -Raw -Encoding ascii).Trim() } else { "" }
$authHeaders = if ($apiToken) { @{"X-AethelHook-Token" = $apiToken} } else { @{} }

# Phone-managed allow list - shared across every agent (same file/format Claude and Codex
# already write to), so "always allow" from any agent's approval covers the same command
# everywhere.
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
    Log "API unreachable - blocking (AethelHook service offline): $_"
    Write-Output '{"decision":"deny","reason":"AethelHook service is offline. Start the service and retry."}'
    exit 0
}

# Block until user taps on phone (up to 5 min)
$internalDecision = "deny"
$internalReason   = ""
try {
    $response = Invoke-WebRequest `
        -Uri "http://localhost:5266/hook/wait-decision/$sessionId" `
        -Method GET `
        -Headers $authHeaders `
        -TimeoutSec 320 `
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
        Write-Output (@{ decision = "deny"; reason = $reason } | ConvertTo-Json -Compress)
        exit 0
    }
    "deny" {
        Log "Denied"
        Write-Output '{"decision":"deny","reason":"Denied via phone"}'
        exit 0
    }
    default {
        Log "No phone response - denying (safe default)"
        Write-Output '{"decision":"deny","reason":"No phone response (timed out)"}'
        exit 0
    }
}
