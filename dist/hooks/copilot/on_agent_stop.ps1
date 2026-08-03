# AethelHook agentStop Hook - GitHub Copilot CLI edition
# Fires when the main agent finishes a turn. Deliberately NOT also wired to
# sessionEnd - Gemini CLI's equivalent (AfterAgent + SessionEnd both wired) produced
# a duplicate "finished" notification on every headless run, since a single-prompt
# `-p` run's process exits right after its one turn, firing both events together.
# agentStop alone already covers both the headless single-turn case and an
# interactive session's per-turn case - see CLAUDE.md gotcha #32 for the original
# bug this avoids repeating.
#
# Confirmed live 2026-07-22 (hook_debug_copilot.log): agentStop's own payload is
# {sessionId, timestamp, cwd, transcriptPath, stopReason, stop_hook_active} - NO
# response text field at all, unlike Claude's Stop hook (last_assistant_message) or
# Gemini's AfterAgent (prompt_response). The actual reply only exists in the
# transcript file at transcriptPath - a JSONL event log - as the most recent
# {"type":"assistant.message","data":{"content":"..."}} line (same event shape
# already confirmed live in RunHeadlessCopilotPromptAsync's own stream parsing).

$debugLog = "C:\ProgramData\AethelHook\hook_debug_copilot.log"
function Log($msg) {
    try { "$(Get-Date -Format 'HH:mm:ss') [Done] $msg" | Out-File -FilePath $debugLog -Append -Encoding utf8 -ErrorAction SilentlyContinue } catch {}
}

Log "agentStop hook fired"

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

            $rawSummary = $null
            if ($data.transcriptPath -and (Test-Path $data.transcriptPath)) {
                try {
                    $transcriptLines = Get-Content -Path $data.transcriptPath -Encoding UTF8 -ErrorAction Stop
                    for ($i = $transcriptLines.Count - 1; $i -ge 0; $i--) {
                        if (-not $transcriptLines[$i]) { continue }
                        try {
                            $evt = $transcriptLines[$i] | ConvertFrom-Json -ErrorAction Stop
                        } catch { continue }
                        if ($evt.type -eq "assistant.message" -and $evt.data -and $evt.data.content) {
                            $rawSummary = $evt.data.content
                            break
                        }
                    }
                } catch {
                    Log "Failed to read transcript at $($data.transcriptPath): $_"
                }
            }

            if ($rawSummary -and "$rawSummary".Trim()) {
                $summary = "$rawSummary".Trim()
                $summary = $summary -replace '\*\*(.+?)\*\*',          '$1'
                $summary = $summary -replace '\*([^*\r\n]+)\*',        '$1'
                $summary = $summary -replace '`([^`]+)`',               '$1'
                $summary = $summary -replace '(?m)^#{1,6}\s+',          ''
                $summary = $summary -replace '\[([^\]]+)\]\([^\)]+\)',  '$1'
                $summary = $summary.Trim()
                if ($summary.Length -gt 4000) { $summary = $summary.Substring(0, 4000) + "..." }
                Log "Got summary ($($summary.Length) chars)"
            }
        }
    }
} catch { Log "stdin read failed: $_" }

$notifyBody = @{ message = "Copilot finished" }
if ($summary) { $notifyBody.detail = $summary }
if ($cwd) { $notifyBody.cwd = $cwd }

# Dispatch to a fully detached process, same reasoning as the other three agents'
# notify_async.ps1 scripts - this hook's own synchronous work must not include a
# network round-trip.
try {
    $json        = $notifyBody | ConvertTo-Json -Compress
    $payloadPath = [System.IO.Path]::GetTempFileName()
    [System.IO.File]::WriteAllText($payloadPath, $json, [System.Text.Encoding]::UTF8)

    $notifierScript = "C:\ProgramData\AethelHook\hooks\copilot\notify_async.ps1"
    Start-Process -FilePath "powershell.exe" `
        -ArgumentList @("-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", $notifierScript, $payloadPath) `
        -WindowStyle Hidden
    Log "Notification dispatched async (summary: $(if ($summary) { 'yes' } else { 'no' }))"
} catch {
    Log "Failed to dispatch async notification: $_"
}

exit 0
