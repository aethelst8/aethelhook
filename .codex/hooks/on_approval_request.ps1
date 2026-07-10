# AethelHook Approval Request Hook - Codex edition
# Fires via PreToolUse before Bash / apply_patch tool calls.
# Exit 0 = allow, Exit 2 + JSON = block.

$debugLog = "C:\ProgramData\AethelHook\hook_debug_codex.log"
function Log($msg) {
    $line = "$(Get-Date -Format 'HH:mm:ss') $msg"
    $line | Out-File -FilePath $debugLog -Append -Encoding ascii
    [Console]::Error.WriteLine($line)
}
Log "--- Codex Approval Request Hook Triggered ---"

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

# Extract tool name and command preview
# Codex sends: tool_name, tool_use_id, tool_input (tool_input.command for Bash)
$toolName       = "unknown_tool"
$commandPreview = "Agent is requesting permission to run a tool"

if ($inputData) {
    if ($inputData.tool_name) {
        $toolName = $inputData.tool_name
    } elseif ($inputData.toolCall -and $inputData.toolCall.name) {
        $toolName = $inputData.toolCall.name
    }

    $toolArgs = $null
    if ($inputData.tool_input) {
        $toolArgs = $inputData.tool_input
    } elseif ($inputData.toolCall -and $inputData.toolCall.args) {
        $toolArgs = $inputData.toolCall.args
    }

    if ($toolArgs) {
        if ($toolArgs.command) {
            $commandPreview = "$($toolArgs.command)"
        } elseif ($toolArgs.patch) {
            $commandPreview = "apply_patch: $($toolArgs.patch -replace '\n',' ' | Select-Object -First 1)"
        } elseif ($toolArgs.file_path) {
            $commandPreview = "$($toolArgs.file_path)"
        } elseif ($toolArgs.path) {
            $commandPreview = "$($toolArgs.path)"
        } else {
            $commandPreview = $toolArgs | ConvertTo-Json -Compress -Depth 3 -ErrorAction SilentlyContinue
        }
    }
}

$cmdName = if ($commandPreview) { ($commandPreview -split '[\s"'']+')[0].Trim() } else { $toolName }

# Strip PowerShell call operator: & 'path\to\exe.exe' -args → exe
# Handle quoted paths with spaces (e.g. & 'C:\Program Files\...\bash.exe')
if ($cmdName -eq "&") {
    $rest = $commandPreview.Substring(1).TrimStart()
    if ($rest.Length -gt 0) {
        $q = $rest[0]
        if ($q -eq "'" -or $q -eq '"') {
            $end = $rest.IndexOf([string]$q, 1)
            if ($end -gt 1) { $cmdName = [System.IO.Path]::GetFileNameWithoutExtension($rest.Substring(1, $end - 1)) }
        } else {
            $cmdName = [System.IO.Path]::GetFileNameWithoutExtension(($rest -split '\s+')[0])
        }
    }
}

# Unwrap "bash -lc <cmd>" or "& 'bash.exe' -lc <cmd>" — the .? after exe handles the closing quote
if ($commandPreview -match '^(?:bash|.*bash\.exe.?)\s+-\w+\s+(.+)$') {
    $inner     = $Matches[1].Trim('"', "'")
    $unwrapped = ($inner -split '[\s"'']+')[0].Trim()
    if ($unwrapped) { $cmdName = $unwrapped }
}

# The allow-list must key on the FULL command, never just $cmdName (its unwrapped first
# word) - matching on the first token only meant "always allow" on e.g. "git" silently
# auto-approved any later command merely starting with "git ". $cmdName (including its
# "& 'exe'"/"bash -lc" unwrapping above) is kept only for the human-readable log/summary.
$fullCommand = if ($commandPreview) { $commandPreview.Trim() } else { $toolName }

# Codex turn_id — used by the API to deduplicate parallel tool calls in the same turn
$codexTurnId = if ($inputData -and $inputData.turn_id) { [string]$inputData.turn_id } else { "" }

Log "Tool: $toolName | Cmd: $cmdName | TurnId: $codexTurnId | Preview: $commandPreview"

# Load API token
$tokenPath   = "C:\ProgramData\AethelHook\api_token.txt"
$apiToken    = if (Test-Path $tokenPath) { (Get-Content $tokenPath -Raw -Encoding ascii).Trim() } else { "" }
$authHeaders = if ($apiToken) { @{"X-AethelHook-Token" = $apiToken} } else { @{} }

# Check phone-managed allow list
$phoneAllowPath = "C:\ProgramData\AethelHook\phone_allow.txt"
if (Test-Path $phoneAllowPath) {
    $allowedCmds = Get-Content $phoneAllowPath -ErrorAction SilentlyContinue |
                   Where-Object { $_.Trim() -ne "" }
    if ($allowedCmds -contains $fullCommand) {
        Log "'$fullCommand' is in phone allow list - auto-approving silently"
        exit 0
    }
}

# Session ID for this hook invocation
$sessionId = [System.Guid]::NewGuid().ToString()

# POST the event to the local API
$body = @{
    event_type    = "APPROVAL_REQUEST"
    message       = "[$toolName] Approve or Decline?"
    detail        = $commandPreview
    session_id    = $sessionId
    timestamp     = (Get-Date -Format "o")
    tool_name     = $toolName
    command_name  = $cmdName
    codex_turn_id = $codexTurnId
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
    exit 2
}

# Block until user taps on phone (up to 80s)
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
        $reason  = if ($internalReason) { $internalReason } else { "User declined via phone" }
        Log "Denied with reason: $reason"
        Write-Output (@{ decision = "block"; reason = $reason } | ConvertTo-Json -Compress)
        exit 2
    }
    "deny" {
        Log "Denied"
        Write-Output '{"decision":"block","reason":"Denied via phone"}'
        exit 2
    }
    default {
        Log "No phone response - blocking (safe default)"
        Write-Output '{"decision":"block","reason":"No phone response (timed out)"}'
        exit 2
    }
}
