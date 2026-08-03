# AethelHook Stop Hook - Devin CLI edition (standalone terminal CLI only, see
# on_pre_tool_use.ps1's own header for the ACP-mode scope note - the Devin IDE/Desktop
# app never fires any hook, including this one).
#
# Fires when the agent finishes a turn (interactive or headless -p). Devin's own Stop
# event carries no response-text field at all (confirmed live 2026-07-22 via
# hook_debug_devincli.log: {"hook_event_name":"Stop","stop_hook_active":false,
# "session_id":"...","prompt_id":"..."}) and there is no transcript file path either -
# Devin CLI stores session history in a SQLite database (sessions.db under
# %APPDATA%\devin\cli\), not a JSONL transcript file like the other four agents. The
# companion extract_summary.py reads that database directly (message_nodes.chat_message,
# most recent role=="assistant" row for this session_id) via Python's stdlib sqlite3 -
# confirmed live this is fast (~0.2s, dominated by interpreter startup) and safe to read
# concurrently while devin.exe still has the file open (WAL mode, confirmed via the
# sessions.db-wal/-shm files alongside it).
#
# Best-effort only: if Python isn't on PATH, or the extraction script fails/times out
# for any reason, this just falls back to a plain "Devin CLI finished" notification with
# no summary - never blocks or delays the notification itself.
#
# IMPORTANT: never emit a "decision" field on stdout here - Stop is one of the events
# that CAN block ("prevent premature stopping" per Devin's own docs), and a stray block
# decision would make the agent think it needs to keep going - the same class of
# doom-loop risk already hit and fixed for OpenCode (see CLAUDE.md gotcha #28). This
# script only ever notifies and exits 0 with no stdout output at all.

$debugLog = "C:\ProgramData\AethelHook\hook_debug_devincli.log"
function Log($msg) {
    try { "$(Get-Date -Format 'HH:mm:ss') [Stop] $msg" | Out-File -FilePath $debugLog -Append -Encoding utf8 -ErrorAction SilentlyContinue } catch {}
}
Log "Stop hook fired"

$cwd       = $null
$sessionId = $null
try {
    $reader   = [System.IO.StreamReader]::new([Console]::OpenStandardInput(), [System.Text.Encoding]::UTF8)
    $readTask = $reader.ReadToEndAsync()
    if ($readTask.Wait(3000)) {
        $raw = $readTask.Result
        if ($raw) {
            Log "stdin: $raw"
            $data = $raw | ConvertFrom-Json
            if ($data.session_id) { $sessionId = $data.session_id }
        }
    }
} catch { Log "stdin read failed: $_" }

# DEVIN_PROJECT_DIR is set automatically by Devin CLI per its own hooks docs.
if ($env:DEVIN_PROJECT_DIR) { $cwd = $env:DEVIN_PROJECT_DIR }

# --- Best-effort summary extraction via the companion Python script ---
$summary = $null
try {
    $pythonCmd = Get-Command python -ErrorAction SilentlyContinue
    if (-not $pythonCmd) { $pythonCmd = Get-Command python3 -ErrorAction SilentlyContinue }

    if ($pythonCmd -and $sessionId) {
        $extractScript = "C:\ProgramData\AethelHook\hooks\devincli\extract_summary.py"
        $psi = New-Object System.Diagnostics.ProcessStartInfo
        $psi.FileName               = $pythonCmd.Source
        $psi.Arguments              = "`"$extractScript`" `"$sessionId`""
        $psi.RedirectStandardOutput = $true
        $psi.UseShellExecute        = $false
        $psi.CreateNoWindow         = $true
        $proc = [System.Diagnostics.Process]::Start($psi)
        if ($proc.WaitForExit(5000)) {
            $rawOut = $proc.StandardOutput.ReadToEnd()
            if ($rawOut -and $rawOut.Trim()) {
                $summary = $rawOut.Trim()
                if ($summary.Length -gt 4000) { $summary = $summary.Substring(0, 4000) + [char]0x2026 }
                Log "Extracted summary via Python ($($summary.Length) chars)"
            } else {
                Log "Python extraction returned no output"
            }
        } else {
            Log "Python extraction timed out - killing"
            try { $proc.Kill() } catch {}
        }
    } else {
        Log "Python not found on PATH or no session_id - notification will have no summary"
    }
} catch {
    Log "Summary extraction failed: $_"
}

$tokenPath   = "C:\ProgramData\AethelHook\api_token.txt"
$apiToken    = if (Test-Path $tokenPath) { (Get-Content $tokenPath -Raw -Encoding ascii).Trim() } else { "" }
$authHeaders = if ($apiToken) { @{"X-AethelHook-Token" = $apiToken} } else { @{} }

$notifyBody = @{ message = "Devin CLI finished" }
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
