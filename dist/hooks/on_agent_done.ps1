# AethelHook Stop Hook
# Fires when Claude Code finishes its turn.
# Reads the transcript to extract the final assistant message and sends it to the phone.

$debugLog = "C:\ProgramData\AethelHook\hook_debug.log"
function Log($msg) {
    $line = "$(Get-Date -Format 'HH:mm:ss') [Done] $msg"
    try { $line | Out-File -FilePath $debugLog -Append -Encoding utf8 } catch {}
}

Log "Stop hook fired"

# --- Read stdin (Stop hook provides session_id, transcript_path, stop_hook_active) ---
$transcriptPath = $null
$cwd = $null
try {
    $reader   = [System.IO.StreamReader]::new([Console]::OpenStandardInput(), [System.Text.Encoding]::UTF8)
    $readTask = $reader.ReadToEndAsync()
    if ($readTask.Wait(3000)) {
        $raw = $readTask.Result
        if ($raw) {
            Log "stdin: $raw"
            $data = $raw | ConvertFrom-Json
            if ($data.transcript_path) { $transcriptPath = $data.transcript_path }
            if ($data.cwd) { $cwd = $data.cwd }
        }
    }
} catch { Log "stdin read failed: $_" }

# --- Extract the last assistant text message from the transcript ---
$summary = ""
if ($transcriptPath -and (Test-Path $transcriptPath)) {
    try {
        $lines = [System.IO.File]::ReadAllLines($transcriptPath, [System.Text.Encoding]::UTF8)
        for ($i = $lines.Count - 1; $i -ge 0; $i--) {
            try {
                $entry = $lines[$i] | ConvertFrom-Json -ErrorAction SilentlyContinue
                # Claude Code transcript format: {type:"assistant", message:{role:"assistant", content:[{type:"text",text:"..."}]}}
                if ($entry.type -eq "assistant") {
                    $content = $entry.message.content
                    if (-not $content) { $content = $entry.content }
                    $textBlock = @($content) | Where-Object { $_.type -eq "text" } | Select-Object -Last 1
                    if ($textBlock -and $textBlock.text -and $textBlock.text.Trim()) {
                        $summary = $textBlock.text.Trim()
                        # Strip markdown formatting
                        $summary = $summary -replace '\*\*(.+?)\*\*',          '$1'
                        $summary = $summary -replace '\*([^*\r\n]+)\*',        '$1'
                        $summary = $summary -replace '`([^`]+)`',              '$1'
                        $summary = $summary -replace '(?m)^#{1,6}\s+',         ''
                        $summary = $summary -replace '\[([^\]]+)\]\([^\)]+\)', '$1'
                        $summary = $summary.Trim()
                        # [char]0x2026 (ellipsis), not a literal "…" in the source - PowerShell 5.1
                        # reads a BOM-less .ps1 file using the system's active code page, not UTF-8,
                        # so a literal multi-byte character embedded directly in the script gets
                        # corrupted before any UTF-8-encoding-for-transmission logic ever runs.
                        if ($summary.Length -gt 4000) { $summary = $summary.Substring(0, 4000) + [char]0x2026 }
                        Log "Extracted summary ($($summary.Length) chars)"
                        break
                    }
                }
            } catch {}
        }
    } catch { Log "Transcript read failed: $_" }
} else {
    Log "No transcript path in stdin (path: $transcriptPath)"
}

# --- Send notification ---
$tokenPath   = "C:\ProgramData\AethelHook\api_token.txt"
$apiToken    = if (Test-Path $tokenPath) { (Get-Content $tokenPath -Raw -Encoding ascii).Trim() } else { "" }
$authHeaders = if ($apiToken) { @{"X-AethelHook-Token" = $apiToken} } else { @{} }

$notifyBody = @{ message = "Claude Code finished" }
if ($summary) { $notifyBody.detail = $summary }
if ($cwd) { $notifyBody.cwd = $cwd }

try {
    $json      = $notifyBody | ConvertTo-Json -Compress
    $jsonBytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    Invoke-WebRequest `
        -Uri "http://localhost:5266/hook/notify" `
        -Method POST `
        -ContentType "application/json; charset=utf-8" `
        -Headers $authHeaders `
        -Body $jsonBytes `
        -TimeoutSec 3 `
        -UseBasicParsing `
        -ErrorAction Stop | Out-Null
    Log "Notification sent OK (summary: $(if ($summary) { 'yes' } else { 'no' }))"
} catch {
    Log "API not reachable - skipping: $_"
}

exit 0
