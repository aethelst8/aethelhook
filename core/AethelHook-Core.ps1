# AethelHook Core - IDE-agnostic shared logic
# Defines Invoke-AethelHookCore. Dot-source this from an IDE adapter.
# Returns a hashtable: Decision, Reason, CmdName, ToolName, CommandPreview, PhoneAllowPath

function Invoke-AethelHookCore {
    param(
        [string]$DebugLog      = "C:\ProgramData\AethelHook\hook_debug.log",
        [string]$PhoneAllowPath = "C:\ProgramData\AethelHook\phone_allow.txt",
        [string]$ApiBase        = "http://localhost:5264"
    )

    "fired $(Get-Date -Format o)" | Out-File -FilePath "C:\ProgramData\AethelHook\hook_fired.txt" -Append -Encoding ascii

    function Log($msg) {
        $line = "$(Get-Date -Format 'HH:mm:ss') $msg"
        $line | Out-File -FilePath $DebugLog -Append -Encoding ascii
        [Console]::Error.WriteLine($line)
    }
    Log "--- AethelHook Core Triggered ---"

    # Read stdin
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
            Log "stdin read timed out"
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

        $args = $null
        if ($inputData.tool_input) {
            $args = $inputData.tool_input
        } elseif ($inputData.toolCall -and $inputData.toolCall.args) {
            $args = $inputData.toolCall.args
        }

        if ($args) {
            if ($args.command)       { $commandPreview = "$($args.command)" }
            elseif ($args.file_path) { $commandPreview = "$($args.file_path)" }
            elseif ($args.CommandLine){ $commandPreview = "$($args.CommandLine)" }
            elseif ($args.TargetFile) { $commandPreview = "$($args.TargetFile)" }
            elseif ($args.path)      { $commandPreview = "$($args.path)" }
            else {
                $commandPreview = $args | ConvertTo-Json -Compress -Depth 3 -ErrorAction SilentlyContinue
            }
        }
    }

    $cmdName = if ($commandPreview) { ($commandPreview -split '[\s"'']+')[0].Trim() } else { $toolName }
    Log "Tool: $toolName | Cmd: $cmdName | Preview: $commandPreview"

    # Check phone-managed allow list
    if (Test-Path $PhoneAllowPath) {
        $allowedCmds = Get-Content $PhoneAllowPath -ErrorAction SilentlyContinue |
                       Where-Object { $_.Trim() -ne "" }
        if ($allowedCmds -contains $cmdName) {
            Log "'$cmdName' is in phone allow list - auto-approving"
            return @{
                Decision       = "allow"
                Reason         = ""
                CmdName        = $cmdName
                ToolName       = $toolName
                CommandPreview = $commandPreview
                PhoneAllowPath = $PhoneAllowPath
            }
        }
    }

    # POST event to API
    $sessionId = [System.Guid]::NewGuid().ToString()
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
        Invoke-WebRequest `
            -Uri "$ApiBase/hook/event" `
            -Method POST `
            -ContentType "application/json" `
            -Body $body `
            -TimeoutSec 5 `
            -UseBasicParsing `
            -ErrorAction Stop | Out-Null
        Log "Event posted OK"
    } catch {
        Log "Post failed (API not running?) - allowing through: $_"
        return @{
            Decision       = "allow"
            Reason         = ""
            CmdName        = $cmdName
            ToolName       = $toolName
            CommandPreview = $commandPreview
            PhoneAllowPath = $PhoneAllowPath
        }
    }

    # Long-poll for phone decision (up to 80s)
    $internalDecision = "deny"
    $internalReason   = ""
    try {
        $response = Invoke-WebRequest `
            -Uri "$ApiBase/hook/wait-decision/$sessionId" `
            -Method GET `
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

    Log "Decision: $internalDecision"

    return @{
        Decision       = $internalDecision
        Reason         = $internalReason
        CmdName        = $cmdName
        ToolName       = $toolName
        CommandPreview = $commandPreview
        PhoneAllowPath = $PhoneAllowPath
    }
}
