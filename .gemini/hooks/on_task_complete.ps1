# AethelHook - Antigravity agent-done hook
# Fires after the agent finishes its turn (SessionEnd / AfterAgent / Stop).
# Extracts last agent response and sends to phone as a notification.
# Must never block or crash - always exits 0.

$debugLog = "C:\ProgramData\AethelHook\hook_debug.log"
function Log($msg) {
    "$(Get-Date -Format 'HH:mm:ss') [Antigravity:Done] $msg" | Out-File -FilePath $debugLog -Append -Encoding utf8
}

Log "Agent-done hook fired"

# Read stdin - Antigravity may provide transcript_path or output
$transcriptPath = $null
try {
    $stdinReader = [System.IO.StreamReader]::new([Console]::OpenStandardInput(), [System.Text.Encoding]::UTF8)
    $stdinTask = $stdinReader.ReadToEndAsync()
    if ($stdinTask.Wait(3000)) {
        $raw = $stdinTask.Result
        if ($raw) {
            Log "stdin: $raw"
            $data = $raw | ConvertFrom-Json -ErrorAction SilentlyContinue
            if ($data -and $data.transcript_path) { $transcriptPath = $data.transcript_path }
        }
    }
} catch { Log "stdin read failed: $_" }

# Extract last assistant text from transcript (JSONL)
$summary = ""
if ($transcriptPath -and (Test-Path $transcriptPath)) {
    try {
        $lines = [System.IO.File]::ReadAllLines($transcriptPath, [System.Text.Encoding]::UTF8)
        for ($i = $lines.Count - 1; $i -ge 0; $i--) {
            try {
                $entry = $lines[$i] | ConvertFrom-Json -ErrorAction SilentlyContinue
                if ($entry.type -eq "assistant") {
                    $content = $entry.message.content
                    if (-not $content) { $content = $entry.content }
                    $textBlock = @($content) | Where-Object { $_.type -eq "text" } | Select-Object -Last 1
                    if ($textBlock -and $textBlock.text -and $textBlock.text.Trim()) {
                        $summary = $textBlock.text.Trim()
                        if ($summary.Length -gt 600) { $summary = $summary.Substring(0, 600) + "..." }
                        Log "Extracted summary ($($summary.Length) chars)"
                        break
                    }
                }
            } catch {}
        }
    } catch { Log "Transcript read failed: $_" }
} else {
    Log "No transcript path available (path: $transcriptPath)"
}

$tokenPath   = "C:\ProgramData\AethelHook\api_token.txt"
$apiToken    = if (Test-Path $tokenPath) { (Get-Content $tokenPath -Raw -Encoding ascii).Trim() } else { "" }
$authHeaders = if ($apiToken) { @{"X-AethelHook-Token" = $apiToken} } else { @{} }

$notifyBody = @{ message = "Antigravity finished" }
if ($summary) { $notifyBody.detail = $summary }

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
