# AethelHook Approval Request Hook - Claude Code edition
# Fires via PreToolUse before Bash / Write / Edit tool calls.
# Exit 0 = allow, Exit 2 = block (stdout on exit 2 is shown to Claude as the reason).

$debugLog = "C:\ProgramData\AethelHook\hook_debug.log"
function Log($msg) {
    $line = "$(Get-Date -Format 'HH:mm:ss') $msg"
    try { $line | Out-File -FilePath $debugLog -Append -Encoding ascii } catch {}
    [Console]::Error.WriteLine($line)
}
Log "--- Approval Request Hook Triggered ---"

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
        } elseif ($toolArgs.file_path) {
            $commandPreview = "$($toolArgs.file_path)"
        } elseif ($toolArgs.CommandLine) {
            $commandPreview = "$($toolArgs.CommandLine)"
        } elseif ($toolArgs.TargetFile) {
            $commandPreview = "$($toolArgs.TargetFile)"
        } elseif ($toolArgs.path) {
            $commandPreview = "$($toolArgs.path)"
        } else {
            $commandPreview = $toolArgs | ConvertTo-Json -Compress -Depth 3 -ErrorAction SilentlyContinue
        }
    }
}

$cmdName = if ($commandPreview) { ($commandPreview -split '[\s"'']+')[0].Trim() } else { $toolName }
Log "Tool: $toolName | Cmd: $cmdName | Preview: $commandPreview"

# Load API token for request authentication
$tokenPath = "C:\ProgramData\AethelHook\api_token.txt"
$apiToken  = if (Test-Path $tokenPath) { (Get-Content $tokenPath -Raw -Encoding ascii).Trim() } else { "" }
$authHeaders = if ($apiToken) { @{"X-AethelHook-Token" = $apiToken} } else { @{} }

# Check phone-managed allow list (auto-approve silently, no notification)
$phoneAllowPath = "C:\ProgramData\AethelHook\phone_allow.txt"
if (Test-Path $phoneAllowPath) {
    $allowedCmds = Get-Content $phoneAllowPath -ErrorAction SilentlyContinue |
                   Where-Object { $_.Trim() -ne "" }
    if ($allowedCmds -contains $cmdName) {
        Log "'$cmdName' is in phone allow list - auto-approving silently"
        exit 0
    }
}

# Session ID for this hook invocation
$sessionId = [System.Guid]::NewGuid().ToString()

# POST the event to the local API
$body = @{
    event_type   = "APPROVAL_REQUEST"
    message      = "[$toolName] Approve or Decline?"
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
    $httpResponse = $_.Exception.Response
    if ($httpResponse) {
        # API is reachable but returned an HTTP error — do NOT self-destruct, just log and block this call
        $statusCode = [int]$httpResponse.StatusCode
        try {
            $errStream = $httpResponse.GetResponseStream()
            $errBody   = (New-Object System.IO.StreamReader($errStream)).ReadToEnd()
            Log "API returned HTTP $statusCode - body: $errBody"
        } catch { Log "API returned HTTP $statusCode (could not read body)" }

        # Log body bytes as base64 for offline diagnostics
        try {
            $bodyBytes  = [System.Text.Encoding]::UTF8.GetBytes($body)
            $bodyBase64 = [Convert]::ToBase64String($bodyBytes)
            Log "Body (base64): $bodyBase64"
        } catch {}

        Write-Output '{"decision":"block","reason":"AethelHook API error - please check the service and try again."}'
        exit 2
    }

    # No response at all — API is genuinely offline — self-destruct to restore native dialogs
    Log "API unreachable (no response) - removing AethelHook hooks to restore native Claude dialogs: $_"

    $settingsPath = "$env:USERPROFILE\.claude\settings.json"
    $aethelAllow  = @("PowerShell(*)", "Write(*)", "Edit(*)", "Read(*)")
    if (Test-Path $settingsPath) {
        try {
            $s = Get-Content $settingsPath -Raw -Encoding utf8 | ConvertFrom-Json
            if ($s.PSObject.Properties['hooks']) { $s.PSObject.Properties.Remove('hooks') }
            if ($s.PSObject.Properties['permissions'] -and
                $s.permissions.PSObject.Properties['allow']) {
                $s.permissions.allow = @($s.permissions.allow | Where-Object { $aethelAllow -notcontains $_ })
                if ($s.permissions.allow.Count -eq 0) { $s.PSObject.Properties.Remove('permissions') }
            }
            $s | ConvertTo-Json -Depth 10 | Out-File $settingsPath -Encoding utf8 -Force
            Log "Hooks removed from settings.json - native dialogs will appear on next tool call"
        } catch {
            Log "Could not update settings.json: $_"
        }
    }

    Write-Output '{"decision":"block","reason":"AethelHook service is offline. Native dialogs are now active - re-run the command."}'
    exit 2
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

# Map phone decision: exit 0 = allow, exit 2 + {"decision":"block","reason":"..."} = block
switch ($internalDecision) {
    { $_ -in "allow", "allow_once" } {
        Log "Allowed"
        exit 0
    }
    { $_ -in "always_allow_project", "always_allow_global" } {
        "$cmdName" | Out-File -FilePath $phoneAllowPath -Append -ErrorAction SilentlyContinue
        Log "Added '$cmdName' to phone allow list"
        exit 0
    }
    "deny_with_reason" {
        $reason = if ($internalReason) { $internalReason } else { "User declined via phone" }
        $escaped = $reason -replace '"', '\"'
        Log "Denied with reason: $reason"
        Write-Output "{`"decision`":`"block`",`"reason`":`"$escaped`"}"
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
