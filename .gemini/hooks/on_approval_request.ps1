# AethelHook Approval Request Hook - Antigravity edition
# Fires via PreToolUse before dangerous tool calls.
# Blocks until user taps phone, then returns Antigravity permissionDecision JSON.
# stdout: clean JSON only  |  stderr: debug (goes to Antigravity agent logs)

$debugLog = "C:\ProgramData\AethelHook\hook_debug.log"
function Log($msg) {
    $line = "$(Get-Date -Format 'HH:mm:ss') $msg"
    try { [System.IO.File]::AppendAllText($debugLog, "$line`r`n", [System.Text.Encoding]::UTF8) } catch {}
    [Console]::Error.WriteLine($line)
}
Log "--- Approval Request Hook Triggered ---"

# --- Read tool input from stdin (3s timeout) ---
$inputData = $null
try {
    $stdinReader = [System.IO.StreamReader]::new([Console]::OpenStandardInput(), [System.Text.Encoding]::UTF8)
    $stdinTask = $stdinReader.ReadToEndAsync()
    if ($stdinTask.Wait(3000)) {
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

# --- Build human-readable description for the notification ---
$toolName       = "unknown_tool"
$commandPreview = "Agent is requesting permission to run a tool"

if ($inputData) {
    $toolCall = $null
    if ($inputData.toolCall) {
        $toolCall = $inputData.toolCall
        if ($toolCall.name) { $toolName = $toolCall.name }
    } elseif ($inputData.tool_name) { $toolName = $inputData.tool_name }

    $toolArgs = $null
    if ($toolCall -and $toolCall.args) { $toolArgs = $toolCall.args }
    elseif ($inputData.tool_input)     { $toolArgs = $inputData.tool_input }

    if ($toolArgs) {
        if ($toolArgs.CommandLine)     { $commandPreview = "$($toolArgs.CommandLine)" }
        elseif ($toolArgs.command)     { $commandPreview = "$($toolArgs.command)" }
        elseif ($toolArgs.TargetFile)  { $commandPreview = "$($toolArgs.TargetFile)" }
        elseif ($toolArgs.path)        { $commandPreview = "$($toolArgs.path)" }
        else { $commandPreview = $toolArgs | ConvertTo-Json -Compress -Depth 3 -ErrorAction SilentlyContinue }
    }
}

$cmdName = if ($commandPreview) { ($commandPreview -split '[\s"'']+')[0].Trim() } else { $toolName }
Log "Tool: $toolName | Cmd: $cmdName | Preview: $commandPreview"
# The allow-list must key on the FULL command, never just $cmdName (its first word) -
# matching on the first token only meant "always allow" on e.g. "git" silently
# auto-approved any later command merely starting with "git ". $cmdName is kept only
# for the human-readable log/summary.
$fullCommand = if ($commandPreview) { $commandPreview.Trim() } else { $toolName }

# --- Phone allow list: auto-approve silently without notifying ---
$phoneAllowPath = "C:\ProgramData\AethelHook\phone_allow.txt"
if (Test-Path $phoneAllowPath) {
    $allowedCmds = Get-Content $phoneAllowPath -ErrorAction SilentlyContinue |
                   Where-Object { $_.Trim() -ne "" }
    if ($allowedCmds -contains $fullCommand) {
        Log "'$fullCommand' is in phone allow list - auto-approving silently"
        Write-Output '{"hookSpecificOutput":{"permissionDecision":"allow"}}'
        exit 0
    }
}

# --- Load API token ---
$tokenPath   = "C:\ProgramData\AethelHook\api_token.txt"
$apiToken    = if (Test-Path $tokenPath) { (Get-Content $tokenPath -Raw -Encoding ascii).Trim() } else { "" }
$authHeaders = if ($apiToken) { @{"X-AethelHook-Token" = $apiToken} } else { @{} }

# --- POST event to AethelHook API ---
$sessionId = [System.Guid]::NewGuid().ToString()
$body = @{
    event_type   = "APPROVAL_REQUEST"
    message      = "[${toolName}] Approve or Decline?"
    detail       = $commandPreview
    session_id   = $sessionId
    timestamp    = (Get-Date -Format "o")
    tool_name    = $toolName
    command_name = $cmdName
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
    Log "API unreachable - deferring to IDE dialog: $_"
    Write-Output '{"hookSpecificOutput":{"permissionDecision":"ask"}}'
    exit 0
}

# --- Block until user taps phone (up to 80s) ---
$internalDecision = "deny"
$internalReason   = ""
try {
    $response = Invoke-WebRequest `
        -Uri "http://localhost:5266/hook/wait-decision/$sessionId" `
        -Method GET `
        -Headers $authHeaders `
        -TimeoutSec 80 `
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

# --- Map phone decision to Antigravity permissionDecision ---
switch ($internalDecision) {
    { $_ -in "allow", "allow_once" } {
        Log "Decision: ALLOW"
        Write-Output '{"hookSpecificOutput":{"permissionDecision":"allow"}}'
        exit 0
    }
    { $_ -in "always_allow_project", "always_allow_global" } {
        "$fullCommand" | Out-File -FilePath $phoneAllowPath -Append -ErrorAction SilentlyContinue
        Log "Added '$fullCommand' to phone allow list (decision: $internalDecision)"
        Log "Decision: ALLOW (always)"
        Write-Output '{"hookSpecificOutput":{"permissionDecision":"allow"}}'
        exit 0
    }
    "deny_with_reason" {
        $safeReason = if ($internalReason) { $internalReason } else { "User declined via phone" }
        Log "Decision: DENY (reason: $safeReason)"
        Write-Output (@{ hookSpecificOutput = @{ permissionDecision = "deny"; permissionDecisionReason = $safeReason } } | ConvertTo-Json -Compress -Depth 5)
        exit 2
    }
    "deny" {
        Log "Decision: DENY"
        Write-Output '{"hookSpecificOutput":{"permissionDecision":"deny","permissionDecisionReason":"Denied via phone"}}'
        exit 2
    }
    default {
        Log "No phone response for '$internalDecision' - blocking silently"
        Write-Output '{"hookSpecificOutput":{"permissionDecision":"deny","permissionDecisionReason":"No phone response (timed out)"}}'
        exit 2
    }
}
