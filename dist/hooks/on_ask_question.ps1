# AethelHook AskUserQuestion Hook - Claude Code edition
# Fires via PreToolUse before AskUserQuestion tool calls.
# Routes the question(s) to the phone; on answer, injects them back via
# hookSpecificOutput.updatedInput so Claude proceeds as if the user answered directly.
#
# Unlike on_approval_request.ps1, this hook FAILS OPEN: any failure (parse error, API
# unreachable, timeout, empty answer) exits 0 with NO stdout, letting Claude Code's
# native interactive dialog appear. This is a convenience feature, not a security gate.

$debugLog = "C:\ProgramData\AethelHook\hook_debug.log"
function Log($msg) {
    $line = "$(Get-Date -Format 'HH:mm:ss') [AskQ] $msg"
    try { $line | Out-File -FilePath $debugLog -Append -Encoding utf8 } catch {}
}

# Strategy switch: "updatedInput" confirmed working via Phase-0 live testing (2026-07-02).
# Kept as a single named variable so it can be flipped to "denyWithReason" later with no
# changes to the API or Android app if a future Claude Code version stops honoring it.
$Strategy = "updatedInput"

try {
    Log "--- AskUserQuestion Hook Triggered ---"

    $inputData = $null
    $stdinReader = [System.IO.StreamReader]::new([Console]::OpenStandardInput(), [System.Text.Encoding]::UTF8)
    $stdinTask = $stdinReader.ReadToEndAsync()
    if ($stdinTask.Wait(5000)) {
        $rawInput = $stdinTask.Result
        if ($rawInput) { $inputData = $rawInput | ConvertFrom-Json }
    }

    if (-not $inputData -or -not $inputData.tool_input -or -not $inputData.tool_input.questions) {
        Log "No questions in tool_input - failing open"
        exit 0
    }

    $questions  = $inputData.tool_input.questions
    # A fresh GUID per call, NOT $inputData.session_id - that's the whole conversation's
    # id and is IDENTICAL across every AskUserQuestion call in the same conversation.
    # Reusing it let a late-arriving answer (e.g. a phone response delayed by an API
    # restart mid-wait) get buffered server-side and wrongly consumed by the NEXT,
    # unrelated question in the same conversation - a real observed cross-question
    # contamination bug. on_approval_request.ps1 already does this correctly.
    $sessionId  = [System.Guid]::NewGuid().ToString()

    # Window-match key for send_answer_key.ps1: the actual triggering project's folder
    # name, not this project's own name - Find-WorkspaceWindow used to hardcode
    # "AethelHook", which only ever matched when AethelHook itself was the active
    # project (never any other project this hook fires for).
    $workspaceName = if ($inputData.cwd) { Split-Path $inputData.cwd -Leaf } else { "AethelHook" }

    $tokenPath = "C:\ProgramData\AethelHook\api_token.txt"
    $apiToken  = if (Test-Path $tokenPath) { (Get-Content $tokenPath -Raw -Encoding ascii).Trim() } else { "" }
    $authHeaders = if ($apiToken) { @{"X-AethelHook-Token" = $apiToken} } else { @{} }

    $body = @{
        session_id = $sessionId
        questions  = $questions
        cwd        = if ($inputData.cwd) { $inputData.cwd } else { "" }
    } | ConvertTo-Json -Depth 10 -Compress

    Log "Posting question(s) to API with session $sessionId ..."
    try {
        $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($body)
        Invoke-WebRequest `
            -Uri "http://localhost:5266/hook/ask-question" `
            -Method POST `
            -ContentType "application/json; charset=utf-8" `
            -Headers $authHeaders `
            -Body $bodyBytes `
            -TimeoutSec 5 `
            -UseBasicParsing `
            -ErrorAction Stop | Out-Null
    } catch {
        Log "POST /hook/ask-question failed - failing open: $_"
        exit 0
    }

    Log "Waiting for phone answer..."
    $answers = $null
    try {
        $response = Invoke-WebRequest `
            -Uri "http://localhost:5266/hook/wait-answer/$sessionId" `
            -Method GET `
            -Headers $authHeaders `
            -TimeoutSec 320 `
            -UseBasicParsing `
            -ErrorAction Stop

        if ($response.Content) {
            $resObj = $response.Content | ConvertFrom-Json
            if ($resObj.answers) {
                $propCount = ($resObj.answers | Get-Member -MemberType NoteProperty | Measure-Object).Count
                if ($propCount -gt 0) { $answers = $resObj.answers }
            }
        }
    } catch {
        Log "GET /hook/wait-answer failed - failing open: $_"
        exit 0
    }

    if (-not $answers) {
        Log "No answer received (timeout or empty) - failing open"
        exit 0
    }

    Log "Answer received - emitting hookSpecificOutput (strategy: $Strategy)"

    # Claude Code (as of the 2.1.199 update) no longer honors this hook's
    # hookSpecificOutput for AskUserQuestion either - the native dialog still renders
    # and a real PC interaction would silently override the phone's answer, exactly
    # like ExitPlanMode. send_answer_key.ps1 closes that gap for the simple case: one
    # question, single-select, answer matches a predefined option (not "Other" free
    # text). Multi-question/multi-select/"Other" cases are intentionally left
    # unautomated for now - falls through to hookSpecificOutput only (currently a
    # no-op) and the native dialog, same as before this fix.
    Log "Automation check: questions.Count=$($questions.Count) multiSelect=$($questions[0].multiSelect)"
    if ($questions.Count -eq 1 -and -not $questions[0].multiSelect) {
        $q = $questions[0]
        # Match by position, not by re-matching the question text as a dictionary key -
        # avoids encoding mismatches (e.g. em-dashes decode differently between the
        # stdin-parsed tool_input and the API's HTTP response in PowerShell 5.1) that
        # would otherwise silently break an exact-string lookup.
        $answerProp  = $answers | Get-Member -MemberType NoteProperty | Select-Object -First 1
        $answerValue = if ($answerProp) { $answers.($answerProp.Name) } else { $null }
        Log "Answer value resolved to: '$answerValue'"
        if ($answerValue -and $answerValue -isnot [System.Array]) {
            $optionIndex = -1
            for ($i = 0; $i -lt $q.options.Count; $i++) {
                if ($q.options[$i].label -eq $answerValue) { $optionIndex = $i; break }
            }
            if ($optionIndex -ge 0) {
                Log "Single-select match: '$answerValue' is option index $optionIndex - launching send_answer_key.ps1"
                Start-Process powershell.exe -WindowStyle Hidden -ArgumentList @(
                    "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                    (Join-Path $PSScriptRoot "send_answer_key.ps1"), "-OptionIndex", "$optionIndex",
                    "-WorkspaceName", "`"$workspaceName`""
                )
            } else {
                Log "Answer '$answerValue' did not match a predefined option (likely 'Other') - skipping automation"
            }
        } else {
            Log "Answer was multi-select array or empty - skipping automation"
        }
    } else {
        Log "Multiple questions or multiSelect - skipping automation, falling back to native dialog"
    }

    if ($Strategy -eq "updatedInput") {
        $output = @{
            hookSpecificOutput = @{
                hookEventName            = "PreToolUse"
                permissionDecision       = "allow"
                permissionDecisionReason = "Answered via phone"
                updatedInput             = @{
                    questions = $questions
                    answers   = $answers
                }
            }
        } | ConvertTo-Json -Depth 10 -Compress
    } else {
        # Fallback strategy: deny with the answer(s) formatted as plain-text feedback.
        $lines = @()
        foreach ($q in $questions) {
            $qText = $q.question
            $ans   = $answers.$qText
            if ($ans -is [System.Array]) { $ans = ($ans -join ", ") }
            $lines += "Q: $qText"
            $lines += "A: $ans"
        }
        $reasonText = "User answered via phone:`n" + ($lines -join "`n")
        $output = @{
            hookSpecificOutput = @{
                hookEventName            = "PreToolUse"
                permissionDecision       = "deny"
                permissionDecisionReason = $reasonText
            }
        } | ConvertTo-Json -Depth 10 -Compress
    }

    Log "Emitting: $output"
    Write-Output $output
    exit 0

} catch {
    Log "Unexpected error - failing open: $_"
    exit 0
}
