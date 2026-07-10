# AethelHook Stop Hook - Codex edition
# Fires when Codex finishes its turn. Extracts last agent response and sends to phone.

$debugLog = "C:\ProgramData\AethelHook\hook_debug_codex.log"
function Log($msg) {
    "$(Get-Date -Format 'HH:mm:ss') [Done] $msg" | Out-File -FilePath $debugLog -Append -Encoding utf8
}

Log "Stop hook fired"

# Read stdin - Codex provides last_assistant_message directly, plus the common
# cwd field every Codex hook event carries (confirmed present on PreToolUse too).
$summary = ""
$cwd     = $null
try {
    $reader   = [System.IO.StreamReader]::new([Console]::OpenStandardInput(), [System.Text.Encoding]::UTF8)
    $readTask = $reader.ReadToEndAsync()
    if ($readTask.Wait(3000)) {
        $raw = $readTask.Result
        if ($raw) {
            Log "stdin: $raw"
            $data = $raw | ConvertFrom-Json
            if ($data.cwd) { $cwd = $data.cwd }
            if ($data.last_assistant_message -and $data.last_assistant_message.Trim()) {
                $summary = $data.last_assistant_message.Trim()
                # Strip markdown formatting
                $summary = $summary -replace '\*\*(.+?)\*\*',              '$1'
                $summary = $summary -replace '\*([^*\r\n]+)\*',            '$1'
                $summary = $summary -replace '`([^`]+)`',                  '$1'
                $summary = $summary -replace '(?m)^#{1,6}\s+',             ''
                $summary = $summary -replace '\[([^\]]+)\]\([^\)]+\)',     '$1'
                $summary = $summary.Trim()
                if ($summary.Length -gt 4000) { $summary = $summary.Substring(0, 4000) + "..." }
                Log "Got summary from last_assistant_message ($($summary.Length) chars)"
            }
        }
    }
} catch { Log "stdin read failed: $_" }

$notifyBody = @{ message = "Codex finished" }
if ($summary) { $notifyBody.detail = $summary }
if ($cwd) { $notifyBody.cwd = $cwd }

# Dispatch the actual notify POST to a fully detached process (see notify_async.ps1)
# instead of waiting on it here - on a slow machine, PowerShell's own process-startup
# overhead can already eat most of Codex's Stop-hook timeout budget, so this hook's
# own synchronous work must not also include a network round-trip.
try {
    $json        = $notifyBody | ConvertTo-Json -Compress
    $payloadPath = [System.IO.Path]::GetTempFileName()
    [System.IO.File]::WriteAllText($payloadPath, $json, [System.Text.Encoding]::UTF8)

    $notifierScript = "C:\ProgramData\AethelHook\hooks\codex\notify_async.ps1"
    Start-Process -FilePath "powershell.exe" `
        -ArgumentList @("-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", $notifierScript, $payloadPath) `
        -WindowStyle Hidden
    Log "Notification dispatched async (summary: $(if ($summary) { 'yes' } else { 'no' }))"
} catch {
    Log "Failed to dispatch async notification: $_"
}

exit 0
