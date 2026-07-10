# AethelHook PostToolUse Hook - Phase 2 (Session Access)
# Fires after every tool call completes, mid-turn. Sends a cheap "still working"
# heartbeat to the phone so it has chunked progress visibility during a turn instead
# of only the final Stop summary. Fire-and-forget: never blocks the tool result, and
# never blocks the turn if the API is offline.

$debugLog = "C:\ProgramData\AethelHook\hook_debug.log"
function Log($msg) {
    $line = "$(Get-Date -Format 'HH:mm:ss') [ToolDone] $msg"
    try { $line | Out-File -FilePath $debugLog -Append -Encoding utf8 } catch {}
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

# Short, cheap preview only - no transcript parsing, keeps every-tool-call overhead low.
$detail = ""
if ($inputData.tool_input) {
    $args = $inputData.tool_input
    if ($args.command)        { $detail = "$($args.command)" }
    elseif ($args.file_path)  { $detail = "$($args.file_path)" }
    elseif ($args.path)       { $detail = "$($args.path)" }
}
# [char]0x2026 (ellipsis), not a literal "…" in the source - PowerShell 5.1 reads a
# BOM-less .ps1 file using the system's active code page, not UTF-8, so a literal
# multi-byte character embedded directly in the script gets corrupted before any of
# this script's own UTF-8-encoding-for-transmission logic ever runs. Confirmed live:
# showed up on the phone as "â€¦" (classic UTF-8-read-as-Windows-1252 mojibake).
if ($detail.Length -gt 150) { $detail = $detail.Substring(0, 150) + [char]0x2026 }

$tokenPath   = "C:\ProgramData\AethelHook\api_token.txt"
$apiToken    = if (Test-Path $tokenPath) { (Get-Content $tokenPath -Raw -Encoding ascii).Trim() } else { "" }
$authHeaders = if ($apiToken) { @{"X-AethelHook-Token" = $apiToken} } else { @{} }

$body = @{
    message   = "Still working..."
    detail    = $detail
    tool_name = $toolName
    cwd       = $inputData.cwd
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
