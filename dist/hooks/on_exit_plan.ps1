# AethelHook ExitPlanMode Hook - Claude Code edition
# Fires via PreToolUse before ExitPlanMode ("Accept this plan?") tool calls.
# Routes the full plan to the phone; the phone's response resolves the dialog on the PC.
#
# Same fail-open philosophy as on_ask_question.ps1: any failure (parse error, API
# unreachable, timeout, no answer) exits 0 with NO stdout, letting Claude Code's native
# "Accept this plan?" dialog appear. This is a convenience feature, not a security gate -
# it must never strand the session waiting on a phone that isn't there.

$debugLog = "C:\ProgramData\AethelHook\hook_debug.log"
function Log($msg) {
    $line = "$(Get-Date -Format 'HH:mm:ss') [ExitPlan] $msg"
    try { $line | Out-File -FilePath $debugLog -Append -Encoding utf8 } catch {}
}

try {
    Log "--- ExitPlanMode Hook Triggered ---"

    $inputData = $null
    $stdinReader = [System.IO.StreamReader]::new([Console]::OpenStandardInput(), [System.Text.Encoding]::UTF8)
    $stdinTask = $stdinReader.ReadToEndAsync()
    if ($stdinTask.Wait(5000)) {
        $rawInput = $stdinTask.Result
        if ($rawInput) { $inputData = $rawInput | ConvertFrom-Json }
    }

    if (-not $inputData) {
        Log "No stdin - failing open"
        exit 0
    }

    $plan = $inputData.tool_input.plan
    if (-not $plan -and $inputData.tool_input.planFilePath) {
        # planFilePath comes from the ExitPlanMode tool call's own arguments - i.e. it's
        # AI-generated and, via prompt injection against the agent, effectively
        # attacker-influenceable. Without confining it to the project directory, a
        # compromised agent turn could point this at an arbitrary file (api_token.txt,
        # a .env, an SSH key) and have its contents read and shipped to the phone as
        # if it were "the plan" - a real local-file-read/exfiltration primitive.
        $planPath = $inputData.tool_input.planFilePath
        $cwd      = $inputData.cwd
        $withinProject = $false
        if ($cwd -and (Test-Path $planPath)) {
            try {
                $resolvedPlanPath = [System.IO.Path]::GetFullPath($planPath)
                $resolvedCwd      = [System.IO.Path]::GetFullPath($cwd)
                if (-not $resolvedCwd.EndsWith([System.IO.Path]::DirectorySeparatorChar)) {
                    $resolvedCwd += [System.IO.Path]::DirectorySeparatorChar
                }
                if ($resolvedPlanPath.StartsWith($resolvedCwd, [System.StringComparison]::OrdinalIgnoreCase)) {
                    $withinProject = $true
                }
            } catch { Log "Failed to resolve planFilePath '$planPath': $_" }
        }
        if ($withinProject) {
            $plan = Get-Content $planPath -Raw -Encoding utf8
        } else {
            Log "planFilePath '$planPath' is outside the project directory ('$cwd') - refusing to read it"
        }
    }

    if (-not $plan) {
        Log "No plan text available - failing open"
        exit 0
    }

    # A fresh GUID per call, NOT $inputData.session_id - that's the whole conversation's
    # id and would collide across multiple ExitPlanMode calls in the same conversation
    # (e.g. a "keep planning" -> re-plan loop), same cross-call answer contamination bug
    # just fixed in on_ask_question.ps1.
    $sessionId = [System.Guid]::NewGuid().ToString()

    # Window-match key for send_plan_key.ps1: the actual triggering project's folder
    # name, not this project's own name - Find-WorkspaceWindow used to hardcode
    # "AethelHook", which only ever matched when AethelHook itself was the active
    # project (never any other project this hook fires for).
    $workspaceName = if ($inputData.cwd) { Split-Path $inputData.cwd -Leaf } else { "AethelHook" }

    $tokenPath = "C:\ProgramData\AethelHook\api_token.txt"
    $apiToken  = if (Test-Path $tokenPath) { (Get-Content $tokenPath -Raw -Encoding ascii).Trim() } else { "" }
    $authHeaders = if ($apiToken) { @{"X-AethelHook-Token" = $apiToken} } else { @{} }

    $body = @{
        session_id = $sessionId
        plan       = $plan
    } | ConvertTo-Json -Compress

    Log "Posting plan to API with session $sessionId ..."
    try {
        $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($body)
        Invoke-WebRequest `
            -Uri "http://localhost:5266/hook/plan-request" `
            -Method POST `
            -ContentType "application/json; charset=utf-8" `
            -Headers $authHeaders `
            -Body $bodyBytes `
            -TimeoutSec 5 `
            -UseBasicParsing `
            -ErrorAction Stop | Out-Null
    } catch {
        Log "POST /hook/plan-request failed - failing open: $_"
        exit 0
    }

    Log "Waiting for phone decision..."
    $decision = $null
    $feedback = ""
    try {
        $response = Invoke-WebRequest `
            -Uri "http://localhost:5266/hook/wait-plan-decision/$sessionId" `
            -Method GET `
            -Headers $authHeaders `
            -TimeoutSec 620 `
            -UseBasicParsing `
            -ErrorAction Stop

        if ($response.Content) {
            $resObj = $response.Content | ConvertFrom-Json
            if ($resObj.decision) {
                $decision = $resObj.decision
                $feedback = if ($resObj.feedback) { $resObj.feedback } else { "" }
            }
        }
    } catch {
        Log "GET /hook/wait-plan-decision failed - failing open: $_"
        exit 0
    }

    if (-not $decision) {
        Log "No decision received (timeout or empty) - failing open"
        exit 0
    }

    Log "Decision received: $decision"

    # Claude Code ignores this hook's permissionDecision for ExitPlanMode specifically
    # (confirmed via live testing) - the native dialog always renders regardless. So the
    # actual resolution comes from send_plan_key.ps1, launched detached below, which
    # sends the real keystroke once the dialog appears. This hookSpecificOutput is kept
    # anyway in case a future Claude Code version starts honoring it.
    $sendKeyScript = Join-Path $PSScriptRoot "send_plan_key.ps1"

    switch ($decision) {
        { $_ -in "yes_auto_accept", "yes_manual_approve" } {
            $label  = if ($decision -eq "yes_auto_accept") { "auto-accept edits" } else { "manually approve edits" }
            $key    = if ($decision -eq "yes_auto_accept") { "1" } else { "2" }
            $output = @{
                hookSpecificOutput = @{
                    hookEventName            = "PreToolUse"
                    permissionDecision       = "allow"
                    permissionDecisionReason = "Plan approved via phone ($label)"
                }
            } | ConvertTo-Json -Depth 10 -Compress
            Write-Output $output
            Start-Process powershell.exe -WindowStyle Hidden -ArgumentList @(
                "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $sendKeyScript, "-Key", $key,
                "-WorkspaceName", "`"$workspaceName`""
            )
        }
        "keep_planning" {
            $reasonText = if ($feedback) { $feedback } else { "User chose to keep planning via phone (no specific feedback given)" }
            $output = @{
                hookSpecificOutput = @{
                    hookEventName            = "PreToolUse"
                    permissionDecision       = "deny"
                    permissionDecisionReason = $reasonText
                }
            } | ConvertTo-Json -Depth 10 -Compress
            Write-Output $output
            # Feedback text is passed via a temp file, not a command-line argument -
            # Start-Process -ArgumentList in Windows PowerShell 5.1 does not reliably
            # quote array elements containing spaces, which silently truncates/breaks
            # multi-word feedback before send_plan_key.ps1 ever runs.
            # A fixed filename here would let another local process race to read/overwrite
            # it before send_plan_key.ps1 does - GetTempFileName() gives each invocation
            # its own unpredictable, atomically-created path instead.
            $feedbackFile = [System.IO.Path]::GetTempFileName()
            Set-Content -Path $feedbackFile -Value $reasonText -Encoding UTF8 -NoNewline
            Start-Process powershell.exe -WindowStyle Hidden -ArgumentList @(
                "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $sendKeyScript, "-Key", "3", "-FeedbackFile", $feedbackFile,
                "-WorkspaceName", "`"$workspaceName`""
            )
        }
        default {
            Log "Unrecognized decision '$decision' - failing open"
        }
    }
    exit 0

} catch {
    Log "Unexpected error - failing open: $_"
    exit 0
}
