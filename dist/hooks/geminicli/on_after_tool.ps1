# AethelHook AfterTool Hook - Gemini CLI edition (Session Access chunked progress)
# Fires after every tool call completes, mid-turn. Sends a cheap "still working"
# heartbeat to the phone, same purpose as Claude's on_tool_done.ps1 (PostToolUse).
# Fire-and-forget: never blocks the tool result, never blocks the turn if the API is
# offline. AfterTool's exact stdin field names beyond the common base payload
# (session_id/transcript_path/cwd/hook_event_name/timestamp/tool_name) were not
# separately live-verified from BeforeTool's - assumed symmetric (tool_name/tool_input),
# same field names confirmed live for BeforeTool. Worst case if wrong: this heartbeat's
# "detail" preview comes out blank, which is harmless (the message itself still sends).

$debugLog = "C:\ProgramData\AethelHook\hook_debug_geminicli.log"
function Log($msg) {
    try { "$(Get-Date -Format 'HH:mm:ss') [AfterTool] $msg" | Out-File -FilePath $debugLog -Append -Encoding utf8 -ErrorAction SilentlyContinue } catch {}
}

$inputData = $null
try {
    $stdinReader = [System.IO.StreamReader]::new([Console]::OpenStandardInput(), [System.Text.Encoding]::UTF8)
    $stdinTask = $stdinReader.ReadToEndAsync()
    if ($stdinTask.Wait(3000)) {
        $rawInput = $stdinTask.Result
        if ($rawInput) { $inputData = $rawInput | ConvertFrom-Json }
    }
} catch { Log "stdin read failed: $_" }

if (-not $inputData) { exit 0 }

$toolName = if ($inputData.tool_name) { $inputData.tool_name } else { "tool" }

$detail = ""
if ($inputData.tool_input) {
    $toolArgs = $inputData.tool_input
    if ($toolArgs.command)       { $detail = "$($toolArgs.command)" }
    elseif ($toolArgs.file_path) { $detail = "$($toolArgs.file_path)" }
    elseif ($toolArgs.dir_path)  { $detail = "$($toolArgs.dir_path)" }
    elseif ($toolArgs.query)     { $detail = "$($toolArgs.query)" }
    elseif ($toolArgs.path)      { $detail = "$($toolArgs.path)" }
}
if ($detail.Length -gt 150) { $detail = $detail.Substring(0, 150) + [char]0x2026 }

$tokenPath   = "C:\ProgramData\AethelHook\api_token.txt"
$apiToken    = if (Test-Path $tokenPath) { (Get-Content $tokenPath -Raw -Encoding ascii).Trim() } else { "" }
$authHeaders = if ($apiToken) { @{"X-AethelHook-Token" = $apiToken} } else { @{} }

$body = @{
    message   = "Still working..."
    detail    = $detail
    tool_name = $toolName
    cwd       = $inputData.cwd
    agent     = "gemini"
} | ConvertTo-Json -Compress

try {
    $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($body)
    Invoke-WebRequest `
        -Uri "http://localhost:5266/hook/session-update" `
        -Method POST `
        -ContentType "application/json; charset=utf-8" `
        -Headers $authHeaders `
        -Body $bodyBytes `
        -TimeoutSec 3 `
        -UseBasicParsing `
        -ErrorAction Stop | Out-Null
} catch {
    Log "API not reachable - skipping: $_"
}

exit 0
